package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.BinaryChannel;
import io.github.b077as.jolt.ChannelOptions;
import io.github.b077as.jolt.SecurityPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The channel server driven by a plain WebSocket client that behaves like the page script (it sends an Origin and the secret). */
class ChannelServerTest {

    private static final String APP = SecurityPolicy.APP_ORIGIN;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private ChannelServer server;

    @BeforeEach
    void start() throws Exception {
        server = new ChannelServer(SecurityPolicy::standard);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    /** Collects what a client socket receives. */
    private static final class Client implements WebSocket.Listener {
        final BlockingQueue<byte[]> messages = new LinkedBlockingQueue<>();
        final CountDownLatch closed = new CountDownLatch(1);
        private final ByteArrayOutputStream partial = new ByteArrayOutputStream();

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            partial.writeBytes(chunk);
            if (last) {
                messages.add(partial.toByteArray());
                partial.reset();
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            closed.countDown();
        }
    }

    private WebSocket connect(String origin, String path, String token, Client client) {
        WebSocket.Builder builder = CLIENT.newWebSocketBuilder();
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return builder.buildAsync(URI.create("ws://127.0.0.1:" + server.port() + path + "?t=" + token), client).join();
    }

    private WebSocket connect(String name, Client client) {
        return connect(APP, "/c/" + name, server.token(), client);
    }

    private static byte[] bytes(int length, long seed) {
        byte[] data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    @Test
    void bytesTravelBothWaysUnchangedAtEverySizeOfFrame() throws Exception {
        BinaryChannel channel = server.open("echo", ChannelOptions.defaults());
        channel.onMessage(data -> {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            channel.send(copy);
        });
        Client client = new Client();
        WebSocket socket = connect("echo", client);
        client.messages.clear();
        // 0 bytes, the 7-bit, 16-bit and 64-bit length forms, and everything a byte can be
        for (int size : new int[] {0, 1, 125, 126, 300, 65_535, 65_536, 2_000_000}) {
            byte[] sent = bytes(size, size);
            socket.sendBinary(ByteBuffer.wrap(sent), true).join();
            byte[] received = client.messages.poll(10, TimeUnit.SECONDS);
            assertNotNull(received, "no echo for " + size + " bytes");
            assertArrayEquals(sent, received, "echo of " + size + " bytes");
        }
    }

    @Test
    void aMessageSentInSeveralFramesIsDeliveredWhole() throws Exception {
        BinaryChannel channel = server.open("parts", ChannelOptions.defaults());
        BlockingQueue<byte[]> got = new LinkedBlockingQueue<>();
        channel.onMessage(data -> {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            got.add(copy);
        });
        WebSocket socket = connect("parts", new Client());
        byte[] all = bytes(3000, 7);
        socket.sendBinary(ByteBuffer.wrap(all, 0, 1000), false).join();
        socket.sendBinary(ByteBuffer.wrap(all, 1000, 1000), false).join();
        socket.sendBinary(ByteBuffer.wrap(all, 2000, 1000), true).join();
        assertArrayEquals(all, got.poll(10, TimeUnit.SECONDS));
    }

    @Test
    void sendCopiesTheBufferAndLeavesItsPositionAlone() throws Exception {
        BinaryChannel channel = server.open("copy", ChannelOptions.defaults());
        Client client = new Client();
        connect("copy", client);
        assertTrue(await(channel::isOpen));
        byte[] source = {1, 2, 3, 4, 5, 6};
        ByteBuffer buffer = ByteBuffer.wrap(source, 2, 3);
        assertTrue(channel.send(buffer));
        Arrays.fill(source, (byte) 9); // the caller reuses its buffer at once
        assertArrayEquals(new byte[] {3, 4, 5}, client.messages.poll(10, TimeUnit.SECONDS));
        assertEquals(2, buffer.position());
    }

    @Test
    void sendSaysNoWhenNobodyIsConnectedAndOpenAndCloseAreReported() throws Exception {
        BinaryChannel channel = server.open("life", ChannelOptions.defaults());
        AtomicBoolean opened = new AtomicBoolean();
        CountDownLatch closed = new CountDownLatch(1);
        channel.onOpen(() -> opened.set(true));
        channel.onClose(closed::countDown);
        assertFalse(channel.isOpen());
        assertFalse(channel.send(new byte[] {1}));
        WebSocket socket = connect("life", new Client());
        assertTrue(await(opened::get));
        assertTrue(channel.isOpen());
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        assertTrue(closed.await(10, TimeUnit.SECONDS));
        assertFalse(channel.isOpen());
        assertFalse(channel.send(new byte[] {1}));
    }

    @Test
    void aReconnectingPageReplacesTheOldConnection() throws Exception {
        BinaryChannel channel = server.open("reload", ChannelOptions.defaults());
        Client first = new Client();
        connect("reload", first);
        assertTrue(await(channel::isOpen));
        Client second = new Client();
        connect("reload", second);
        assertTrue(first.closed.await(10, TimeUnit.SECONDS));
        assertTrue(await(channel::isOpen));
        assertTrue(channel.send(new byte[] {42}));
        assertArrayEquals(new byte[] {42}, second.messages.poll(10, TimeUnit.SECONDS));
    }

    @Test
    void closingTheChannelDisconnectsThePageAndFreesTheName() throws Exception {
        BinaryChannel channel = server.open("gone", ChannelOptions.defaults());
        Client client = new Client();
        connect("gone", client);
        assertTrue(await(channel::isOpen));
        channel.close();
        assertTrue(client.closed.await(10, TimeUnit.SECONDS));
        assertThrows(CompletionException.class, () -> connect("gone", new Client())); // 404 until reopened
        server.open("gone", ChannelOptions.defaults()); // the name is free again
    }

    @Test
    void aNameCanOnlyBeOpenedOnceAndMustBeValid() {
        server.open("same", ChannelOptions.defaults());
        assertThrows(IllegalStateException.class, () -> server.open("same", ChannelOptions.defaults()));
        assertThrows(IllegalArgumentException.class, () -> server.open("", ChannelOptions.defaults()));
        assertThrows(IllegalArgumentException.class, () -> server.open("has space", ChannelOptions.defaults()));
        assertThrows(IllegalArgumentException.class, () -> server.open("a/b", ChannelOptions.defaults()));
    }

    @Test
    void connectionsAreRefusedWithoutTheSecretOrFromAnUntrustedOrigin() {
        server.open("secure", ChannelOptions.defaults());
        assertThrows(CompletionException.class, () -> connect(APP, "/c/secure", "wrong", new Client()));
        assertThrows(CompletionException.class, () -> connect("https://evil.example", "/c/secure", server.token(), new Client()));
        assertThrows(CompletionException.class, () -> connect("null", "/c/secure", server.token(), new Client()));
        assertThrows(CompletionException.class, () -> connect(null, "/c/secure", server.token(), new Client())); // no Origin at all
        assertThrows(CompletionException.class, () -> connect(APP, "/c/missing", server.token(), new Client()));
    }

    @Test
    void anOriginIsTrustedWhenThePolicyAtThatMomentSaysSo() throws Exception {
        SecurityPolicy[] current = {SecurityPolicy.standard()};
        server.close();
        server = new ChannelServer(() -> current[0]);
        server.open("moving", ChannelOptions.defaults());
        assertThrows(CompletionException.class, () -> connect("http://localhost:5173", "/c/moving", server.token(), new Client()));
        current[0] = SecurityPolicy.standard().withTrustedOrigins("http://localhost:5173");
        connect("http://localhost:5173", "/c/moving", server.token(), new Client());
    }

    @Test
    void aMessageOverTheLimitClosesTheConnection() throws Exception {
        BinaryChannel channel = server.open("small", ChannelOptions.defaults().withMaxMessageBytes(1000));
        BlockingQueue<byte[]> got = new LinkedBlockingQueue<>();
        channel.onMessage(data -> got.add(new byte[data.remaining()]));
        Client client = new Client();
        WebSocket socket = connect("small", client);
        socket.sendBinary(ByteBuffer.wrap(bytes(1000, 1)), true).join();
        assertNotNull(got.poll(10, TimeUnit.SECONDS));
        socket.sendBinary(ByteBuffer.wrap(bytes(1001, 2)), true).join();
        assertTrue(client.closed.await(10, TimeUnit.SECONDS));
        assertNull(got.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void aReliableChannelRefusesWhatDoesNotFitAndALatestOnlyOneKeepsTheNewest() throws Exception {
        // The client never asks for data, so nothing is read from the socket once the buffers fill and the queue backs up.
        BinaryChannel reliable = server.open("reliable", ChannelOptions.defaults().withMaxQueuedBytes(1_000_000));
        BinaryChannel newest = server.open("newest", ChannelOptions.newestOnly());
        WebSocket slow1 = CLIENT.newWebSocketBuilder().header("Origin", APP)
                .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/c/reliable?t=" + server.token()), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        // no request(n): the client reads nothing
                    }
                }).join();
        WebSocket slow2 = CLIENT.newWebSocketBuilder().header("Origin", APP)
                .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/c/newest?t=" + server.token()), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                    }
                }).join();
        assertTrue(await(() -> reliable.isOpen() && newest.isOpen()));
        byte[] chunk = new byte[500_000];
        boolean refused = false;
        for (int i = 0; i < 200 && !refused; i++) {
            refused = !reliable.send(chunk);
        }
        assertTrue(refused, "a reliable channel must say no once its queue is full");
        for (int i = 0; i < 200; i++) {
            assertTrue(newest.send(chunk), "a latest-only channel always takes the newest message");
        }
        slow1.abort();
        slow2.abort();
    }
}
