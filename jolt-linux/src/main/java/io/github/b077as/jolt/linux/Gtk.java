package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.internal.Icons;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The slice of GLib, GTK 3 and WebKitGTK 4.1 the backend needs. Every signature was checked against the installed
 * {@code -dev} headers (see {@code docker/linux-test}); {@code gboolean}/{@code gint}/{@code guint} are 32-bit,
 * {@code gulong}/{@code GType}/{@code gsize} are 64-bit on the 64-bit Linux ABI.
 */
final class Gtk {

    static final int GTK_WINDOW_TOPLEVEL = 0;
    static final int USER_CONTENT_INJECT_ALL_FRAMES = 0;
    static final int USER_CONTENT_INJECT_TOP_FRAME = 1;
    static final int POLICY_DECISION_NAVIGATION_ACTION = 0; // WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION
    static final int POLICY_DECISION_NEW_WINDOW_ACTION = 1;
    static final int USER_SCRIPT_INJECT_AT_DOCUMENT_START = 0;

    private static final Arena LIBS = Arena.global();
    private static final SymbolLookup GLIB = SymbolLookup.libraryLookup("libglib-2.0.so.0", LIBS);
    private static final SymbolLookup GOBJECT = SymbolLookup.libraryLookup("libgobject-2.0.so.0", LIBS);
    private static final SymbolLookup GTK = SymbolLookup.libraryLookup("libgtk-3.so.0", LIBS);
    private static final SymbolLookup JSC = SymbolLookup.libraryLookup("libjavascriptcoregtk-4.1.so.0", LIBS);
    private static final SymbolLookup WEBKIT = SymbolLookup.libraryLookup("libwebkit2gtk-4.1.so.0", LIBS);

