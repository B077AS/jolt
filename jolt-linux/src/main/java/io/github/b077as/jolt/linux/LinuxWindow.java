package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.Permission;
import io.github.b077as.jolt.BlockedEvent;
import io.github.b077as.jolt.CloseBehavior;
import io.github.b077as.jolt.ColorScheme;
import io.github.b077as.jolt.ContextMenuInfo;
import io.github.b077as.jolt.ContextMenuProvider;
import io.github.b077as.jolt.EditCommand;
import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.Menu;
import io.github.b077as.jolt.PrintOptions;
import io.github.b077as.jolt.SecurityPolicy;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowClosedException;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.internal.BlobBodyShim;
import io.github.b077as.jolt.internal.BridgeScript;
import io.github.b077as.jolt.internal.ClipboardData;
import io.github.b077as.jolt.internal.FileDragProtocol;
import io.github.b077as.jolt.internal.FileDropProtocol;
import io.github.b077as.jolt.internal.FrameProtocol;
import io.github.b077as.jolt.internal.FrameScript;
import io.github.b077as.jolt.internal.NativeDialogHost;
import io.github.b077as.jolt.internal.NativeFileDragHost;
import io.github.b077as.jolt.internal.PageFileDrag;
import io.github.b077as.jolt.internal.PageRpc;
import io.github.b077as.jolt.internal.PageFileDrop;
import io.github.b077as.jolt.internal.NativeResourceHost;
import io.github.b077as.jolt.internal.ResourceService;
import io.github.b077as.jolt.internal.SecurityGuard;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A GTK 3 top-level window containing a WebKitGTK web view.
 *
 * <p>Public methods may be called from any thread; they hop to the UI thread. Unlike WebView2, WebKitGTK creates
 * the web view synchronously, so the window is ready as soon as the constructor returns.
 */
final class LinuxWindow implements WebWindow, NativeResourceHost, NativeDialogHost, NativeFileDragHost, FrameProtocol.Host {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.linux");
    /** Name of the {@code window.webkit.messageHandlers.<name>} channel; must match the bridge script. */
    private static final String MESSAGE_HANDLER = "jolt";

    private final LinuxApplication app;
    private final WindowOptions options;
    private final SecurityGuard guard;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CopyOnWriteArrayList<Consumer<String>> messageListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> closedListeners = new CopyOnWriteArrayList<>();

    // UI thread only
    private final Set<CompletableFuture<?>> inflight = new HashSet<>();
    private final long id;
    private MemorySegment window;
    private MemorySegment webView;
    private MemorySegment contentManager;
    private boolean destroyed;
    private boolean progressShown;
    /** {@link #destroyNow} has started; the window goes as soon as an open dialog has returned. */
    private volatile boolean closing;
    private LinuxDialogs dialogs;
    /** Whether the window has been shown at least once (UI thread). */
    private boolean shown;
    /** Asked to be maximized before it was first shown. Readable from any thread. */
    private volatile boolean maximizeWhenShown;
    /** The window manager's word on it, kept up to date by the window-state signal. Readable from any thread. */
    private volatile boolean maximized;
    /** Asked to be full screen before it was first shown. Readable from any thread. */
    private volatile boolean fullscreenWhenShown;
    /** The window manager's word on it, kept up to date by the window-state signal. Readable from any thread. */
    private volatile boolean fullscreen;
    /** Mirrors of the window's state for callers on other threads; kept up to date on the UI thread. */
    private volatile boolean visible;
    private volatile boolean iconified;
    /** Fed by the window-state signal (GDK_WINDOW_STATE_FOCUSED): the window manager gave this window the keyboard focus. */
    private volatile boolean focused;
    private volatile CloseBehavior closeBehavior = CloseBehavior.CLOSE;
    private ResourceService resourceService;
    /** The box that holds the menu bar above the web view; NULL until the first menu bar (UI thread). */
    private MemorySegment contentBox = MemorySegment.NULL;
    private GtkMenus.Built menuBar;
    private MemorySegment menuAccelGroup = MemorySegment.NULL;
    private volatile ContextMenuProvider contextMenuProvider;

