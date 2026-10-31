package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.BinaryChannel;
import io.github.b077as.jolt.ChannelOptions;
import io.github.b077as.jolt.OpenWindowRequest;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOpener;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The Java half of {@code jolt.openWindow(path, options)}, {@code jolt.broadcast(text)} and {@code jolt.onBroadcast(cb)}. The
 * page talks over a {@link BinaryChannel} (so the window's {@code SecurityPolicy} decides which pages may) with small JSON
 * messages:
 *
 * <pre>
 * page to Java   {"id":1,"op":"open","path":"/m/1","key":"k","title":"t","width":900,"height":700}
 *                {"op":"broadcast","text":"..."}
 * Java to page   {"id":1,"status":1}        1 opened, 2 an existing window with that key was brought forward,
 *                                           0 refused by the app, 3 rejected (invalid request or too many)
 *                {"op":"broadcast","text":"..."}
 * </pre>
 * The page never creates a window: the app's {@link WindowOpener} does, on the UI thread, and may refuse. All windows of one
 * {@link Application} that enabled this form a group, which is what keys and broadcasts are scoped to.
 */
public final class PageWindows {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.windows");

    public static final String CHANNEL = "jolt.windows";
    static final int MAX_REQUESTS = 10;
    static final long WINDOW_MILLIS = 10_000;
    static final int MAX_MESSAGE_BYTES = 64 * 1024;
    static final int MAX_TEXT_CHARS = 16 * 1024;
    static final int MAX_KEY_CHARS = 128;
    static final int MAX_TITLE_CHARS = 256;
    static final int MIN_SIZE = 200;
    static final int MAX_SIZE = 4000;

    private static final Map<Application, Group> GROUPS = new IdentityHashMap<>();

    /** What the windows of one application share: who has this enabled, and which keys are taken. */
    private static final class Group {
        final List<PageWindows> members = new ArrayList<>();
        final Map<String, WebWindow> byKey = new LinkedHashMap<>();
    }

    private final WebWindow window;
    private final Application app;
    private final WindowOpener opener;
    private final Group group;
    private final BinaryChannel channel;
    private final LongSupplier clock;
    private final long[] recent = new long[MAX_REQUESTS];
    private int next;

    PageWindows(WebWindow window, Application app, WindowOpener opener, Group group, BinaryChannel channel, LongSupplier clock) {
        this.window = window;
        this.app = app;
        this.opener = opener;
        this.group = group;
        this.channel = channel;
        this.clock = clock;
        java.util.Arrays.fill(recent, Long.MIN_VALUE / 2);
        channel.onMessage(this::handle);
    }

    /**
     * Lets the pages of {@code window} use {@code jolt.openWindow} and {@code jolt.broadcast}. Does nothing if it is already
     * enabled (the first call's {@code opener} stays).
     */
    public static void enable(WebWindow window, Application app, WindowOpener opener) {
        java.util.Objects.requireNonNull(window, "window");
        java.util.Objects.requireNonNull(app, "app");
        PageWindows created;
        synchronized (GROUPS) {
            Group group = GROUPS.computeIfAbsent(app, a -> new Group());
            for (PageWindows member : group.members) {
                if (member.window == window) {
                    return;
                }
            }
            BinaryChannel channel = ChannelServers.of(window).open(CHANNEL,
                    ChannelOptions.defaults().withMaxMessageBytes(MAX_MESSAGE_BYTES));
            created = new PageWindows(window, app, opener, group, channel, System::currentTimeMillis);
            group.members.add(created);
            window.onClosed(() -> {
                synchronized (GROUPS) {
                    group.members.remove(created);
                    group.byKey.values().removeIf(w -> w == window);
                    if (group.members.isEmpty() && group.byKey.isEmpty()) {
                        GROUPS.remove(app, group);
                    }
                }
            });
        }
        String script = WindowsScript.build();
        window.addInitScript(script);
        window.eval(script).exceptionally(t -> null);
    }

    /** Allows one more request now, or says the page is over its budget. */
    synchronized boolean admit() {
        long now = clock.getAsLong();
        if (now - recent[next] < WINDOW_MILLIS) {
            return false;
        }
        recent[next] = now;
        next = (next + 1) % MAX_REQUESTS;
        return true;
    }

    void handle(ByteBuffer message) {
        long id = -1;
        try {
            Map<String, String> m = MiniJson.members(decode(message));
            String op = text(m, "op");
            if ("broadcast".equals(op)) {
                broadcast(text(m, "text"));
                return;
            }
            if (!"open".equals(op)) {
                return;
            }
            id = Long.parseLong(m.get("id"));
            long requestId = id;
            if (!admit()) {
                reply(requestId, 3);
                return;
            }
            String path = text(m, "path");
            if (!AppPaths.isAppPath(path)) {
                reply(requestId, 3);
                return;
            }
            String key = optionalText(m, "key", MAX_KEY_CHARS);
            String title = optionalText(m, "title", MAX_TITLE_CHARS);
            int width = size(m, "width");
            int height = size(m, "height");
            OpenWindowRequest request = new OpenWindowRequest(window, path, key, title, width, height, w -> enable(w, app, opener));
            app.dispatch(() -> reply(requestId, open(request)));
        } catch (RuntimeException e) { // malformed message
            if (id >= 0) {
                reply(id, 3);
            }
        }
    }

    /** UI thread. */
    private int open(OpenWindowRequest request) {
        if (opener == null) {
            return 0;
        }
        String key = request.key();
        if (key != null) {
            WebWindow existing;
            synchronized (GROUPS) {
                existing = group.byKey.get(key);
            }
            if (existing != null) {
                existing.focus();
                return 2;
            }
        }
        WebWindow opened;
        try {
            opened = opener.open(request);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "the window opener failed; the request is refused", e);
            return 0;
        }
        if (opened == null) {
            return 0;
        }
        if (key != null) {
            synchronized (GROUPS) {
                group.byKey.put(key, opened);
            }
            opened.onClosed(() -> {
                synchronized (GROUPS) {
                    group.byKey.remove(key, opened);
                }
            });
        }
        enable(opened, app, opener); // does nothing if the opener enabled it already
        return 1;
    }

    private void broadcast(String text) {
        List<PageWindows> others;
        synchronized (GROUPS) {
            others = new ArrayList<>(group.members);
        }
        byte[] frame = ("{\"op\":\"broadcast\",\"text\":" + JsLiteral.quote(text) + "}").getBytes(StandardCharsets.UTF_8);
        for (PageWindows other : others) {
            if (other != this) {
                other.channel.send(frame);
            }
        }
    }

    private void reply(long id, int status) {
        channel.send(("{\"id\":" + id + ",\"status\":" + status + "}").getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(ByteBuffer message) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(message).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("not UTF-8");
        }
    }

    private static String text(Map<String, String> members, String name) {
        String raw = members.get(name);
        if (raw == null || !raw.startsWith("\"")) {
            throw new IllegalArgumentException("missing " + name);
        }
        String value = MiniJson.decodeString(raw);
        if (value.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return value;
    }

    /** A string member that may be missing or {@code null}; too long is an error. */
    private static String optionalText(Map<String, String> members, String name, int maxChars) {
        String raw = members.get(name);
        if (raw == null || raw.equals("null")) {
            return null;
        }
        String value = text(members, name);
        if (value.length() > maxChars) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return value.isEmpty() ? null : value;
    }

    /** A size the page suggested: 0 when absent, otherwise limited to a range a window can reasonably have. */
    private static int size(Map<String, String> members, String name) {
        String raw = members.get(name);
        if (raw == null || raw.equals("null")) {
            return 0;
        }
        double value = Double.parseDouble(raw);
        if (Double.isNaN(value) || value <= 0) {
            return 0;
        }
        return (int) Math.max(MIN_SIZE, Math.min(MAX_SIZE, Math.round(value)));
    }
}