    // GLib / GObject
    private static final MethodHandle G_IDLE_ADD = h(GLIB, "g_idle_add", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle G_FREE = h(GLIB, "g_free", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle G_ERROR_FREE = h(GLIB, "g_error_free", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle G_OBJECT_UNREF = h(GOBJECT, "g_object_unref", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle G_SIGNAL_CONNECT_DATA = h(GOBJECT, "g_signal_connect_data",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    /** {@code g_object_new(GType, const char* first_property, ...)} with two property/value pairs and NULL. */
    private static final MethodHandle G_OBJECT_NEW_2 = h(GOBJECT, "g_object_new",
            FunctionDescriptor.of(ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
            Linker.Option.firstVariadicArg(2));

    // GTK
    private static final MethodHandle GTK_INIT_CHECK = h(GTK, "gtk_init_check", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GTK_MAIN = h(GTK, "gtk_main", FunctionDescriptor.ofVoid());
    private static final MethodHandle GTK_MAIN_QUIT = h(GTK, "gtk_main_quit", FunctionDescriptor.ofVoid());
    private static final MethodHandle GTK_WINDOW_NEW = h(GTK, "gtk_window_new", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle GTK_WINDOW_SET_TITLE = h(GTK, "gtk_window_set_title", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle GTK_WINDOW_SET_DEFAULT_SIZE = h(GTK, "gtk_window_set_default_size",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle GTK_WINDOW_SET_GEOMETRY_HINTS = h(GTK, "gtk_window_set_geometry_hints",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle GTK_WINDOW_RESIZE = h(GTK, "gtk_window_resize", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle GTK_WINDOW_MAXIMIZE = h(GTK, "gtk_window_maximize", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GTK_WINDOW_UNMAXIMIZE = h(GTK, "gtk_window_unmaximize", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GTK_WINDOW_SET_POSITION = h(GTK, "gtk_window_set_position", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle GTK_WINDOW_GET_POSITION = h(GTK, "gtk_window_get_position", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GTK_WINDOW_GET_SIZE = h(GTK, "gtk_window_get_size", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GDK_SCREEN_GET_DEFAULT = h(GTK, "gdk_screen_get_default", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle GDK_SCREEN_GET_WIDTH = h(GTK, "gdk_screen_get_width", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GDK_SCREEN_GET_HEIGHT = h(GTK, "gdk_screen_get_height", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GTK_CONTAINER_ADD = h(GTK, "gtk_container_add", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle GTK_WIDGET_SHOW = h(GTK, "gtk_widget_show", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GTK_WIDGET_HIDE = h(GTK, "gtk_widget_hide", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GTK_WIDGET_DESTROY = h(GTK, "gtk_widget_destroy", FunctionDescriptor.ofVoid(ADDRESS));

    // window icon, theme preference, background colour
    private static final SymbolLookup PIXBUF = SymbolLookup.libraryLookup("libgdk_pixbuf-2.0.so.0", LIBS);
    private static final MethodHandle PIXBUF_LOADER_NEW = h(PIXBUF, "gdk_pixbuf_loader_new", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle PIXBUF_LOADER_WRITE = h(PIXBUF, "gdk_pixbuf_loader_write",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
    private static final MethodHandle PIXBUF_LOADER_CLOSE = h(PIXBUF, "gdk_pixbuf_loader_close",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle PIXBUF_LOADER_GET_PIXBUF = h(PIXBUF, "gdk_pixbuf_loader_get_pixbuf",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GTK_WINDOW_SET_ICON = h(GTK, "gtk_window_set_icon", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle GTK_WINDOW_GET_ICON = h(GTK, "gtk_window_get_icon", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle WEB_VIEW_GET_BACKGROUND_COLOR = h(WEBKIT, "webkit_web_view_get_background_color",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle GTK_SETTINGS_GET_DEFAULT =h(GTK, "gtk_settings_get_default", FunctionDescriptor.of(ADDRESS));
    /** {@code g_object_set(object, "property", int value, NULL)}. */
    private static final MethodHandle G_OBJECT_SET_INT = h(GOBJECT, "g_object_set",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS), Linker.Option.firstVariadicArg(2));
    /** {@code g_object_get(object, "property", int* out, NULL)}. */
    private static final MethodHandle G_OBJECT_GET_INT = h(GOBJECT, "g_object_get",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS), Linker.Option.firstVariadicArg(2));

    // JavaScriptCore
    private static final MethodHandle JSC_VALUE_TO_STRING = h(JSC, "jsc_value_to_string", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle JSC_VALUE_TO_JSON = h(JSC, "jsc_value_to_json", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    // WebKitGTK
    private static final MethodHandle WEB_VIEW_GET_TYPE = h(WEBKIT, "webkit_web_view_get_type", FunctionDescriptor.of(JAVA_LONG));
    private static final MethodHandle WEBSITE_DATA_MANAGER_NEW = h(WEBKIT, "webkit_website_data_manager_new",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS), Linker.Option.firstVariadicArg(1));
    private static final MethodHandle WEB_CONTEXT_NEW_WITH_DATA_MANAGER = h(WEBKIT,
            "webkit_web_context_new_with_website_data_manager", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle USER_CONTENT_MANAGER_NEW = h(WEBKIT, "webkit_user_content_manager_new", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle USER_CONTENT_MANAGER_REGISTER = h(WEBKIT,
            "webkit_user_content_manager_register_script_message_handler", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle USER_CONTENT_MANAGER_ADD_SCRIPT = h(WEBKIT,
            "webkit_user_content_manager_add_script", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle USER_SCRIPT_NEW = h(WEBKIT, "webkit_user_script_new",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle USER_SCRIPT_UNREF = h(WEBKIT, "webkit_user_script_unref", FunctionDescriptor.ofVoid(ADDRESS));
    /** {@code JSCValue* webkit_javascript_result_get_js_value(WebKitJavascriptResult*)}, transfer none. */
    private static final MethodHandle JAVASCRIPT_RESULT_GET_JS_VALUE = h(WEBKIT, "webkit_javascript_result_get_js_value",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle WEB_VIEW_LOAD_URI =h(WEBKIT, "webkit_web_view_load_uri", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    /** {@code WebKitNavigationAction* webkit_navigation_policy_decision_get_navigation_action(decision)}, transfer none. */
    private static final MethodHandle NAVIGATION_DECISION_GET_ACTION = h(WEBKIT,
            "webkit_navigation_policy_decision_get_navigation_action", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle NAVIGATION_ACTION_GET_REQUEST = h(WEBKIT, "webkit_navigation_action_get_request",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle URI_REQUEST_GET_URI = h(WEBKIT, "webkit_uri_request_get_uri", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle POLICY_DECISION_IGNORE = h(WEBKIT, "webkit_policy_decision_ignore", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle PERMISSION_REQUEST_DENY = h(WEBKIT, "webkit_permission_request_deny", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle WEB_VIEW_GET_URI = h(WEBKIT, "webkit_web_view_get_uri", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle WEB_VIEW_LOAD_HTML = h(WEBKIT, "webkit_web_view_load_html",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle WEB_VIEW_EVALUATE_JAVASCRIPT = h(WEBKIT, "webkit_web_view_evaluate_javascript",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle WEB_VIEW_EVALUATE_JAVASCRIPT_FINISH = h(WEBKIT, "webkit_web_view_evaluate_javascript_finish",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle WEB_VIEW_GET_SETTINGS = h(WEBKIT, "webkit_web_view_get_settings", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle WEB_VIEW_GET_INSPECTOR = h(WEBKIT, "webkit_web_view_get_inspector", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle SETTINGS_SET_DEVELOPER_EXTRAS = h(WEBKIT, "webkit_settings_set_enable_developer_extras",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle INSPECTOR_SHOW = h(WEBKIT, "webkit_web_inspector_show", FunctionDescriptor.ofVoid(ADDRESS));
    /** {@code void webkit_web_view_set_background_color(WebKitWebView*, const GdkRGBA*)}; GdkRGBA is four doubles. */
    private static final MethodHandle WEB_VIEW_SET_BACKGROUND_COLOR = h(WEBKIT, "webkit_web_view_set_background_color",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    private Gtk() {}

    private static MethodHandle h(SymbolLookup lib, String name, FunctionDescriptor fd, Linker.Option... options) {
        return Native.downcall(lib, name, fd, options);
    }

    /** Forces class initialization, which loads every library and resolves every symbol (throws if any is missing). */
    static void load() {
        // static initializers do the work
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

    /** Allocates {@code s} as a NUL-terminated UTF-8 string. */
    static MemorySegment utf8(Arena arena, String s) {
        return arena.allocateFrom(s, StandardCharsets.UTF_8);
    }

    /** Reads a NUL-terminated UTF-8 string at {@code ptr}, or {@code null} for NULL. */
    static String readUtf8(MemorySegment ptr) {
        return ptr.equals(MemorySegment.NULL) ? null : ptr.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
    }

    // ---- GLib / GObject ----

    static void idleAdd(MemorySegment callback, MemorySegment data) {
        call(G_IDLE_ADD, callback, data);
    }

    static void free(MemorySegment ptr) {
        call(G_FREE, ptr);
    }

    static void errorFree(MemorySegment gError) {
        call(G_ERROR_FREE, gError);
    }

    /** {@code GError.message}: {@code GQuark domain (4), gint code (4), gchar* message}. */
    static String errorMessage(MemorySegment gError) {
        return readUtf8(gError.reinterpret(16).get(ADDRESS, 8));
    }

    static void unref(MemorySegment object) {
        call(G_OBJECT_UNREF, object);
    }

    static void connect(MemorySegment instance, String signal, MemorySegment handler, MemorySegment data) {
        try (Arena arena = Arena.ofConfined()) {
            call(G_SIGNAL_CONNECT_DATA, instance, utf8(arena, signal), handler, data, MemorySegment.NULL, 0);
        }
    }

    // ---- GTK ----

    static boolean initCheck() {
        return (int) call(GTK_INIT_CHECK, MemorySegment.NULL, MemorySegment.NULL) != 0;
    }

    static void main() {
        call(GTK_MAIN);
    }

    static void mainQuit() {
        call(GTK_MAIN_QUIT);
    }

    static MemorySegment windowNew() {
        return (MemorySegment) call(GTK_WINDOW_NEW, GTK_WINDOW_TOPLEVEL);
    }

    static void windowSetTitle(MemorySegment window, String title) {
        try (Arena arena = Arena.ofConfined()) {
            call(GTK_WINDOW_SET_TITLE, window, utf8(arena, title));
        }
    }

    static void windowSetDefaultSize(MemorySegment window, int width, int height) {
        call(GTK_WINDOW_SET_DEFAULT_SIZE, window, width, height);
    }

    /** The smallest size the window manager lets the user resize the window to. Zero in one dimension leaves it unconstrained. */
    static void windowSetMinSize(MemorySegment window, int minWidth, int minHeight) {
        final int gdkHintMinSize = 1 << 1;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment geometry = arena.allocate(56); // GdkGeometry: min_width and min_height come first
            geometry.set(JAVA_INT, 0, minWidth);
            geometry.set(JAVA_INT, 4, minHeight);
            call(GTK_WINDOW_SET_GEOMETRY_HINTS, window, MemorySegment.NULL, geometry, gdkHintMinSize);
        }
    }

    static void windowResize(MemorySegment window, int width, int height) {
        call(GTK_WINDOW_RESIZE, window, width, height);
    }

    /** Asks the window manager to maximize; on a window that is not shown yet it sets the state the window opens in. */
    static void windowMaximize(MemorySegment window) {
        call(GTK_WINDOW_MAXIMIZE, window);
    }

    static void windowUnmaximize(MemorySegment window) {
        call(GTK_WINDOW_UNMAXIMIZE, window);
    }

    /** The window's {x, y, width, height} and the screen's {width, height}, as one array of six. For tests (X11 only: Wayland has no positions). */
    static int[] windowGeometry(MemorySegment window) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment x = arena.allocate(4);
            MemorySegment y = arena.allocate(4);
            MemorySegment w = arena.allocate(4);
            MemorySegment h = arena.allocate(4);
            call(GTK_WINDOW_GET_POSITION, window, x, y);
            call(GTK_WINDOW_GET_SIZE, window, w, h);
            MemorySegment screen = (MemorySegment) call(GDK_SCREEN_GET_DEFAULT);
            return new int[] {x.get(JAVA_INT, 0), y.get(JAVA_INT, 0), w.get(JAVA_INT, 0), h.get(JAVA_INT, 0),
                    (int) call(GDK_SCREEN_GET_WIDTH, screen), (int) call(GDK_SCREEN_GET_HEIGHT, screen)};
        }
    }

    /** {@code GTK_WIN_POS_CENTER}: centre on the screen when the window is first shown (X11; a Wayland compositor decides itself). */
    static final int WIN_POS_CENTER = 1;

    static void windowSetPosition(MemorySegment window, int position) {
        call(GTK_WINDOW_SET_POSITION, window, position);
    }

    static void containerAdd(MemorySegment container, MemorySegment child) {
        call(GTK_CONTAINER_ADD, container, child);
    }

    static void widgetShow(MemorySegment widget) {
        call(GTK_WIDGET_SHOW, widget);
    }

    static void widgetHide(MemorySegment widget) {
        call(GTK_WIDGET_HIDE, widget);
    }

    static void widgetDestroy(MemorySegment widget) {
        call(GTK_WIDGET_DESTROY, widget);
    }

    // ---- JavaScriptCore ----

    /** Converts a {@code JSCValue*} to a Java string (via {@code jsc_value_to_string}); the C string is freed. */
    static String jscToString(MemorySegment value) {
        MemorySegment raw = (MemorySegment) call(JSC_VALUE_TO_STRING, value);
        String s = readUtf8(raw);
        if (!raw.equals(MemorySegment.NULL)) {
            free(raw);
        }
        return s;
    }

    /**
     * The text of a {@code script-message-received} payload. The signal's argument is a
     * {@code WebKitJavascriptResult*} (not a {@code JSCValue*}, despite what newer evaluate APIs return), so it is
     * unwrapped first.
     */
    static String scriptMessageText(MemorySegment javascriptResult) {
        MemorySegment value = (MemorySegment) call(JAVASCRIPT_RESULT_GET_JS_VALUE, javascriptResult);
        return jscToString(value);
    }

    /** JSON for a {@code JSCValue*}, or {@code null} when JavaScriptCore has no JSON form (for example {@code undefined}). */
    static String jscToJson(MemorySegment value) {
        MemorySegment raw = (MemorySegment) call(JSC_VALUE_TO_JSON, value, 0);
        String s = readUtf8(raw);
        if (!raw.equals(MemorySegment.NULL)) {
            free(raw);
        }
        return s;
    }

    // ---- WebKitGTK ----

    static MemorySegment newWebContext(String dataDir, String cacheDir) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment manager = (MemorySegment) call(WEBSITE_DATA_MANAGER_NEW, utf8(arena, "base-data-directory"),
                    utf8(arena, dataDir), utf8(arena, "base-cache-directory"), utf8(arena, cacheDir), MemorySegment.NULL);
            MemorySegment context = (MemorySegment) call(WEB_CONTEXT_NEW_WITH_DATA_MANAGER, manager);
            unref(manager); // the context keeps its own reference
            return context;
        }
    }

    static MemorySegment newUserContentManager() {
        return (MemorySegment) call(USER_CONTENT_MANAGER_NEW);
    }

    /** Registers {@code window.webkit.messageHandlers.<name>}; fires {@code script-message-received::<name>}. */
    static boolean registerScriptMessageHandler(MemorySegment manager, String name) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) call(USER_CONTENT_MANAGER_REGISTER, manager, utf8(arena, name)) != 0;
        }
    }

    static void addUserScript(MemorySegment manager, String source) {
        addUserScript(manager, source, USER_CONTENT_INJECT_ALL_FRAMES);
    }

    /** @param frames {@link #USER_CONTENT_INJECT_ALL_FRAMES} or {@link #USER_CONTENT_INJECT_TOP_FRAME} */
    static void addUserScript(MemorySegment manager, String source, int frames) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment script = (MemorySegment) call(USER_SCRIPT_NEW, utf8(arena, source), frames,
                    USER_SCRIPT_INJECT_AT_DOCUMENT_START, MemorySegment.NULL, MemorySegment.NULL);
            call(USER_CONTENT_MANAGER_ADD_SCRIPT, manager, script);
            call(USER_SCRIPT_UNREF, script);
        }
    }

    static MemorySegment newWebView(MemorySegment context, MemorySegment userContentManager) {
        try (Arena arena = Arena.ofConfined()) {
            long type = (long) call(WEB_VIEW_GET_TYPE);
            return (MemorySegment) call(G_OBJECT_NEW_2, type, utf8(arena, "web-context"), context,
                    utf8(arena, "user-content-manager"), userContentManager, MemorySegment.NULL);
        }
    }

    static void loadUri(MemorySegment webView, String uri) {
        try (Arena arena = Arena.ofConfined()) {
            call(WEB_VIEW_LOAD_URI, webView, utf8(arena, uri));
        }
    }

    /** @param baseUri the document's address, which gives it a real origin; relative URLs resolve against it */
    static void loadHtml(MemorySegment webView, String html, String baseUri) {
        try (Arena arena = Arena.ofConfined()) {
            call(WEB_VIEW_LOAD_HTML, webView, utf8(arena, html), utf8(arena, baseUri));
        }
    }

    /** The address a navigation policy decision (navigation or new-window) is about, or {@code null}. */
    static String decisionUri(MemorySegment decision) {
        MemorySegment action = (MemorySegment) call(NAVIGATION_DECISION_GET_ACTION, decision);
        if (action.equals(MemorySegment.NULL)) {
            return null;
        }
        MemorySegment request = (MemorySegment) call(NAVIGATION_ACTION_GET_REQUEST, action);
        return request.equals(MemorySegment.NULL) ? null : readUtf8((MemorySegment) call(URI_REQUEST_GET_URI, request));
    }

    static void ignoreDecision(MemorySegment decision) {
        call(POLICY_DECISION_IGNORE, decision);
    }

    static void denyPermission(MemorySegment request) {
        call(PERMISSION_REQUEST_DENY, request);
    }

    /** The address of the page the view is showing, or {@code null}. */
    static String webViewUri(MemorySegment webView) {
        return readUtf8((MemorySegment) call(WEB_VIEW_GET_URI, webView));
    }

    /** Starts evaluating {@code script}; {@code callback} (a GAsyncReadyCallback) later receives {@code userData}. */
    static void evaluateJavascript(MemorySegment webView, String script, MemorySegment callback, MemorySegment userData) {
        try (Arena arena = Arena.ofConfined()) {
            call(WEB_VIEW_EVALUATE_JAVASCRIPT, webView, utf8(arena, script), -1L, MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL, callback, userData);
        }
    }

    /**
     * Completes an evaluation. Returns the JSON form of the result ({@code "null"} for values without one) or throws
     * with the script error's message. Takes ownership of and frees the intermediate GLib objects.
     */
    static String evaluateJavascriptFinish(MemorySegment webView, MemorySegment asyncResult) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment errorOut = arena.allocate(ADDRESS);
            MemorySegment value = (MemorySegment) call(WEB_VIEW_EVALUATE_JAVASCRIPT_FINISH, webView, asyncResult, errorOut);
            MemorySegment error = errorOut.get(ADDRESS, 0);
            if (value.equals(MemorySegment.NULL)) {
                String message = error.equals(MemorySegment.NULL) ? "unknown error" : errorMessage(error);
                if (!error.equals(MemorySegment.NULL)) {
                    errorFree(error);
                }
                throw new IllegalStateException(message);
            }
            try {
                String json = jscToJson(value);
                return json == null ? "null" : json;
            } finally {
                unref(value);
            }
        }
    }

    static void setDeveloperExtras(MemorySegment webView, boolean enabled) {
        MemorySegment settings = (MemorySegment) call(WEB_VIEW_GET_SETTINGS, webView); // owned by the view
        call(SETTINGS_SET_DEVELOPER_EXTRAS, settings, enabled ? 1 : 0);
    }

    /**
     * Sets the window icon from PNG data, or from a Windows {@code .ico} file (its largest PNG image is used, which GTK
     * decodes reliably). Returns false if the data is not a decodable image. Has no visible effect on Wayland, where the
     * compositor takes the icon from the application's desktop entry.
     */
    static boolean setWindowIcon(MemorySegment window, byte[] data) {
        byte[] png = data;
        if (Icons.isIco(data)) {
            byte[] inside = Icons.largestPng(data);
            if (inside != null) {
                png = inside;
            } // an icon file with only bitmap images is handed to the loader as it is
        }
        MemorySegment loader = (MemorySegment) call(PIXBUF_LOADER_NEW);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(ADDRESS);
            MemorySegment image = arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, png);
            boolean written = (int) call(PIXBUF_LOADER_WRITE, loader, image, (long) png.length, error) != 0;
            MemorySegment closeError = arena.allocate(ADDRESS);
            boolean closed = (int) call(PIXBUF_LOADER_CLOSE, loader, closeError) != 0; // must always be called
            for (MemorySegment e : new MemorySegment[] {error.get(ADDRESS, 0), closeError.get(ADDRESS, 0)}) {
                if (!e.equals(MemorySegment.NULL)) {
                    errorFree(e);
                }
            }
            MemorySegment pixbuf = written && closed ? (MemorySegment) call(PIXBUF_LOADER_GET_PIXBUF, loader) : MemorySegment.NULL;
            if (pixbuf.equals(MemorySegment.NULL)) {
                return false;
            }
            call(GTK_WINDOW_SET_ICON, window, pixbuf); // the window takes its own reference
            return true;
        } finally {
            unref(loader); // also releases the loader's pixbuf, which the window now holds separately
        }
    }

    /** GTK's dark-theme preference. It is process-wide, and a desktop-drawn title bar follows it where supported. */
    static void setPreferDarkTheme(boolean dark) {
        MemorySegment settings = (MemorySegment) call(GTK_SETTINGS_GET_DEFAULT);
        try (Arena arena = Arena.ofConfined()) {
            call(G_OBJECT_SET_INT, settings, utf8(arena, "gtk-application-prefer-dark-theme"), dark ? 1 : 0, MemorySegment.NULL);
        }
    }

    static boolean preferDarkTheme() {
        MemorySegment settings = (MemorySegment) call(GTK_SETTINGS_GET_DEFAULT);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_INT);
            call(G_OBJECT_GET_INT, settings, utf8(arena, "gtk-application-prefer-dark-theme"), out, MemorySegment.NULL);
            return out.get(JAVA_INT, 0) != 0;
        }
    }

    /** Whether GTK holds an icon for {@code window}. For tests. */
    static boolean windowHasIcon(MemorySegment window) {
        return !((MemorySegment) call(GTK_WINDOW_GET_ICON, window)).equals(MemorySegment.NULL);
    }

    /** The colour the web view shows behind the page as {@code 0xRRGGBB}. For tests. */
    static int backgroundColor(MemorySegment webView) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rgba = arena.allocate(4 * 8L, 8);
            call(WEB_VIEW_GET_BACKGROUND_COLOR, webView, rgba);
            int red = (int) Math.round(rgba.get(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 0) * 255);
            int green = (int) Math.round(rgba.get(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 8) * 255);
            int blue = (int) Math.round(rgba.get(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 16) * 255);
            return (red << 16) | (green << 8) | blue;
        }
    }

    /** Colour ({@code 0xRRGGBB}) shown behind the page. */
    static void setBackgroundColor(MemorySegment webView, int rgb) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rgba = arena.allocate(4 * 8L, 8); // GdkRGBA: four doubles, red green blue alpha
            rgba.set(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 0, ((rgb >> 16) & 0xFF) / 255.0);
            rgba.set(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 8, ((rgb >> 8) & 0xFF) / 255.0);
            rgba.set(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 16, (rgb & 0xFF) / 255.0);
            rgba.set(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 24, 1.0);
            call(WEB_VIEW_SET_BACKGROUND_COLOR, webView, rgba);
        }
    }

    static void showInspector(MemorySegment webView) {
        MemorySegment inspector = (MemorySegment) call(WEB_VIEW_GET_INSPECTOR, webView); // owned by the view
        call(INSPECTOR_SHOW, inspector);
    }
}