    /** Must be called on the UI thread. */
    LinuxWindow(LinuxApplication app, WindowOptions options) {
        this.app = app;
        this.options = options;
        this.guard = new SecurityGuard(options.security(), app::dispatch);

        window = Gtk.windowNew();
        Gtk.windowSetTitle(window, options.title());
        Gtk.windowSetDefaultSize(window, options.width(), options.height());
        if (options.frameless()) {
            Gtk.windowSetDecorated(window, false); // the page draws the title bar
        }
        if (!options.resizable()) {
            Gtk.windowSetResizable(window, false);
        }
        // A closed owner, or one from another application, is ignored.
        if (options.owner() instanceof LinuxWindow owner && owner.app == app && !owner.destroyed) {
            Gtk.windowSetOwner(window, owner.window);
        }

        contentManager = Gtk.newUserContentManager();
        if (!Gtk.registerScriptMessageHandler(contentManager, MESSAGE_HANDLER)) {
            throw new JoltException("Could not register the script message handler");
        }
        boolean subframes = options.security().bridgeInSubframes();
        Gtk.addUserScript(contentManager,
                BridgeScript.build("window.webkit.messageHandlers." + MESSAGE_HANDLER + ".postMessage(m)", subframes),
                subframes ? Gtk.USER_CONTENT_INJECT_ALL_FRAMES : Gtk.USER_CONTENT_INJECT_TOP_FRAME);
        if (options.frameless()) {
            // after the bridge: the frame script talks through window.jolt
            Gtk.addUserScript(contentManager, FrameScript.build("linux", options.resizable()), Gtk.USER_CONTENT_INJECT_TOP_FRAME);
        }
        webView = Gtk.newWebView(app.webContext(), contentManager);
        Gtk.setDeveloperExtras(webView, options.devTools());
        GtkMedia.enable(webView);

        this.id = app.register(this);
        MemorySegment key = MemorySegment.ofAddress(id);
        Gtk.connect(window, "destroy", Callbacks.DESTROY, key);
        Gtk.connect(window, "delete-event", Callbacks.DELETE_EVENT, key);
        Gtk.connect(window, "window-state-event", Callbacks.WINDOW_STATE, key);
        Gtk.connect(contentManager, "script-message-received::" + MESSAGE_HANDLER, Callbacks.SCRIPT_MESSAGE, key);
        Gtk.connect(webView, "close", Callbacks.WEB_VIEW_CLOSE, key);
        Gtk.connect(webView, "decide-policy", Callbacks.DECIDE_POLICY, key);
        Gtk.connect(webView, "permission-request", Callbacks.PERMISSION_REQUEST, key);
        Gtk.connect(webView, "drag-data-received", Callbacks.DRAG_DATA_RECEIVED, key);
        Gtk.connect(webView, "drag-drop", Callbacks.DRAG_DROP, key);
        Gtk.connect(webView, "drag-data-get", Callbacks.DRAG_DATA_GET, key);
        Gtk.connect(webView, "drag-end", Callbacks.DRAG_END, key);
        Gtk.connect(webView, "drag-failed", Callbacks.DRAG_FAILED, key);
        // Always connected: the handler asks the window, which suppresses the web view's menu unless devtools are on or a provider is set
        Gtk.connect(webView, "context-menu", Callbacks.CONTEXT_MENU, key);

        // Appearance first, so the window never shows in the wrong colours or without its icon.
        if (options.titleBarScheme() != null) {
            Gtk.setPreferDarkTheme(options.titleBarScheme() == ColorScheme.DARK);
        }
        if (options.icon() != null && !Gtk.setWindowIcon(window, options.icon())) {
            LOG.log(System.Logger.Level.WARNING, "The window icon could not be decoded; it must be PNG or ICO data");
        }
        app.installDesktopIcon(options.title(), options.icon());
        if (options.backgroundRgb() != null) {
            Gtk.setBackgroundColor(webView, options.backgroundRgb());
        }

        if (options.resizable() && (options.minWidth() > 0 || options.minHeight() > 0)) {
            Gtk.windowSetMinSize(window, options.minWidth(), options.minHeight());
        }

        Gtk.containerAdd(window, webView);
        Gtk.widgetShow(webView);
        if (options.maximized() && options.resizable()) {
            Gtk.windowMaximize(window); // before the window is shown this sets the state it opens in
            maximizeWhenShown = true;
        } else if (options.centered()) {
            Gtk.windowSetPosition(window, Gtk.WIN_POS_CENTER);
        }
        if (options.visible()) {
            Gtk.widgetShow(window);
            shown = true;
            visible = true;
            maximizeWhenShown = false; // from here the window manager's state counts
            fullscreenWhenShown = false;
        }
        ready.complete(null);
    }

