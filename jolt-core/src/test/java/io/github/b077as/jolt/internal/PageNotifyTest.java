package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.BinaryChannel;
import io.github.b077as.jolt.Notification;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageNotifyTest {

    private final List<Notification> shown = new ArrayList<>();
    private final List<byte[]> replies = new ArrayList<>();
    private Consumer<ByteBuffer> listener;
    private long now;
    private CompletableFuture<Boolean> result = CompletableFuture.completedFuture(true);

    private PageNotify create() {
        Application app = (Application) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Application.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("notify")) {
                        shown.add((Notification) args[0]);
                        return result;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        BinaryChannel channel = (BinaryChannel) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{BinaryChannel.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "onMessage" -> listener = castListener(args[0]);
                        case "send" -> {
                            ByteBuffer b = (ByteBuffer) args[0];
                            byte[] copy = new byte[b.remaining()];
                            b.get(copy);
                            replies.add(copy);
                            return true;
                        }
                        default -> { }
                    }
                    return null;
                });
        return new PageNotify(channel, app, () -> now);
    }

    @SuppressWarnings("unchecked")
    private static Consumer<ByteBuffer> castListener(Object o) {
        return (Consumer<ByteBuffer>) o;
    }

    private static ByteBuffer request(int id, String title, String body) {
        byte[] t = title.getBytes(StandardCharsets.UTF_8);
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(12 + t.length + b.length).putInt(id).putInt(t.length).put(t).putInt(b.length).put(b).flip();
    }

    private static void putString(java.io.ByteArrayOutputStream out, String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(ByteBuffer.allocate(4).putInt(b.length).array());
        out.writeBytes(b);
    }

    /** A request with actions: a click, two buttons and a reply field. */
    private static ByteBuffer requestWithActions(int id) {
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(4).putInt(id).array());
        putString(out, "Title");
        putString(out, "Body");
        out.write(1);          // wants a click
        out.write(2);          // two buttons
        putString(out, "Open");
        putString(out, "Later");
        out.write(1);          // a reply field
        putString(out, "Send");
        putString(out, "Say something");
        return ByteBuffer.wrap(out.toByteArray());
    }

    private int statusOf(int index) {
        return replies.get(index)[4];
    }

    @Test
    void aRequestShowsANotificationAndRepliesShown() {
        create();
        listener.accept(request(7, "Done", "all good ü"));
        assertEquals(List.of(new Notification("Done", "all good ü")), shown);
        assertEquals(7, ByteBuffer.wrap(replies.get(0)).getInt());
        assertEquals(1, statusOf(0));
    }

    @Test
    void aSystemThatDeclinesIsReportedAsNotShown() {
        result = CompletableFuture.completedFuture(false);
        create();
        listener.accept(request(1, "x", ""));
        assertEquals(0, statusOf(0));
    }

    @Test
    void aFailureIsReportedWithoutLeakingItsCause() {
        result = CompletableFuture.failedFuture(new IllegalStateException("secret detail"));
        create();
        listener.accept(request(1, "x", ""));
        assertEquals(3, statusOf(0));
        assertEquals(5, replies.get(0).length);
    }

    @Test
    void aPageIsLimitedToFiveNotificationsPerTenSeconds() {
        create();
        for (int i = 0; i < 5; i++) {
            now = i * 100;
            listener.accept(request(i, "n" + i, ""));
        }
        now = 600;
        listener.accept(request(5, "too many", ""));
        assertEquals(5, shown.size());
        assertEquals(2, statusOf(5));

        now = 10_000; // the first one has left the window
        listener.accept(request(6, "again", ""));
        assertEquals(6, shown.size());
        assertEquals(1, statusOf(6));
    }

    @Test
    void invalidAndMalformedRequestsAreRejectedAndDoNotUseTheBudget() {
        PageNotify notify = create();
        listener.accept(request(1, "   ", "blank title"));
        assertEquals(2, statusOf(0));
        listener.accept(request(2, "t".repeat(Notification.MAX_TITLE + 1), ""));
        assertEquals(2, statusOf(1));
        listener.accept(ByteBuffer.wrap(new byte[]{0, 0, 0, 3, 0, 0, 0, 99, 1})); // string longer than the message
        assertEquals(2, statusOf(2));
        assertTrue(shown.isEmpty());
        assertTrue(notify.admit());
    }

    @Test
    void aTruncatedMessageWithoutAnIdIsIgnored() {
        create();
        listener.accept(ByteBuffer.wrap(new byte[]{1, 2}));
        assertTrue(replies.isEmpty());
    }

    @Test
    void theActionsOfARequestBecomeANotificationWhoseUseIsReportedBackAsEvents() {
        create();
        listener.accept(requestWithActions(9));
        Notification n = shown.get(0);
        assertEquals(List.of("Open", "Later"), n.buttons().stream().map(Notification.Button::label).toList());
        assertEquals("Send", n.reply().label());
        assertEquals("Say something", n.reply().placeholder());
        assertEquals(1, statusOf(0)); // shown

        n.onClick().run();
        n.buttons().get(1).action().run();
        n.reply().onReply().accept("héllo");

        ByteBuffer click = ByteBuffer.wrap(replies.get(1));
        assertEquals(9, click.getInt());
        assertEquals(4, click.get());
        assertEquals(0, click.get()); // kind: click
        ByteBuffer button = ByteBuffer.wrap(replies.get(2));
        button.position(5);
        assertEquals(1, button.get()); // kind: button
        assertEquals(1, button.getInt()); // the second one
        ByteBuffer reply = ByteBuffer.wrap(replies.get(3));
        reply.position(5);
        assertEquals(2, reply.get());
        reply.getInt();
        byte[] text = new byte[reply.getInt()];
        reply.get(text);
        assertEquals("héllo", new String(text, StandardCharsets.UTF_8));
    }

    @Test
    void tooManyButtonsOrATruncatedActionListIsRejected() {
        create();
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(4).putInt(3).array());
        putString(out, "t");
        putString(out, "b");
        out.write(0);
        out.write(6); // more than the limit
        listener.accept(ByteBuffer.wrap(out.toByteArray()));
        assertEquals(2, statusOf(0));
        assertTrue(shown.isEmpty());
    }

    @Test
    void theNotificationRecordValidates() {
        assertThrows(NullPointerException.class, () -> new Notification(null, ""));
        assertThrows(IllegalArgumentException.class, () -> new Notification(" ", ""));
        assertFalse(new Notification("t", null).body() == null);
    }
}
