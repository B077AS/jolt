package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.BinaryChannel;
import io.github.b077as.jolt.ChannelOptions;
import io.github.b077as.jolt.SecurityPolicy;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Carries the {@link BinaryChannel}s of one window: a WebSocket server on the loopback interface that only the window's own
 * page can use.
 *
 * <p>Any local program and any web page the user has open in a browser can connect to a loopback port, so a connection is
 * accepted only if
 * <ul>
 *   <li>the {@code Host} header names this server (stops DNS rebinding);
 *   <li>the {@code Origin} header, which a browser always sends and a page cannot forge, is trusted by the window's current
 *       {@link SecurityPolicy};
 *   <li>the per-run secret in the URL matches. Only the script Jolt gives the window knows it, and that script is the only
 *       code that connects;
 *   <li>a channel of that name is open on the Java side.
 * </ul>
 */
public final class ChannelServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.channel");
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final int MAX_HEADER_BYTES = 8 * 1024;
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 5_000;

    private final ServerSocket server;
    private final Supplier<SecurityPolicy> policy;
    private final String token;
    private final List<String> hosts;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public ChannelServer(Supplier<SecurityPolicy> policy) throws IOException {
        this.policy = policy;
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        hosts = List.of("127.0.0.1:" + port(), "localhost:" + port());
        Thread.ofPlatform().daemon().name("jolt-channels-accept").start(this::acceptLoop);
    }

    public int port() {
        return server.getLocalPort();
    }

    /**
     * Opens the channel {@code name}.
     *
     * @throws IllegalArgumentException if the name is empty, longer than 128 characters or has characters other than letters,
     *                                  digits and {@code . _ : -}
     * @throws IllegalStateException    if a channel of that name is already open
     */
    public BinaryChannel open(String name, ChannelOptions options) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a channel name is 1 to 128 letters, digits or . _ : - : " + name);
        }
        if (closed) {
            throw new IllegalStateException("the window is closed");
        }
        Channel channel = new Channel(name, options);
        if (channels.putIfAbsent(name, channel) != null) {
            throw new IllegalStateException("channel already open: " + name);
        }
        return channel;
    }

    /** The per-run secret a page must send to connect (it is inside {@link #script()}). */
    public String token() {
        return token;
    }

    /** The script that gives the page {@code jolt.channel(name)}. Safe to run more than once in a document. */
    public String script() {
        return ChannelScript.build(port(), token);
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException e) {
            // already closed
        }
        for (Channel channel : List.copyOf(channels.values())) {
            channel.close();
        }
    }

    // ---- accepting ----

    private void acceptLoop() {
        while (!closed) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                return;
            }
            Thread.ofVirtual().name("jolt-channel").start(() -> serve(socket));
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 8 * 1024);
            Request request = readRequest(in);
            Channel channel = request == null ? null : handshake(socket, request);
            if (channel == null) {
                socket.close();
                return;
            }
            socket.setSoTimeout(0);
            ChannelConnection connection = new ChannelConnection(socket, in, channel, channel.options.latestOnly(),
                    channel.options.maxQueuedBytes(), channel.options.maxMessageBytes());
            if (!channel.attach(connection)) {
                connection.close(1001);
                return;
            }
            connection.run();
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // gone
            }
        }
    }

    private record Request(String method, URI target, Map<String, String> headers) {}

    /** Reads the HTTP request line and headers; {@code null} if they are malformed or too long. */
    private static Request readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        int matched = 0; // how much of \r\n\r\n has just been seen
        while (matched < 4) {
            int b = in.read();
            if (b < 0 || raw.size() >= MAX_HEADER_BYTES) {
                return null;
            }
            raw.write(b);
            matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        String[] lines = raw.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length != 3) {
            return null;
        }
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.merge(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim(),
                        (a, b) -> a + ", " + b);
            }
        }
        try {
            return new Request(requestLine[0], new URI(requestLine[1]), headers);
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    /** Checks the request and answers it; returns the channel to attach, or {@code null} after refusing. */
    private Channel handshake(Socket socket, Request request) throws IOException {
        Map<String, String> headers = request.headers;
        OutputStream out = socket.getOutputStream();
        String upgrade = headers.getOrDefault("upgrade", "");
        String connection = headers.getOrDefault("connection", "").toLowerCase(Locale.ROOT);
        String key = headers.get("sec-websocket-key");
        if (!request.method.equals("GET") || !upgrade.equalsIgnoreCase("websocket") || !connection.contains("upgrade")
                || key == null || !"13".equals(headers.get("sec-websocket-version"))) {
            refuse(out, "400 Bad Request");
            return null;
        }
        if (!hosts.contains(headers.getOrDefault("host", "").toLowerCase(Locale.ROOT))
                || !policy.get().trusts(headers.get("origin"))
                || !tokenMatches(queryParameter(request.target, "t"))) {
            LOG.log(System.Logger.Level.WARNING, "A channel connection was refused: origin " + headers.get("origin")
                    + " is not trusted or the secret is wrong");
            refuse(out, "403 Forbidden");
            return null;
        }
        String path = request.target.getPath();
        Channel channel = path != null && path.startsWith("/c/") ? channels.get(URLDecoder.decode(path.substring(3), StandardCharsets.UTF_8)) : null;
        if (channel == null) {
            refuse(out, "404 Not Found");
            return null;
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                + accept(key) + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        return channel;
    }

    private static void refuse(OutputStream out, String status) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static String accept(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String queryParameter(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith(name + "=")) {
                    return URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private boolean tokenMatches(String candidate) {
        return candidate != null
                && MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    // ---- the channel the application holds ----

    private final class Channel implements BinaryChannel, ChannelConnection.Listener {

        private final String name;
        private final ChannelOptions options;
        private final AtomicReference<ChannelConnection> active = new AtomicReference<>();
        private final CopyOnWriteArrayList<Consumer<ByteBuffer>> messageListeners = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<Runnable> openListeners = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<Runnable> closeListeners = new CopyOnWriteArrayList<>();
        private volatile boolean closedByApp;

        Channel(String name, ChannelOptions options) {
            this.name = name;
            this.options = options;
        }

        /** A page connected: it replaces the one before it (a reload reconnects before the old socket is noticed gone). */
        boolean attach(ChannelConnection connection) {
            if (closedByApp) {
                return false;
            }
            ChannelConnection previous = active.getAndSet(connection);
            if (previous != null) {
                previous.close();
            }
            run(openListeners);
            return true;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isOpen() {
            return active.get() != null;
        }

        @Override
        public boolean send(ByteBuffer data) {
            ChannelConnection connection = active.get();
            if (connection == null) {
                return false;
            }
            byte[] copy = new byte[data.remaining()];
            data.duplicate().get(copy);
            return connection.send(copy);
        }

        @Override
        public void onMessage(Consumer<ByteBuffer> listener) {
            messageListeners.add(listener);
        }

        @Override
        public void onOpen(Runnable listener) {
            openListeners.add(listener);
        }

        @Override
        public void onClose(Runnable listener) {
            closeListeners.add(listener);
        }

        @Override
        public void close() {
            closedByApp = true;
            channels.remove(name, this);
            ChannelConnection connection = active.get();
            if (connection != null) {
                connection.close(); // its end is reported through onEnd, which tells the close listeners
            }
        }

        @Override
        public void onMessage(byte[] message) {
            for (Consumer<ByteBuffer> listener : messageListeners) {
                try {
                    listener.accept(ByteBuffer.wrap(message));
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "an onMessage listener of channel " + name + " failed", e);
                }
            }
        }

        @Override
        public void onEnd(ChannelConnection connection) {
            if (active.compareAndSet(connection, null)) { // not when a newer connection has already replaced it
                run(closeListeners);
            }
        }

        private void run(List<Runnable> listeners) {
            for (Runnable listener : listeners) {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "a listener of channel " + name + " failed", e);
                }
            }
        }
    }
}