    // ---- WebWindow (any thread) ----

    @Override
    public CompletableFuture<Void> ready() {
        return ready;
    }

    @Override
    public void loadUrl(String url) {
        app.dispatch(() -> {
            if (!destroyed) {
                Gtk.loadUri(webView, url);
            }
        });
    }

    @Override
    public void loadHtml(String html) {
        app.dispatch(() -> {
            if (!destroyed) {
                Gtk.loadHtml(webView, html, SecurityPolicy.APP_ORIGIN + "/"); // a real origin the policy can recognise
            }
        });
    }

    /**
     * Serves the app's files from inside WebKitGTK through a URI scheme of our own ({@code jolt://app}). The origin is added to
     * the window's trusted origins. Returns false on a system whose WebKitGTK is too old for that.
     */
    @Override
    public boolean loadResources(ResourceService service) {
        if (!GtkResources.available()) {
            return false;
        }
        app.dispatch(() -> {
            if (destroyed) {
                return;
            }
            resourceService = service;
            app.ensureResourceScheme();
            // WebKitGTK aborts the process on a request body that holds a Blob; the script sends bytes instead (see BlobBodyShim)
            Gtk.addUserScript(contentManager, BlobBodyShim.script(GtkResources.ORIGIN));
            guard.set(guard.policy().withTrustedOrigins(GtkResources.ORIGIN));
            Gtk.loadUri(webView, GtkResources.ORIGIN + service.startPath());
        });
        return true;
    }

    @Override
    public CompletableFuture<String> eval(String script) {
        CompletableFuture<String> result = new CompletableFuture<>();
        app.dispatch(() -> {
            if (destroyed) {
                result.completeExceptionally(new WindowClosedException());
                return;
            }
            inflight.add(result);
            result.whenComplete((r, e) -> inflight.remove(result));
            long requestId = app.registerEval(result);
            Gtk.evaluateJavascript(webView, script, Callbacks.EVAL_DONE, MemorySegment.ofAddress(requestId));
        });
        return result;
    }

    @Override
    public void setTaskbarProgress(io.github.b077as.jolt.TaskbarProgress progress) {
        java.util.Objects.requireNonNull(progress, "progress");
        app.dispatch(() -> {
            if (!destroyed) {
                progressShown = progress.state() != io.github.b077as.jolt.TaskbarProgress.State.NONE;
                app.launcherProgress().set(progress);
            }
        });
    }

    /** Best effort: true when there is a session bus; whether the desktop draws it is up to the desktop. */
    @Override
    public boolean supportsTaskbarProgress() {
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        return System.getenv("DBUS_SESSION_BUS_ADDRESS") != null
                || runtime != null && java.nio.file.Files.exists(java.nio.file.Path.of(runtime, "bus"));
    }

    @Override
    public void addInitScript(String script) {
        app.dispatch(() -> {
            if (!destroyed) {
                Gtk.addUserScript(contentManager, script);
            }
        });
    }

    @Override
    public void onMessage(Consumer<String> listener) {
        messageListeners.add(listener);
    }

    @Override
    public void postMessage(String message) {
        eval(BridgeScript.dispatch(message)).exceptionally(t -> {
            if (!(t instanceof WindowClosedException)) { // closing with a message in flight is expected, not a problem
                LOG.log(System.Logger.Level.WARNING, "postMessage failed: " + t.getMessage());
            }
            return null;
        });
    }

    @Override
    public void setTitle(String title) {
        app.dispatch(() -> {
            if (!destroyed) {
                Gtk.windowSetTitle(window, title);
            }
        });
    }

    @Override
    public void setIcon(byte[] png) {
        byte[] copy = png == null ? null : png.clone();
        app.dispatch(() -> {
            if (!destroyed && copy != null && !Gtk.setWindowIcon(window, copy)) {
                LOG.log(System.Logger.Level.WARNING, "The window icon could not be decoded; it must be PNG or ICO data");
            }
            if (!destroyed && copy != null) {
                app.installDesktopIcon(options.title(), copy);
            }
        });
    }

