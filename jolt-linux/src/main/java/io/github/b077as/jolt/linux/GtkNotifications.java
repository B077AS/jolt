package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.Notification;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Desktop notifications over the session bus: {@code org.freedesktop.Notifications.Notify}, plus the
 * {@code ActionInvoked} and {@code NotificationClosed} signals for clicks and buttons. Kept apart from {@link Gtk} so an
 * old system without these GIO calls loses only notifications.
 *
 * <p>The specification has no text input, so {@link Notification#reply()} is left out here. The default action
 * ({@code "default"}) is what most daemons run when the notification itself is clicked.
 */
final class GtkNotifications {

    private static final Arena LIBS = Arena.global();
    private static final SymbolLookup GLIB = SymbolLookup.libraryLookup("libglib-2.0.so.0", LIBS);
    private static final SymbolLookup GIO = SymbolLookup.libraryLookup("libgio-2.0.so.0", LIBS);

    private static final MethodHandle G_BUS_GET_SYNC = Native.downcall(GIO, "g_bus_get_sync",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle G_DBUS_CONNECTION_CALL_SYNC = Native.downcall(GIO, "g_dbus_connection_call_sync",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT,
                    ADDRESS, ADDRESS));
    /** {@code guint g_dbus_connection_signal_subscribe(conn, sender, interface, member, object_path, arg0, flags, callback, data, free)}. */
    private static final MethodHandle G_DBUS_SIGNAL_SUBSCRIBE = Native.downcall(GIO, "g_dbus_connection_signal_subscribe",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle G_DBUS_SIGNAL_UNSUBSCRIBE = Native.downcall(GIO, "g_dbus_connection_signal_unsubscribe",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle G_VARIANT_PARSE = Native.downcall(GLIB, "g_variant_parse",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle G_VARIANT_TYPE_NEW = Native.downcall(GLIB, "g_variant_type_new",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle G_VARIANT_TYPE_FREE = Native.downcall(GLIB, "g_variant_type_free",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle G_VARIANT_UNREF = Native.downcall(GLIB, "g_variant_unref", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle G_VARIANT_GET_CHILD_VALUE = Native.downcall(GLIB, "g_variant_get_child_value",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
    private static final MethodHandle G_VARIANT_GET_UINT32 = Native.downcall(GLIB, "g_variant_get_uint32",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle G_VARIANT_GET_STRING = Native.downcall(GLIB, "g_variant_get_string",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    static final String SERVICE = "org.freedesktop.Notifications";
    private static final String PATH = "/org/freedesktop/Notifications";
    private static final int G_BUS_TYPE_SESSION = 2;
    private static final String DEFAULT_ACTION = "default";

    private final LinuxApplication app;
    private final long appRegistryId;
    /** Notification id (from the daemon) to the actions of that notification, by action key. */
    private final Map<Integer, Map<String, Runnable>> actions = new ConcurrentHashMap<>();
    private MemorySegment bus = MemorySegment.NULL;
    private int clickSubscription;
    private int closeSubscription;

    GtkNotifications(LinuxApplication app, long appRegistryId) {
        this.app = app;
        this.appRegistryId = appRegistryId;
    }

    /**
     * Shows {@code n}. Returns {@code false} when there is no session bus or no notification service. Blocking (up to 3
     * seconds); safe from any thread because GDBus connections are thread-safe.
     */
    boolean send(String appName, Notification n) {
        Map<String, Runnable> mine = new HashMap<>();
        StringBuilder actionList = new StringBuilder();
        if (n.onClick() != null) {
            mine.put(DEFAULT_ACTION, n.onClick());
            actionList.append(quote(DEFAULT_ACTION)).append(", ").append(quote("")).append(", ");
        }
        for (int i = 0; i < n.buttons().size(); i++) {
            String key = "button" + i;
            mine.put(key, n.buttons().get(i).action());
            actionList.append(quote(key)).append(", ").append(quote(n.buttons().get(i).label())).append(", ");
        }
        if (actionList.length() > 0) {
            actionList.setLength(actionList.length() - 2);
        }
        // (app_name, replaces_id, app_icon, summary, body, actions, hints, expire_timeout)
        String text = "(" + quote(appName) + ", uint32 0, '', " + quote(n.title()) + ", " + quote(n.body()) + ", "
                + (mine.isEmpty() ? "@as []" : "[" + actionList + "]") + ", @a{sv} {}, -1)";

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(ADDRESS);
            MemorySegment connection = connection(error);
            if (connection.equals(MemorySegment.NULL)) {
                dropError(error);
                return false;
            }
            if (!mine.isEmpty()) {
                subscribe(connection);
            }
            MemorySegment parameters = parse("(susssasa{sv}i)", text, error);
            if (parameters.equals(MemorySegment.NULL)) {
                dropError(error);
                return false;
            }
            MemorySegment reply = (MemorySegment) call(G_DBUS_CONNECTION_CALL_SYNC, connection, utf8(arena, SERVICE),
                    utf8(arena, PATH), utf8(arena, SERVICE), utf8(arena, "Notify"), parameters, MemorySegment.NULL,
                    0 /* G_DBUS_CALL_FLAGS_NONE */, 3000, MemorySegment.NULL, error);
            if (reply.equals(MemorySegment.NULL)) {
                dropError(error);
                return false;
            }
            try {
                if (!mine.isEmpty()) {
                    MemorySegment id = (MemorySegment) call(G_VARIANT_GET_CHILD_VALUE, reply, 0L);
                    actions.put((int) call(G_VARIANT_GET_UINT32, id), mine);
                    call(G_VARIANT_UNREF, id);
                }
            } finally {
                call(G_VARIANT_UNREF, reply);
            }
            return true;
        }
    }

    private synchronized MemorySegment connection(MemorySegment error) {
        if (bus.equals(MemorySegment.NULL)) {
            bus = (MemorySegment) call(G_BUS_GET_SYNC, G_BUS_TYPE_SESSION, MemorySegment.NULL, error);
        }
        return bus;
    }

    /** Starts listening for button presses and closes, once. */
    private synchronized void subscribe(MemorySegment connection) {
        if (clickSubscription != 0) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = MemorySegment.ofAddress(appRegistryId);
            clickSubscription = (int) call(G_DBUS_SIGNAL_SUBSCRIBE, connection, utf8(arena, SERVICE), utf8(arena, SERVICE),
                    utf8(arena, "ActionInvoked"), utf8(arena, PATH), MemorySegment.NULL, 0, Callbacks.NOTIFY_SIGNAL, data,
                    MemorySegment.NULL);
            closeSubscription = (int) call(G_DBUS_SIGNAL_SUBSCRIBE, connection, utf8(arena, SERVICE), utf8(arena, SERVICE),
                    utf8(arena, "NotificationClosed"), utf8(arena, PATH), MemorySegment.NULL, 0, Callbacks.NOTIFY_SIGNAL, data,
                    MemorySegment.NULL);
        }
    }

    /** A signal from the notification service; runs on the GLib main loop thread. */
    void onSignal(String signal, MemorySegment parameters) {
        MemorySegment idValue = (MemorySegment) call(G_VARIANT_GET_CHILD_VALUE, parameters, 0L);
        int id = (int) call(G_VARIANT_GET_UINT32, idValue);
        call(G_VARIANT_UNREF, idValue);
        if (signal.equals("NotificationClosed")) {
            actions.remove(id);
            return;
        }
        Map<String, Runnable> mine = actions.get(id);
        if (mine == null) {
            return; // not ours
        }
        MemorySegment keyValue = (MemorySegment) call(G_VARIANT_GET_CHILD_VALUE, parameters, 1L);
        String key = Gtk.readUtf8((MemorySegment) call(G_VARIANT_GET_STRING, keyValue, MemorySegment.NULL));
        call(G_VARIANT_UNREF, keyValue);
        Runnable action = key == null ? null : mine.get(key);
        if (action != null) {
            app.dispatch(action);
        }
    }

    /** Stops listening. UI thread, before the application's callbacks go away. */
    synchronized void close() {
        actions.clear();
        if (!bus.equals(MemorySegment.NULL)) {
            if (clickSubscription != 0) {
                call(G_DBUS_SIGNAL_UNSUBSCRIBE, bus, clickSubscription);
                call(G_DBUS_SIGNAL_UNSUBSCRIBE, bus, closeSubscription);
                clickSubscription = 0;
            }
            Gtk.unref(bus);
            bus = MemorySegment.NULL;
        }
    }

    // ---- GVariant text ----

    private static MemorySegment parse(String type, String text, MemorySegment error) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment variantType = (MemorySegment) call(G_VARIANT_TYPE_NEW, utf8(arena, type));
            try {
                return (MemorySegment) call(G_VARIANT_PARSE, variantType, utf8(arena, text), MemorySegment.NULL,
                        MemorySegment.NULL, error);
            } finally {
                call(G_VARIANT_TYPE_FREE, variantType);
            }
        }
    }

    /** A GVariant text-format string literal; control characters other than newline and tab are dropped. */
    static String quote(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2).append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\'' -> out.append("\\'");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c >= 0x20 && c != 0x7f) {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('\'').toString();
    }

    private static MemorySegment utf8(Arena arena, String s) {
        return Gtk.utf8(arena, s);
    }

    private static void dropError(MemorySegment errorLocation) {
        MemorySegment e = errorLocation.get(ADDRESS, 0);
        if (!e.equals(MemorySegment.NULL)) {
            Gtk.errorFree(e);
            errorLocation.set(ADDRESS, 0, MemorySegment.NULL);
        }
    }

    private static Object call(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
