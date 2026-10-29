package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.BinaryChannel;
import io.github.b077as.jolt.ChannelOptions;
import io.github.b077as.jolt.Dialogs;
import io.github.b077as.jolt.FileDialogOptions;
import io.github.b077as.jolt.FileFilter;
import io.github.b077as.jolt.WebWindow;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The Java half of {@code jolt.saveAs(data, options)}: shows the save dialog the page asks for, then writes the bytes the page
 * sends to the file the user chose. The page never learns the path, only the file's name.
 *
 * <p>Messages are binary, numbers big-endian, strings a {@code u32} length and UTF-8 bytes. Every message starts with a type
 * byte and a {@code u32} request id.
 * <pre>
 * page to Java   SAVE (1)  title, name, filter count, per filter: name, extension count, extensions
 *                DATA (2)  the next bytes of the file
 *                END  (3)  all bytes sent
 *                ABORT (4) the page gave up
 * Java to page   CHOSEN (11)    the file name the user chose; send DATA now
 *                CANCELLED (12) the user closed the dialog
 *                DONE (13)      u64 bytes written
 *                ERROR (14)     message
 * </pre>
 * The bytes go to a temporary file next to the target and replace it only when the page says END, so a failed transfer never
 * leaves half a file where the user asked for a whole one.
 */
public final class PageSaveAs {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.saveas");

    /** The channel the page script talks to. */
    public static final String CHANNEL = "jolt.saveAs";

    private static final int SAVE = 1, DATA = 2, END = 3, ABORT = 4;
    private static final int CHOSEN = 11, CANCELLED = 12, DONE = 13, ERROR = 14;
    private static final int MAX_STRING_BYTES = 4096, MAX_FILTERS = 64, MAX_EXTENSIONS = 64;

    private final BinaryChannel channel;
    private final Supplier<Dialogs> dialogs;
    private volatile Path initialDirectory;
    private final Map<Integer, Transfer> transfers = new ConcurrentHashMap<>();

    /** One file being received. */
    private static final class Transfer {
        final Path target;
        final Path partial;
        final OutputStream out;
        long written;

        Transfer(Path target, Path partial, OutputStream out) {
            this.target = target;
            this.partial = partial;
            this.out = out;
        }
    }

    public PageSaveAs(BinaryChannel channel, Supplier<Dialogs> dialogs, Path initialDirectory) {
        this.channel = channel;
        this.dialogs = dialogs;
        this.initialDirectory = initialDirectory;
        channel.onMessage(this::handle);
        channel.onClose(this::abortAll);
    }

    private static final Map<WebWindow, PageSaveAs> ENABLED = new java.util.IdentityHashMap<>();

    /**
     * Lets the pages of {@code window} call {@code jolt.saveAs}. Calling it again only changes the folder the dialog starts in.
     *
     * @param initialDirectory where the save dialog starts, or {@code null} for where the system last had the user
     */
    public static void enable(WebWindow window, Path initialDirectory) {
        synchronized (ENABLED) {
            PageSaveAs existing = ENABLED.get(window);
            if (existing != null) {
                existing.initialDirectory = initialDirectory;
                return;
            }
            ChannelServer server = ChannelServers.of(window);
            PageSaveAs saveAs = new PageSaveAs(server.open(CHANNEL, ChannelOptions.defaults()), window::dialogs, initialDirectory);
            ENABLED.put(window, saveAs);
            window.onClosed(() -> {
                synchronized (ENABLED) {
                    ENABLED.remove(window);
                }
                saveAs.abortAll();
            });
            String script = SaveAsScript.build();
            window.addInitScript(script);
            window.eval(script).exceptionally(t -> null);
        }
    }

    /** Discards every transfer that has not finished. */
    public void abortAll() {
        for (Integer id : List.copyOf(transfers.keySet())) {
            abort(id);
        }
    }

    private void handle(ByteBuffer message) {
        try {
            if (message.remaining() < 5) {
                return;
            }
            int type = message.get() & 0xFF;
            int id = message.getInt();
            switch (type) {
                case SAVE -> save(id, message);
                case DATA -> data(id, message);
                case END -> end(id);
                case ABORT -> abort(id);
                default -> { }
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "a saveAs message could not be handled", e);
        }
    }