    @Override
    public void setTitleBarScheme(ColorScheme scheme) {
        app.dispatch(() -> {
            if (!destroyed && scheme != null) {
                Gtk.setPreferDarkTheme(scheme == ColorScheme.DARK);
            }
        });
    }

    @Override
    public void setTitleBarColors(int backgroundRgb, int textRgb) {
        // Linux desktops draw (or let the toolkit draw) the title bar themselves; there is no portable way to colour it.
        LOG.log(System.Logger.Level.DEBUG, "Custom title bar colours are not supported on Linux; ignored");
    }

    @Override
    public void setBackgroundColor(int rgb) {
        app.dispatch(() -> {
            if (!destroyed) {
                Gtk.setBackgroundColor(webView, rgb);
            }
        });
    }

    @Override
    public void setSize(int width, int height) {
        app.dispatch(() -> {
            if (!destroyed) {
                Gtk.windowResize(window, width, height);
            }
        });
    }

    @Override
    public void show() {
        app.dispatch(() -> {
            if (!destroyed) {
                Gtk.widgetShow(window);
                shown = true;
                visible = true;
                maximizeWhenShown = false;
                fullscreenWhenShown = false;
            }
        });
    }

    @Override
    public void hide() {
        app.dispatch(() -> {
            if (!destroyed) {
                Gtk.widgetHide(window);
                visible = false;
            }
        });
    }

    @Override
    public void minimize() {
        app.dispatch(() -> {
            if (!destroyed) {
                Gtk.windowIconify(window);
                if (DesktopEntry.onWayland()) {
                    // xdg-shell never tells a client that its window was minimized, so GDK sends no ICONIFIED state;
                    // the focus event on restore (onFocusedChanged) takes it back
                    iconified = true;
                }
            }
        });
    }

    @Override
    public boolean isMinimized() {
        return iconified;
    }

