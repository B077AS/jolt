package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The native callbacks GTK and WebKit invoke: one upcall stub per kind, created once and kept for the life of the
 * process.
 *
 * <p>They must never be freed. GLib has a single default main context for the whole process, so a source or
 * async result queued by one {@code Application} can be dispatched during a later one. A stub tied to an
 * application's lifetime would then be a dangling function pointer and crash the JVM. Targets are found through
 * {@link Registry} by the id passed as {@code user_data}; a stale id simply resolves to nothing.
 */
final class Callbacks {

    /** {@code GSourceFunc}: user_data is the application id. */
    static final MemorySegment IDLE;
    /** {@code destroy} signal on the top-level window: user_data is the window id. */
    static final MemorySegment DESTROY;
    /** {@code window-state-event} on the top-level window (maximized or not): returns FALSE; user_data is the window id. */
    static final MemorySegment WINDOW_STATE;
    /** {@code script-message-received::<name>} on the content manager: user_data is the window id. */
    static final MemorySegment SCRIPT_MESSAGE;
    /** {@code close} signal on the web view (page called {@code window.close()}): user_data is the window id. */
    static final MemorySegment WEB_VIEW_CLOSE;
    /** {@code context-menu} signal: returns TRUE to suppress the menu. */
    static final MemorySegment CONTEXT_MENU;
    /** {@code decide-policy} on the web view: returns TRUE if the decision was made here. user_data is the window id. */
    static final MemorySegment DECIDE_POLICY;
    /** {@code permission-request} on the web view: returns TRUE if handled. user_data is the window id. */
    static final MemorySegment PERMISSION_REQUEST;
    /** {@code WebKitURISchemeRequestCallback} for the app's own scheme: user_data is the application id. */
    static final MemorySegment URI_SCHEME_REQUEST;
    /** {@code GAsyncReadyCallback} for {@code webkit_web_view_evaluate_javascript}: user_data is the eval id. */
    static final MemorySegment EVAL_DONE;
    /** {@code GDBusSignalCallback} for the notification service's signals: user_data is the application id. */
    static final MemorySegment NOTIFY_SIGNAL;
    /** {@code GLogFunc}: drops {@link Gtk#KNOWN_NOISE}, prints everything else as GLib would. */
    static final MemorySegment LOG;

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Arena forever = Arena.global();
        IDLE = stub(lookup, forever, "onIdle", MethodType.methodType(int.class, MemorySegment.class),
                FunctionDescriptor.of(JAVA_INT, ADDRESS), 0);
        DESTROY = stub(lookup, forever, "onDestroy", MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), 0);
        WINDOW_STATE = stub(lookup, forever, "onWindowState",
                MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), 0);
        SCRIPT_MESSAGE = stub(lookup, forever, "onScriptMessage",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS), 0);
        WEB_VIEW_CLOSE = stub(lookup, forever, "onWebViewClose",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), 0);
        CONTEXT_MENU = stub(lookup, forever, "onContextMenu", MethodType.methodType(int.class, MemorySegment.class,
                        MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS), 1);
        DECIDE_POLICY = stub(lookup, forever, "onDecidePolicy", MethodType.methodType(int.class, MemorySegment.class,
                        MemorySegment.class, int.class, MemorySegment.class),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS), 0);
        PERMISSION_REQUEST = stub(lookup, forever, "onPermissionRequest", MethodType.methodType(int.class, MemorySegment.class,
                        MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), 0);
        URI_SCHEME_REQUEST = stub(lookup, forever, "onUriSchemeRequest",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), 0);
        EVAL_DONE = stub(lookup, forever, "onEvalDone",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS), 0);
        NOTIFY_SIGNAL = stub(lookup, forever, "onNotifySignal",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class,
                        MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS), 0);
        LOG = stub(lookup, forever, "onLog",
                MethodType.methodType(void.class, MemorySegment.class, int.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, ADDRESS), 0);
    }

    private Callbacks() {}

    private static MemorySegment stub(MethodHandles.Lookup lookup, Arena arena, String method, MethodType type,
                                      FunctionDescriptor descriptor, long failureValue) {
        try {
            MethodHandle target = lookup.findStatic(Callbacks.class, method, type);
            return Native.LINKER.upcallStub(Native.guarded(target, failureValue), descriptor, arena);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ---- native callbacks (UI thread) ----

    /** Returns {@code G_SOURCE_REMOVE} (0). */
    private static int onIdle(MemorySegment data) {
        LinuxApplication app = Registry.get(data.address(), LinuxApplication.class);
        if (app != null) {
            app.drain();
        }
        return 0;
    }

    private static void onNotifySignal(MemorySegment connection, MemorySegment sender, MemorySegment path,
                                       MemorySegment interfaceName, MemorySegment signal, MemorySegment parameters,
                                       MemorySegment data) {
        LinuxApplication app = Registry.get(data.address(), LinuxApplication.class);
        if (app != null) {
            app.onNotificationSignal(Gtk.readUtf8(signal), parameters);
        }
    }

    private static void onDestroy(MemorySegment widget, MemorySegment data) {
        LinuxWindow window = Registry.get(data.address(), LinuxWindow.class);
        if (window != null) {
            window.onDestroyed();
        }
    }

    /** GdkEventWindowState on 64-bit: type(4) pad(4) window(8) send_event(1) pad(3) changed_mask(4) new_window_state(4). */
    private static final int GDK_WINDOW_STATE_MAXIMIZED = 1 << 2;

    private static int onWindowState(MemorySegment widget, MemorySegment event, MemorySegment data) {
        LinuxWindow window = Registry.get(data.address(), LinuxWindow.class);
        if (window != null) {
            MemorySegment state = event.reinterpret(28);
            int changed = state.get(JAVA_INT, 20);
            int now = state.get(JAVA_INT, 24);
            if ((changed & GDK_WINDOW_STATE_MAXIMIZED) != 0) {
                window.onMaximizedChanged((now & GDK_WINDOW_STATE_MAXIMIZED) != 0);
            }
        }
        return 0; // FALSE: let GTK handle the event too
    }

    private static void onScriptMessage(MemorySegment contentManager, MemorySegment javascriptResult, MemorySegment data) {
        LinuxWindow window = Registry.get(data.address(), LinuxWindow.class);
        if (window != null) {
            window.onScriptMessage(Gtk.scriptMessageText(javascriptResult));
        }
    }

    private static void onWebViewClose(MemorySegment webView, MemorySegment data) {
        LinuxWindow window = Registry.get(data.address(), LinuxWindow.class);
        if (window != null) {
            window.close();
        }
    }

    private static int onContextMenu(MemorySegment webView, MemorySegment menu, MemorySegment event, MemorySegment hit,
                                     MemorySegment data) {
        return 1;
    }

    private static int onDecidePolicy(MemorySegment webView, MemorySegment decision, int type, MemorySegment data) {
        LinuxWindow window = Registry.get(data.address(), LinuxWindow.class);
        return window != null && window.onDecidePolicy(decision, type) ? 1 : 0;
    }

    private static int onPermissionRequest(MemorySegment webView, MemorySegment request, MemorySegment data) {
        LinuxWindow window = Registry.get(data.address(), LinuxWindow.class);
        return window != null && window.onPermissionRequest(request) ? 1 : 0;
    }

    private static void onUriSchemeRequest(MemorySegment request, MemorySegment data) {
        LinuxApplication app = Registry.get(data.address(), LinuxApplication.class);
        if (app != null) {
            app.onSchemeRequest(request);
        }
    }

    private static void onLog(MemorySegment domain, int level, MemorySegment message, MemorySegment data) {
        if (!Gtk.isKnownNoise(Gtk.readUtf8(message))) {
            Gtk.logDefault(domain, level, message);
        }
    }

    private static void onEvalDone(MemorySegment source, MemorySegment result, MemorySegment data) {
        LinuxApplication.PendingEval pending = Registry.take(data.address(), LinuxApplication.PendingEval.class);
        String json = null;
        RuntimeException failure = null;
        try {
            json = Gtk.evaluateJavascriptFinish(source, result); // always finish, to free GLib's objects
        } catch (RuntimeException e) {
            failure = e;
        }
        if (pending == null) {
            return;
        }
        if (failure != null) {
            pending.future().completeExceptionally(new JoltException("Script execution failed: " + failure.getMessage(), failure));
        } else {
            pending.future().complete(json);
        }
    }
}
