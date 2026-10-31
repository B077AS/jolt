package io.github.b077as.jolt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Makes sure only one copy of the application runs per user. The first copy to call {@link #acquire} is the <em>primary</em>; a later
 * copy hands its command-line arguments to the primary, which is told through a callback (typically to bring its window to the front),
 * and learns it is not the primary, so it can simply exit. Call it first thing in {@code main}, before creating the application.
 *
 * <pre>{@code
 * SingleInstance instance = SingleInstance.acquire(options, List.of(args), otherArgs -> app.dispatch(main::focus));
 * if (!instance.isPrimary()) {
 *     return;                           // the running copy has been told; nothing more to do
 * }
 * }</pre>
 *
 * <p>It uses a lock file in the application's data directory (the operating system releases it if the process dies, so a crash never
 * leaves the application locked) and a Unix domain socket for the arguments, which only the same user can reach. Keep the returned
 * object (or call {@link #close()} at exit) for as long as the application runs.
 */
public final class SingleInstance implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt");
    private static final int MAGIC = 0x4A4F4C54; // "JOLT"
    private static final int MAX_ARGUMENTS = 256;
    /** A Unix socket path must fit in about 108 bytes on Linux. */
    private static final int MAX_SOCKET_PATH = 100;

    private final boolean primary;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final ServerSocketChannel server;
    private final Path socketPath;

    private SingleInstance(boolean primary, FileChannel lockChannel, FileLock lock, ServerSocketChannel server, Path socketPath) {
        this.primary = primary;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.server = server;
        this.socketPath = socketPath;
    }

    /**
     * @param args           the arguments of this launch, handed to the primary when this copy is not the first
     * @param onOtherLaunch  called when another copy was started, with its arguments, on a thread of its own (use
     *                       {@link Application#dispatch} to reach the UI thread); only used by the primary
     * @throws java.io.UncheckedIOException if the lock file cannot be created
     */
    public static SingleInstance acquire(AppOptions options, List<String> args, Consumer<List<String>> onOtherLaunch) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(onOtherLaunch, "onOtherLaunch");
        try {
            Path directory = options.dataDirectory();
            Files.createDirectories(directory);
            FileChannel channel = FileChannel.open(directory.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = null;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                // another SingleInstance of this JVM holds it: the same as another process
            }
            Path socket = socketPath(options);
            if (lock == null) {
                channel.close();
                forward(socket, args);
                return new SingleInstance(false, null, null, null, socket);
            }
            Files.deleteIfExists(socket); // left by a primary that died
            ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            Thread acceptor = new Thread(() -> acceptLoop(server, onOtherLaunch), "jolt-single-instance");
            acceptor.setDaemon(true);
            acceptor.start();
            return new SingleInstance(true, channel, lock, server, socket);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("cannot set up the single-instance lock", e);
        }
    }

    /** Whether this copy is the first one; if not, its arguments have been handed to the primary and it should exit. */
    public boolean isPrimary() {
        return primary;
    }

    /** Lets another copy become the primary. */
    @Override
    public void close() {
        if (!primary) {
            return;
        }
        try {
            server.close();
        } catch (IOException e) {
            // closing
        }
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException e) {
            // best effort
        }
        try {
            lock.release();
            lockChannel.close();
        } catch (IOException e) {
            // closing
        }
    }

    // ---- internals ----

    private static void acceptLoop(ServerSocketChannel server, Consumer<List<String>> onOtherLaunch) {
        while (server.isOpen()) {
            SocketChannel client;
            try {
                client = server.accept();
            } catch (IOException e) {
                return; // closed
            }
            Thread.ofVirtual().name("jolt-single-instance-client").start(() -> {
                try (client; DataInputStream in = new DataInputStream(Channels.newInputStream(client))) {
                    if (in.readInt() != MAGIC) {
                        return;
                    }
                    int count = in.readInt();
                    if (count < 0 || count > MAX_ARGUMENTS) {
                        return;
                    }
                    List<String> args = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        args.add(in.readUTF());
                    }
                    onOtherLaunch.accept(List.copyOf(args));
                } catch (IOException | RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "a message from another copy of the application was not understood", e);
                }
            });
        }
    }

    private static void forward(Path socket, List<String> args) throws IOException {
        IOException failure = null;
        for (int attempt = 0; attempt < 30; attempt++) { // the primary may still be starting up
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(UnixDomainSocketAddress.of(socket));
                DataOutputStream out = new DataOutputStream(Channels.newOutputStream(channel));
                out.writeInt(MAGIC);
                out.writeInt(Math.min(args.size(), MAX_ARGUMENTS));
                for (int i = 0; i < Math.min(args.size(), MAX_ARGUMENTS); i++) {
                    out.writeUTF(args.get(i));
                }
                out.flush();
                return;
            } catch (IOException e) {
                failure = e;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        LOG.log(System.Logger.Level.WARNING, "another copy of the application is running but could not be reached", failure);
    }

    /**
     * Where the socket lives: in the data directory if the path is short enough for a Unix socket, otherwise in a private directory
     * (named after the application and user) under the runtime or temporary directory. Both copies compute the same path.
     */
    static Path socketPath(AppOptions options) throws IOException {
        Path inData = options.dataDirectory().toAbsolutePath().resolve("instance.sock");
        if (inData.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_SOCKET_PATH) {
            return inData;
        }
        String base = System.getenv("XDG_RUNTIME_DIR");
        Path root = Path.of(base != null && !base.isBlank() ? base : System.getProperty("java.io.tmpdir"));
        String id;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((options.appId() + "|" + System.getProperty("user.name")).getBytes(StandardCharsets.UTF_8));
            id = HexFormat.of().formatHex(hash, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Path directory = root.resolve("jolt-" + id);
        if (!Files.isDirectory(directory)) {
            try {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException e) {
                Files.createDirectories(directory); // not POSIX: the temporary directory is per user already
            }
        }
        return directory.resolve("i.sock");
    }
}