    @Override
    public boolean isFocused() {
        return focused && visible && !iconified && !destroyed;
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public void setCloseBehavior(CloseBehavior behavior) {
        closeBehavior = java.util.Objects.requireNonNull(behavior, "behavior");
    }

    @Override
    public CloseBehavior closeBehavior() {
        return closeBehavior;
    }

    // ---- FrameProtocol.Host (UI thread) ----

    @Override
    public void toggleMaximize() {
        if (destroyed || !options.resizable()) {
            return;
        }
        if (maximized) {
            Gtk.windowUnmaximize(window);
        } else {
            Gtk.windowMaximize(window);
        }
    }

    @Override
    public void beginMove() {
        if (!destroyed) {
            Gtk.windowBeginMoveDrag(window);
        }
    }

    @Override
    public void beginResize(int edge) {
        if (!destroyed && !maximized && options.resizable()) {
            // protocol edges 1 left, 2 right, 3 top, 4 top-left, 5 top-right, 6 bottom, 7 bottom-left, 8 bottom-right
            final int[] gdkEdges = {3, 4, 1, 0, 2, 6, 5, 7};
            Gtk.windowBeginResizeDrag(window, gdkEdges[edge - 1]);
        }
    }

    @Override
    public void focus() {
        app.dispatch(() -> {
            if (!destroyed) {
                Gtk.windowDeiconify(window);
                Gtk.windowPresent(window); // also shows a hidden window; on Wayland the compositor may only mark it as wanting attention
                shown = true;
                visible = true;
                maximizeWhenShown = false;
                fullscreenWhenShown = false;
            }
        });
    }

    @Override
    public void maximize() {
        app.dispatch(() -> {
            if (!destroyed && options.resizable()) {
                Gtk.windowMaximize(window);
                if (!shown) {
                    maximizeWhenShown = true;
                }
            }
        });
    }

    @Override
    public void restore() {
        app.dispatch(() -> {
            if (!destroyed) {
                maximizeWhenShown = false;
                Gtk.windowUnmaximize(window);
            }
        });
    }

    @Override
    public boolean isMaximized() {
        return maximizeWhenShown || maximized;
    }

    @Override
    public void setFullscreen(boolean on) {
        app.dispatch(() -> {
            if (destroyed) {
                return;
            }
            if (on) {
                Gtk.windowFullscreen(window); // before the window is shown this sets the state it opens in
                if (!shown) {
                    fullscreenWhenShown = true;
                }
            } else {
                fullscreenWhenShown = false;
                Gtk.windowUnfullscreen(window);
            }
        });
    }

    @Override
    public void toggleFullscreen() {
        setFullscreen(!isFullscreen());
    }

    @Override
    public boolean isFullscreen() {
        return fullscreenWhenShown || fullscreen;
    }

    /** The window-state signal: the window manager made the window full screen or took it out of it. */
    void onFullscreenChanged(boolean now) {
        fullscreen = now;
        pushFrameState();
    }

    /** The window-state signal: the window manager maximized or restored the window. */
    void onIconifiedChanged(boolean nowIconified) {
        iconified = nowIconified;
    }

    /** The window-state signal: the window gained or lost the keyboard focus. */
    void onFocusedChanged(boolean nowFocused) {
        focused = nowFocused;
        if (nowFocused) {
            iconified = false; // a minimized window cannot have the focus (Wayland sends no iconified state)
        }
        pushFrameState();
    }

    @Override
    public void reportState() {
        pushFrameState();
    }

    /** Tells a frameless page whether the window is maximized and in front. UI thread. */
    private void pushFrameState() {
        if (options.frameless() && !destroyed) {
            eval(FrameProtocol.stateScript(maximized, focused || !shown, isFullscreen())).exceptionally(t -> null);
        }
    }

    /** The close button was pressed. Returns true to keep the window (it is only hidden). */
    boolean onDeleteEvent() {
        if (closeBehavior == CloseBehavior.HIDE && !closing) {
            Gtk.widgetHide(window);
            visible = false;
            return true;
        }
        return false;
    }

    void onMaximizedChanged(boolean nowMaximized) {
        maximized = nowMaximized;
        pushFrameState();
    }

    @Override
    public void openDevTools() {
        app.dispatch(() -> {
            if (destroyed) {
                return;
            }
            if (options.devTools()) {
                Gtk.showInspector(webView);
            } else {
                LOG.log(System.Logger.Level.WARNING, "openDevTools ignored: devTools are disabled in WindowOptions");
            }
        });
    }

    // ---- menu bar, context menu, editing commands and printing (compiled but not yet run on Linux) ----

    private int menuBarHeight;

    @Override
    public void setMenuBar(Menu menu) {
        if (options.frameless()) {
            LOG.log(System.Logger.Level.WARNING, "setMenuBar ignored: a frameless window has no place for a native menu bar");
            return;
        }
        app.dispatch(() -> applyMenuBar(menu));
    }

    @Override
    public boolean supportsMenuBar() {
        return !options.frameless() && GtkMenus.available();
    }

    /** UI thread. */
    private void applyMenuBar(Menu menu) {
        if (destroyed) {
            return;
        }
        if (!GtkMenus.available()) {
            LOG.log(System.Logger.Level.WARNING, "setMenuBar ignored: native menus are not available on this system");
            return;
        }
        GtkMenus.Built old = menuBar;
        MemorySegment oldGroup = menuAccelGroup;
        int oldHeight = menuBarHeight;
        menuBar = null;
        menuAccelGroup = MemorySegment.NULL;
        menuBarHeight = 0;
        if (menu != null && !menu.items().isEmpty()) {
            if (contentBox.equals(MemorySegment.NULL)) {
                contentBox = GtkMenus.makeRoomForBar(window, webView);
            }
            menuAccelGroup = GtkMenus.newAccelGroup();
            GtkMenus.addAccelGroup(window, menuAccelGroup);
            menuBar = GtkMenus.build(menu, true, menuAccelGroup);
            menuBarHeight = GtkMenus.placeBar(contentBox, menuBar.root);
        }
        if (old != null) {
            old.destroy();
            GtkMenus.removeAccelGroup(window, oldGroup);
        }
        // The bar takes its height from the page: give it back, so the page area keeps its size
        int taken = menuBarHeight - oldHeight;
        if (taken != 0 && !maximized && !fullscreen) {
            if (shown) {
                int[] geometry = Gtk.windowGeometry(window);
                Gtk.windowResize(window, geometry[2], Math.max(1, geometry[3] + taken));
            } else {
                Gtk.windowSetDefaultSize(window, options.width(), options.height() + menuBarHeight);
            }
        }
    }

    @Override
    public void setContextMenu(ContextMenuProvider provider) {
        contextMenuProvider = provider;
    }

    @Override
    public boolean supportsContextMenu() {
        return GtkMenus.available();
    }

    /**
     * The web view's {@code context-menu} signal, which may be raised by the keyboard too ({@code event} is the GDK event or NULL).
     *
     * @return true to stop the web view from showing its own menu
     */
    boolean onContextMenu(MemorySegment event, MemorySegment hit) {
        ContextMenuProvider provider = contextMenuProvider;
        if (provider == null || destroyed || !GtkMenus.available()) {
            return !options.devTools(); // as before there were providers: no menu, unless the developer tools want theirs
        }
        try {
            String page = Gtk.webViewUri(webView);
            Menu menu = provider.menuFor(GtkMenus.describe(hit, event, page == null ? "" : page));
            if (menu != null && !menu.items().isEmpty()) {
                GtkMenus.popup(app, webView, menu, event);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "the context menu failed", e);
        }
        return true;
    }

    @Override
    public void edit(EditCommand command) {
        String name = switch (command) {
            case UNDO -> "Undo";
            case REDO -> "Redo";
            case CUT -> "Cut";
            case COPY -> "Copy";
            case PASTE -> "Paste";
            case SELECT_ALL -> "SelectAll";
        };
        app.dispatch(() -> {
            if (!destroyed && GtkMenus.available()) {
                GtkMenus.execute(webView, name);
            }
        });
    }

    @Override
    public CompletableFuture<byte[]> printToPdf(PrintOptions printOptions) {
        java.util.Objects.requireNonNull(printOptions, "options");
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        app.dispatch(() -> {
            if (destroyed) {
                result.completeExceptionally(new WindowClosedException());
                return;
            }
            inflight.add(result);
            result.whenComplete((r, e) -> inflight.remove(result));
            GtkPrinting.start(app, webView, printOptions, result);
        });
        return result;
    }

    @Override
    public SecurityPolicy security() {
        return guard.policy();
    }

    @Override
    public void setSecurity(SecurityPolicy policy) {
        guard.set(policy);
    }

    @Override
    public void onBlocked(Consumer<BlockedEvent> listener) {
        guard.onBlocked(listener);
    }

    @Override
    public void onClosed(Runnable listener) {
        closedListeners.add(listener);
    }

    @Override
    public void close() {
        app.dispatch(this::destroyNow);
    }

    // ---- UI thread ----

    /** The GTK window and web view, for tests that check what the toolkit holds. */
    MemorySegment nativeWindow() {
        return window;
    }

    MemorySegment nativeWebView() {
        return webView;
    }

    /** Destroys the native window now; its "destroy" signal then calls {@link #onDestroyed()}. */
    void destroyNow() {
        if (!destroyed) {
            closing = true;
            if (dialogs != null && dialogs.isShowing()) {
                dialogs.respond(false); // LinuxDialogs calls destroyNow again once the dialog has returned
                return;
            }
            Gtk.widgetDestroy(window);
        }
    }

    @Override
    public synchronized LinuxDialogs dialogs() {
        if (dialogs == null) {
            dialogs = new LinuxDialogs(app, this);
        }
        return dialogs;
    }

    /** Whether the native window is destroyed or about to be. */
    boolean isDestroyed() {
        return destroyed || closing;
    }

    /**
     * Navigation and new-window decisions. Returns true when the decision was made here (blocked). WebKitGTK never opens a
     * new window without the application's help, so allowing one is simply leaving the default.
     */
    boolean onDecidePolicy(MemorySegment decision, int type) {
        if (type != Gtk.POLICY_DECISION_NAVIGATION_ACTION && type != Gtk.POLICY_DECISION_NEW_WINDOW_ACTION) {
            return false; // responses and the like are not ours to decide
        }
        String uri = Gtk.decisionUri(decision);
        boolean allowed = type == Gtk.POLICY_DECISION_NEW_WINDOW_ACTION ? guard.allowNewWindow(uri) : guard.allowNavigation(uri);
        if (allowed) {
            return false;
        }
        Gtk.ignoreDecision(decision);
        return true;
    }

    boolean onPermissionRequest(MemorySegment request) {
        String uri = destroyed ? null : Gtk.webViewUri(webView);
        if (grantsMedia(request, uri)) {
            GtkMedia.allow(request);
            return true;
        }
        if (guard.allowPermission(uri)) {
            return false;
        }
        Gtk.denyPermission(request);
        return true;
    }

    /** Whether {@code request} is for the microphone/camera and the SecurityPolicy grants all of what it needs to {@code uri}. */
    private boolean grantsMedia(MemorySegment request, String uri) {
        if (GtkMedia.isDeviceInfo(request)) {
            return guard.grants(uri, Permission.MICROPHONE) || guard.grants(uri, Permission.CAMERA);
        }
        java.util.Set<Permission> needed = GtkMedia.kindsOf(request);
        if (needed == null) {
            return false;
        }
        for (Permission p : needed) {
            if (!guard.grants(uri, p)) {
                return false;
            }
        }
        return true;
    }

    // ---- files dropped on the window and dragged out of it (UI thread) ----
    //
    // WebKitGTK never tells a page where a dropped file lives, but GTK tells the web view widget: while a drag from another program is
    // over it, "drag-data-received" delivers the text/uri-list; at the drop, "drag-drop" fires. The page's own "drop" event then sends
    // the file names and the drop zone (FileDropScript), and FileDropProtocol checks the two agree.

    /** Longest uri-list read from a drag; a hostile source could offer anything. */
    private static final int MAX_URI_LIST_BYTES = 4 * 1024 * 1024;
    /** How long after the drop the page's message may take to arrive. */
    private static final long DROP_MESSAGE_WINDOW_NANOS = 10_000_000_000L;
    /** How long after the drop a late delivery of the drag data still counts as part of it. */
    private static final long LATE_DATA_NANOS = 1_000_000_000L;

    /** What the drag over the window currently offers, from another program. */
    private List<Path> offeredFiles = List.of();
    /** What was dropped, until the page's message claims it. */
    private List<Path> droppedFiles = List.of();
    private long droppedAtNanos;
    /** Files of a drag out that is under way (set between {@link #beginFileDrag} and the end of the drag), and who to tell. */
    private List<Path> dragOutFiles;
    private Consumer<Boolean> dragOutFinished;
    private boolean dragOutFailed;

    void onDragDataReceived(MemorySegment context, MemorySegment selection) {
        if (destroyed || !PageFileDrop.isEnabled(this) || !GtkDragDrop.isExternal(context)
                || !GtkDragDrop.URI_LIST.equals(GtkDragDrop.targetName(selection))) {
            return;
        }
        byte[] bytes = GtkDragDrop.data(selection, MAX_URI_LIST_BYTES);
        List<Path> files = bytes == null ? List.of() : ClipboardData.parseUriList(new String(bytes, StandardCharsets.UTF_8));
        offeredFiles = files;
        if (System.nanoTime() - droppedAtNanos < LATE_DATA_NANOS) {
            droppedFiles = files;
        }
    }

    void onDragDrop(MemorySegment context) {
        if (destroyed || !PageFileDrop.isEnabled(this)) {
            return;
        }
        // a drag that started in this program (a link dragged inside the page, our own drag out) is not a drop from the file manager
        droppedFiles = GtkDragDrop.isExternal(context) ? offeredFiles : List.of();
        droppedAtNanos = System.nanoTime();
        offeredFiles = List.of();
    }

    /** The files of the last drop, once: what the page's message is checked against. */
    private List<Path> takeDroppedFiles() {
        List<Path> files = System.nanoTime() - droppedAtNanos <= DROP_MESSAGE_WINDOW_NANOS ? droppedFiles : List.of();
        droppedFiles = List.of();
        return files;
    }

    @Override
    public boolean beginFileDrag(List<Path> files, Consumer<Boolean> finished) {
        if (destroyed || dragOutFiles != null) {
            return false;
        }
        dragOutFiles = files;
        dragOutFinished = finished;
        dragOutFailed = false;
        if (!GtkDragDrop.begin(webView)) {
            dragOutFiles = null;
            dragOutFinished = null;
            return false;
        }
        return true;
    }

    void onDragDataGet(MemorySegment selection) {
        List<Path> files = dragOutFiles;
        if (files != null) { // otherwise it is one of WebKit's own drags
            GtkDragDrop.setUriList(selection, ClipboardData.uriList(files).getBytes(StandardCharsets.UTF_8));
        }
    }

    void onDragFailed() {
        if (dragOutFiles != null) {
            dragOutFailed = true;
        }
    }

    void onDragEnd() {
        finishDragOut(!dragOutFailed);
    }

    private void finishDragOut(boolean dropped) {
        Consumer<Boolean> finished = dragOutFinished;
        boolean ours = dragOutFiles != null;
        dragOutFiles = null;
        dragOutFinished = null;
        dragOutFailed = false;
        if (ours && finished != null) {
            finished.accept(dropped);
        }
    }

    void onScriptMessage(String text) {
        if (text == null || destroyed) {
            return;
        }
        // The page that sent it. WebKitGTK does not say which frame, so the top-level page stands in; with navigation
        // locked, no untrusted frame can be inside a trusted page.
        String source = Gtk.webViewUri(webView);
        if (!guard.allowMessageFrom(source)) {
            return;
        }
        if (FrameProtocol.isFrameMessage(text)) {
            if (options.frameless()) {
                FrameProtocol.handle(text, this);
            }
            return; // never reaches the application, frameless or not
        }
        if (FileDropProtocol.isDropMessage(text)) {
            PageFileDrop.deliver(this, text, takeDroppedFiles());
            return; // never reaches the application's message listeners
        }
        if (FileDragProtocol.isDragMessage(text)) {
            PageFileDrag.handle(this, text);
            return;
        }
        if (PageRpc.isRpcMessage(text)) {
            PageRpc.deliver(this, text, source);
            return; // never reaches the application's message listeners
        }
        for (Consumer<String> listener : messageListeners) {
            try {
                listener.accept(text);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "message listener failed", e);
            }
        }
    }

