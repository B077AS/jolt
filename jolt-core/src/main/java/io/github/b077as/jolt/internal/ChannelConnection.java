package io.github.b077as.jolt.internal;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayDeque;

/**
 * One WebSocket (RFC 6455) connection of a {@link ChannelServer}, after the handshake: reads the page's binary messages and
 * writes Java's, with a queue so that a slow page never blocks the thread that sends.
 */
final class ChannelConnection {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.channel");

    private static final int OP_CONTINUATION = 0x0, OP_TEXT = 0x1, OP_BINARY = 0x2, OP_CLOSE = 0x8, OP_PING = 0x9, OP_PONG = 0xA;
    private static final int CLOSE_NORMAL = 1000, CLOSE_GOING_AWAY = 1001, CLOSE_PROTOCOL = 1002, CLOSE_UNSUPPORTED = 1003,
            CLOSE_TOO_BIG = 1009;

    /** What the connection reports to the channel that owns it. */
    interface Listener {
        void onMessage(byte[] message);

        void onEnd(ChannelConnection connection);
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final Listener listener;
    private final boolean latestOnly;
    private final long maxQueuedBytes;
    private final int maxMessageBytes;

    private final Object writeLock = new Object();
    private final Object queueLock = new Object();
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private long queuedBytes;
    private boolean closing;
    private volatile boolean ended;

    ChannelConnection(Socket socket, InputStream in, Listener listener, boolean latestOnly, long maxQueuedBytes, int maxMessageBytes)
            throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(in);
        this.out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
        this.listener = listener;
        this.latestOnly = latestOnly;
        this.maxQueuedBytes = maxQueuedBytes;
        this.maxMessageBytes = maxMessageBytes;
    }

    /** Runs the connection until it ends: a writer thread is started and this thread reads. */
    void run() {
        Thread.ofVirtual().name("jolt-channel-writer").start(this::writeLoop);
        try {
            readLoop();
        } catch (IOException e) {
            // the page went away, or the socket was closed from our side
        } finally {
            finish();
        }
    }

    /** Queues a message for the page. Returns {@code false} if the connection is closed or has too much waiting. */
    boolean send(byte[] message) {
        synchronized (queueLock) {
            if (closing || ended) {
                return false;
            }
            if (latestOnly) {
                queue.clear();
                queuedBytes = 0;
            } else if (queuedBytes > 0 && queuedBytes + message.length > maxQueuedBytes) {
                return false;
            }
            queue.add(message);
            queuedBytes += message.length;
            queueLock.notifyAll();
            return true;
        }
    }

    /** Sends a close frame and ends the connection; messages still waiting in the queue are dropped. */
    void close(int code) {
        synchronized (queueLock) {
            if (closing) {
                return;
            }
            closing = true;
            queueLock.notifyAll();
        }
        try {
            writeFrame(OP_CLOSE, new byte[] {(byte) (code >> 8), (byte) code});
        } catch (IOException e) {
            // already gone
        }
        finish();
    }

    void close() {
        close(CLOSE_GOING_AWAY);
    }

    private void finish() {
        if (ended) {
            return;
        }
        ended = true;
        synchronized (queueLock) {
            closing = true;
            queue.clear();
            queuedBytes = 0;
            queueLock.notifyAll();
        }
        try {
            socket.close();
        } catch (IOException e) {
            // nothing more to do
        }
        listener.onEnd(this);
    }

    // ---- writing ----

    private void writeLoop() {
        try {
            while (true) {
                byte[] next;
                synchronized (queueLock) {
                    while (queue.isEmpty()) {
                        if (closing || ended) {
                            return;
                        }
                        queueLock.wait();
                    }
                    next = queue.poll();
                    queuedBytes -= next.length;
                }
                writeFrame(OP_BINARY, next);
            }
        } catch (IOException | InterruptedException e) {
            finish();
        }
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        byte[] header = new byte[10];
        int n = 0;
        header[n++] = (byte) (0x80 | opcode); // FIN, no extensions
        if (payload.length < 126) {
            header[n++] = (byte) payload.length;
        } else if (payload.length <= 0xFFFF) {
            header[n++] = 126;
            header[n++] = (byte) (payload.length >> 8);
            header[n++] = (byte) payload.length;
        } else {
            header[n++] = 127;
            long length = payload.length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                header[n++] = (byte) (length >> shift);
            }
        }
        synchronized (writeLock) {
            out.write(header, 0, n);
            out.write(payload);
            out.flush();
        }
    }

    // ---- reading ----

    private void readLoop() throws IOException {
        ByteArrayOutputStream fragments = null; // the message being assembled from several frames
        while (!ended) {
            int first = in.read();
            if (first < 0) {
                return;
            }
            boolean fin = (first & 0x80) != 0;
            int opcode = first & 0x0F;
            if ((first & 0x70) != 0) {
                close(CLOSE_PROTOCOL); // reserved bits mean an extension we never agreed to
                return;
            }
            int second = in.readUnsignedByte();
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7F;
            if (length == 126) {
                length = in.readUnsignedShort();
            } else if (length == 127) {
                length = in.readLong();
            }
            boolean control = (opcode & 0x8) != 0;
            if (!masked || length < 0 || (control && (length > 125 || !fin))) {
                close(CLOSE_PROTOCOL); // a browser always masks; control frames are short and never fragmented
                return;
            }
            if (length > maxMessageBytes) {
                close(CLOSE_TOO_BIG);
                return;
            }
            byte[] mask = new byte[4];
            in.readFully(mask);
            byte[] payload = new byte[(int) length];
            in.readFully(payload);
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }

            switch (opcode) {
                case OP_PING -> writeFrame(OP_PONG, payload);
                case OP_PONG -> { }
                case OP_CLOSE -> {
                    close(CLOSE_NORMAL);
                    return;
                }
                case OP_TEXT, OP_BINARY, OP_CONTINUATION -> {
                    if (opcode == OP_CONTINUATION ? fragments == null : fragments != null) {
                        close(CLOSE_PROTOCOL);
                        return;
                    }
                    if (opcode == OP_TEXT) {
                        close(CLOSE_UNSUPPORTED); // a channel carries bytes; the page-side script encodes strings
                        return;
                    }
                    if (fin && fragments == null) {
                        deliver(payload);
                    } else {
                        if (fragments == null) {
                            fragments = new ByteArrayOutputStream(payload.length * 2);
                        }
                        if ((long) fragments.size() + payload.length > maxMessageBytes) {
                            close(CLOSE_TOO_BIG);
                            return;
                        }
                        fragments.write(payload);
                        if (fin) {
                            byte[] whole = fragments.toByteArray();
                            fragments = null;
                            deliver(whole);
                        }
                    }
                }
                default -> {
                    close(CLOSE_PROTOCOL);
                    return;
                }
            }
        }
    }

    private void deliver(byte[] message) {
        try {
            listener.onMessage(message);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "a channel listener failed", e);
        }
    }

}
