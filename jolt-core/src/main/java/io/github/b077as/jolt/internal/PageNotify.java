package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.BinaryChannel;
import io.github.b077as.jolt.ChannelOptions;
import io.github.b077as.jolt.Notification;
import io.github.b077as.jolt.WebWindow;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The Java half of {@code jolt.notify(title, body)}. The page asks over a {@link BinaryChannel}, so the window's
 * {@code SecurityPolicy} decides which pages may; a page cannot raise more than {@value #MAX_PER_WINDOW} notifications per
 * {@value #WINDOW_MILLIS} ms.
 *
 * <pre>
 * page to Java   u32 id, string title, string body,          (a string is a u32 byte length and UTF-8)
 *                u8 wantsClick, u8 button count, per button: string label,
 *                u8 hasReply, [string label, string placeholder]
 * Java to page   u32 id, u8 status: 1 shown, 0 refused by the system, 2 rejected (invalid or too many), 3 failed
 *                u32 id, u8 4 (event), u8 kind (0 click, 1 button, 2 reply), u32 index, string text
 * </pre>
 * Only the choices travel to Java; the page keeps its own callbacks and runs the one an event names.
 */
public final class PageNotify {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.notify");

    public static final String CHANNEL = "jolt.notify";
    static final int MAX_PER_WINDOW = 5;
    static final long WINDOW_MILLIS = 10_000;
    /** A reply longer than this is cut: it came from a notification field, not from a document. */
    static final int MAX_REPLY_CHARS = 4096;

    private static final Map<WebWindow, PageNotify> ENABLED = new IdentityHashMap<>();

    private final BinaryChannel channel;
    private final Application app;
    private final LongSupplier clock;
    private final long[] recent = new long[MAX_PER_WINDOW];
    private int next;

    PageNotify(BinaryChannel channel, Application app, LongSupplier clock) {
        this.channel = channel;
        this.app = app;
        this.clock = clock;
        java.util.Arrays.fill(recent, Long.MIN_VALUE / 2);
        channel.onMessage(this::handle);
    }

    /** Lets the pages of {@code window} call {@code jolt.notify}; notifications go out through {@code app}. */
    public static void enable(WebWindow window, Application app) {
        java.util.Objects.requireNonNull(app, "app");
        synchronized (ENABLED) {
            if (ENABLED.containsKey(window)) {
                return;
            }
            BinaryChannel channel = ChannelServers.of(window).open(CHANNEL,
                    ChannelOptions.defaults().withMaxMessageBytes(64 * 1024));
            ENABLED.put(window, new PageNotify(channel, app, System::currentTimeMillis));
            window.onClosed(() -> {
                synchronized (ENABLED) {
                    ENABLED.remove(window);
                }
            });
            String script = NotifyScript.build();
            window.addInitScript(script);
            window.eval(script).exceptionally(t -> null);
        }
    }

    /** Allows one more notification now, or says the page is over its budget. */
    synchronized boolean admit() {
        long now = clock.getAsLong();
        if (now - recent[next] < WINDOW_MILLIS) {
            return false; // the oldest of the last MAX_PER_WINDOW is still inside the window
        }
        recent[next] = now;
        next = (next + 1) % MAX_PER_WINDOW;
        return true;
    }

    void handle(ByteBuffer message) {
        int id = -1;
        try {
            id = message.getInt();
            String title = string(message);
            String body = string(message);
            Notification notification = new Notification(title, body);
            if (message.hasRemaining()) {
                notification = withActions(notification, id, message);
            }
            if (!admit()) {
                reply(id, 2);
                return;
            }
            int replyId = id;
            app.notify(notification).whenComplete((shown, error) -> {
                if (error != null) {
                    LOG.log(System.Logger.Level.WARNING, "a page notification failed", error);
                    reply(replyId, 3);
                } else {
                    reply(replyId, shown ? 1 : 0);
                }
            });
        } catch (RuntimeException e) { // malformed message, invalid Notification, or no notification support
            if (id >= 0) {
                reply(id, 2);
            }
        }
    }

    /** Adds the click, buttons and reply the page asked for; each reports back to the page as an event. */
    private Notification withActions(Notification notification, int id, ByteBuffer m) {
        if ((m.get() & 1) != 0) {
            notification = notification.withOnClick(() -> event(id, 0, 0, ""));
        }
        int buttons = m.get() & 0xFF;
        if (buttons > Notification.MAX_BUTTONS) {
            throw new IllegalArgumentException("too many buttons");
        }
        for (int i = 0; i < buttons; i++) {
            int index = i;
            notification = notification.withButton(string(m), () -> event(id, 1, index, ""));
        }
        if (m.get() != 0) {
            String label = string(m);
            String placeholder = string(m);
            notification = notification.withReply(label, placeholder, text -> event(id, 2, 0, text));
        }
        return notification;
    }

    private void event(int id, int kind, int index, String text) {
        byte[] bytes = text.length() > MAX_REPLY_CHARS ? text.substring(0, MAX_REPLY_CHARS).getBytes(StandardCharsets.UTF_8)
                : text.getBytes(StandardCharsets.UTF_8);
        channel.send(ByteBuffer.allocate(10 + 4 + bytes.length).putInt(id).put((byte) 4).put((byte) kind).putInt(index)
                .putInt(bytes.length).put(bytes).flip());
    }

    private void reply(int id, int status) {
        channel.send(ByteBuffer.allocate(5).putInt(id).put((byte) status).flip());
    }

    private static String string(ByteBuffer m) {
        int length = m.getInt();
        if (length < 0 || length > m.remaining() || length > 8192) {
            throw new IllegalArgumentException("bad string length");
        }
        byte[] bytes = new byte[length];
        m.get(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("not UTF-8");
        }
    }
}