    /**
     * A request for the app's own scheme. WebKitGTK asks on the UI thread and does not wait, so the answer is produced on a worker
     * thread (the handler may be slow) and put back here.
     */
    void onSchemeRequest(MemorySegment request) {
        ResourceService service = resourceService;
        if (service == null) {
            GtkResources.respond(request, 404, ResourceService.baseHeaders(), new byte[0]);
            return;
        }
        GtkResources.Incoming incoming = GtkResources.read(request, service.maxBody());
        int refused = incoming.body() == null ? 413 : service.admit(incoming.method(), incoming.headers(), GtkResources.ORIGIN);
        if (refused != 0) {
            GtkResources.respond(request, refused, ResourceService.baseHeaders(), new byte[0]);
            return;
        }
        GtkResources.ref(request); // the request must outlive this callback
        boolean head = incoming.method().equals("HEAD");
        service.serveAsyncFollowingRedirects(incoming.method(), incoming.uri(), incoming.headers(), incoming.body())
                .thenAccept(response -> app.dispatch(() -> {
                    try {
                        if (!destroyed) {
                            GtkResources.respond(request, response.status(), response.headers(), head ? new byte[0] : response.body());
                        }
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.ERROR, "answering a resource request failed", e);
                    } finally {
                        Gtk.unref(request);
                    }
                }));
    }

    void onDestroyed() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        finishDragOut(false); // a drag out that never got to end
        if (progressShown) {
            app.launcherProgress().set(io.github.b077as.jolt.TaskbarProgress.none()); // a bar left by a window that is gone
        }
        WindowClosedException closed = new WindowClosedException();
        for (CompletableFuture<?> future : new ArrayList<>(inflight)) {
            future.completeExceptionally(closed);
        }
        inflight.clear();
        ready.completeExceptionally(closed); // no-op if already completed

        if (menuBar != null) {
            menuBar.release(); // the widgets go with the window; only the callbacks have to be forgotten
            menuBar = null;
        }
        Gtk.unref(contentManager); // the web view holds its own reference and is destroyed with the window
        contentManager = webView = window = MemorySegment.NULL;
        app.windowDestroyed(id);
        for (Runnable listener : closedListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "onClosed listener failed", e);
            }
        }
    }
}
