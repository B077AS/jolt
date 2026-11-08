package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.TaskbarProgress;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Progress on the application's launcher icon, by the {@code com.canonical.Unity.LauncherEntry} {@code Update} signal on the session
 * bus: {@code ('application://<appId>.desktop', {'progress': <0.4>, 'progress-visible': <true>})}. Best effort. KDE Plasma's task
 * manager, Ubuntu's dock and Dash to Dock/Panel draw it; GNOME Shell without such an extension, XFCE and most others ignore it. The
 * desktop matches the id to an installed {@code .desktop} file, so an application without one shows nothing. It is one bar per
 * application, not per window, and has only a bar: an error or paused state is drawn the same, and an indeterminate one is shown as
 * a bar with no progress. UI thread.
 */
final class LauncherProgress {

    private static final System.Logger LOG = System.getLogger(LauncherProgress.class.getName());
    private static final Arena LIBS = Arena.global();
    private static final SymbolLookup GLIB = SymbolLookup.libraryLookup("libglib-2.0.so.0", LIBS);
    private static final SymbolLookup GIO = SymbolLookup.libraryLookup("libgio-2.0.so.0", LIBS);
    private static final MethodHandle G_BUS_GET_SYNC = Native.downcall(GIO, "g_bus_get_sync", FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle EMIT_SIGNAL = Native.downcall(GIO, "g_dbus_connection_emit_signal",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle VARIANT_PARSE = Native.downcall(GLIB, "g_variant_parse",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle VARIANT_TYPE_NEW = Native.downcall(GLIB, "g_variant_type_new", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle VARIANT_TYPE_FREE = Native.downcall(GLIB, "g_variant_type_free", FunctionDescriptor.ofVoid(ADDRESS));
    private static final int G_BUS_TYPE_SESSION = 2;

    private final String uri;
    private final String path;
    private MemorySegment bus = MemorySegment.NULL;
    private boolean busTried;

    LauncherProgress(String appId) {
        String id = appId.replaceAll("[^A-Za-z0-9._-]", "_").replaceFirst("^\\.+", "_");
        this.uri = "application://" + id + ".desktop";
        this.path = "/com/canonical/unity/launcherentry/" + Integer.toUnsignedString(uri.hashCode());
    }

    /** Whether there is a session bus to send on. */
    boolean available() {
        return connection() != null;
    }

    private MemorySegment connection() {
        if (!busTried) {
            busTried = true;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment error = arena.allocate(ADDRESS);
                bus = (MemorySegment) call(G_BUS_GET_SYNC, G_BUS_TYPE_SESSION, MemorySegment.NULL, error);
                if (bus.equals(MemorySegment.NULL)) {
                    MemorySegment e = error.get(ADDRESS, 0);
                    LOG.log(System.Logger.Level.DEBUG, "no session bus for launcher progress: {0}", e.equals(MemorySegment.NULL) ? "?" : Gtk.errorMessage(e));
                    if (!e.equals(MemorySegment.NULL)) {
                        Gtk.errorFree(e);
                    }
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "launcher progress unavailable", e);
            }
        }
        return bus.equals(MemorySegment.NULL) ? null : bus;
    }

    void set(TaskbarProgress progress) {
        MemorySegment connection = connection();
        if (connection == null) {
            return;
        }
        boolean visible = progress.state() != TaskbarProgress.State.NONE;
        String text = "(" + GtkNotifications.quote(uri) + ", {'progress': <" + String.format(Locale.ROOT, "%.4f", progress.fraction())
                + ">, 'progress-visible': <" + visible + ">})";
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(ADDRESS);
            MemorySegment type = (MemorySegment) call(VARIANT_TYPE_NEW, Gtk.utf8(arena, "(sa{sv})"));
            MemorySegment parameters;
            try {
                parameters = (MemorySegment) call(VARIANT_PARSE, type, Gtk.utf8(arena, text), MemorySegment.NULL, MemorySegment.NULL, error);
            } finally {
                call(VARIANT_TYPE_FREE, type);
            }
            if (parameters.equals(MemorySegment.NULL)) {
                MemorySegment e = error.get(ADDRESS, 0);
                LOG.log(System.Logger.Level.WARNING, "launcher progress: bad variant: {0}", e.equals(MemorySegment.NULL) ? "?" : Gtk.errorMessage(e));
                if (!e.equals(MemorySegment.NULL)) {
                    Gtk.errorFree(e);
                }
                return;
            }
            // the call takes the floating reference to the parameters
            call(EMIT_SIGNAL, connection, MemorySegment.NULL, Gtk.utf8(arena, path), Gtk.utf8(arena, "com.canonical.Unity.LauncherEntry"),
                    Gtk.utf8(arena, "Update"), parameters, error);
            MemorySegment e = error.get(ADDRESS, 0);
            if (!e.equals(MemorySegment.NULL)) {
                LOG.log(System.Logger.Level.DEBUG, "launcher progress not sent: {0}", Gtk.errorMessage(e));
                Gtk.errorFree(e);
            }
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