    private void save(int id, ByteBuffer message) {
        FileDialogOptions options;
        try {
            options = options(message);
        } catch (RuntimeException e) {
            reply(ERROR, id, string("invalid saveAs options: " + e.getMessage()));
            return;
        }
        Dialogs d;
        try {
            d = dialogs.get();
        } catch (RuntimeException e) {
            reply(ERROR, id, string("this window cannot show a save dialog"));
            return;
        }
        d.saveFile(options).whenComplete((chosen, error) -> {
            if (error != null) {
                reply(ERROR, id, string("the save dialog failed: " + error.getMessage()));
            } else if (chosen.isEmpty()) {
                reply(CANCELLED, id);
            } else {
                begin(id, chosen.get());
            }
        });
    }

    private FileDialogOptions options(ByteBuffer m) {
        String title = string(m);
        String name = string(m);
        int count = m.getInt();
        if (count < 0 || count > MAX_FILTERS) {
            throw new IllegalArgumentException("too many filters");
        }
        List<FileFilter> filters = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String filterName = string(m);
            int extensions = m.getInt();
            if (extensions < 0 || extensions > MAX_EXTENSIONS) {
                throw new IllegalArgumentException("too many extensions");
            }
            List<String> list = new ArrayList<>();
            for (int j = 0; j < extensions; j++) {
                list.add(string(m));
            }
            filters.add(new FileFilter(filterName, list));
        }
        FileDialogOptions options = FileDialogOptions.defaults();
        if (!title.isEmpty()) {
            options = options.withTitle(title);
        }
        if (!name.isEmpty()) {
            options = options.withFileName(name);
        }
        if (initialDirectory != null) {
            options = options.withInitialDirectory(initialDirectory);
        }
        return options.withFilters(filters.toArray(FileFilter[]::new));
    }

    private void begin(int id, Path target) {
        try {
            Path folder = target.toAbsolutePath().getParent();
            Path partial = Files.createTempFile(folder, ".jolt-", ".part");
            transfers.put(id, new Transfer(target, partial, Files.newOutputStream(partial)));
            reply(CHOSEN, id, string(target.getFileName().toString()));
        } catch (IOException | RuntimeException e) {
            reply(ERROR, id, string("cannot write there: " + e.getMessage()));
        }
    }

    private void data(int id, ByteBuffer message) {
        Transfer t = transfers.get(id);
        if (t == null) {
            return;
        }
        try {
            byte[] bytes = new byte[message.remaining()];
            message.get(bytes);
            t.out.write(bytes);
            t.written += bytes.length;
        } catch (IOException e) {
            abort(id);
            reply(ERROR, id, string("writing the file failed: " + e.getMessage()));
        }
    }

    private void end(int id) {
        Transfer t = transfers.remove(id);
        if (t == null) {
            return;
        }
        try {
            t.out.close();
            try {
                Files.move(t.partial, t.target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(t.partial, t.target, StandardCopyOption.REPLACE_EXISTING);
            }
            ByteBuffer size = ByteBuffer.allocate(8).putLong(t.written);
            reply(DONE, id, size.array());
        } catch (IOException e) {
            discard(t);
            reply(ERROR, id, string("saving the file failed: " + e.getMessage()));
        }
    }

    private void abort(int id) {
        Transfer t = transfers.remove(id);
        if (t != null) {
            discard(t);
        }
    }

    private static void discard(Transfer t) {
        try {
            t.out.close();
        } catch (IOException e) {
            // being deleted anyway
        }
        try {
            Files.deleteIfExists(t.partial);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "could not delete " + t.partial, e);
        }
    }

    // ---- wire format ----

    private void reply(int type, int id, byte[]... parts) {
        int size = 5;
        for (byte[] part : parts) {
            size += part.length;
        }
        ByteBuffer out = ByteBuffer.allocate(size).put((byte) type).putInt(id);
        for (byte[] part : parts) {
            out.put(part);
        }
        channel.send(out.flip());
    }

    private static byte[] string(String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + utf8.length).putInt(utf8.length).put(utf8).array();
    }

    private static String string(ByteBuffer m) {
        int length = m.getInt();
        if (length < 0 || length > MAX_STRING_BYTES || length > m.remaining()) {
            throw new IllegalArgumentException("bad string length");
        }
        byte[] bytes = new byte[length];
        m.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
