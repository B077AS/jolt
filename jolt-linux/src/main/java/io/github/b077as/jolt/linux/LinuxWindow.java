package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.BlockedEvent;
import io.github.b077as.jolt.ColorScheme;
import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.SecurityPolicy;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowClosedException;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.internal.BlobBodyShim;
import io.github.b077as.jolt.internal.BridgeScript;
import io.github.b077as.jolt.internal.NativeResourceHost;
import io.github.b077as.jolt.internal.ResourceService;
import io.github.b077as.jolt.internal.SecurityGuard;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashSet;
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
final class LinuxWindow implements WebWindow, NativeResourceHost {

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
    /** Whether the window has been shown at least once (UI thread). */
    private boolean shown;
    /** Asked to be maximized before it was first shown. Readable from any thread. */
    private volatile boolean maximizeWhenShown;
    /** The window manager's word on it, kept up to date by the window-state signal. Readable from any thread. */
    private volatile boolean maximized;
    private ResourceService resourceService;

    /** Must be called on the UI thread. */
    LinuxWindow(LinuxApplication app, WindowOptions options) {
        this.app = app;
        this.options = options;
        this.guard = new SecurityGuard(options.security(), app::dispatch);

        window = Gtk.windowNew();
        Gtk.windowSetTitle(window, options.title());
        Gtk.windowSetDefaultSize(window, options.width(), options.height());

        contentManager = Gtk.newUserContentManager();
        if (!Gtk.registerScriptMessageHandler(contentManager, MESSAGE_HANDLER)) {
            throw new JoltException("Could not register the script message handler");
        }
        boolean subframes = options.security().bridgeInSubframes();
        Gtk.addUserScript(contentManager,
                BridgeScript.build("window.webkit.messageHandlers." + MESSAGE_HANDLER + ".postMessage(m)", subframes),
                subframes ? Gtk.USER_CONTENT_INJECT_ALL_FRAMES : Gtk.USER_CONTENT_INJECT_TOP_FRAME);
        webView = Gtk.newWebView(app.webContext(), contentManager);
        Gtk.setDeveloperExtras(webView, options.devTools());

        this.id = app.register(this);
        MemorySegment key = MemorySegment.ofAddress(id);
        Gtk.connect(window, "destroy", Callbacks.DESTROY, key);
        Gtk.connect(window, "window-state-event", Callbacks.WINDOW_STATE, key);
        Gtk.connect(contentManager, "script-message-received::" + MESSAGE_HANDLER, Callbacks.SCRIPT_MESSAGE, key);
        Gtk.connect(webView, "close", Callbacks.WEB_VIEW_CLOSE, key);
        Gtk.connect(webView, "decide-policy", Callbacks.DECIDE_POLICY, key);
        Gtk.connect(webView, "permission-request", Callbacks.PERMISSION_REQUEST, key);
        if (!options.devTools()) {
            Gtk.connect(webView, "context-menu", Callbacks.CONTEXT_MENU, key);
        }

        // Appearance first, so the window never shows in the wrong colours or without its icon.
        if (options.titleBarScheme() != null) {
            Gtk.setPreferDarkTheme(options.titleBarScheme() == ColorScheme.DARK);
        }
        if (options.icon() != null && !Gtk.setWindowIcon(window, options.icon())) {
            LOG.log(System.Logger.Level.WARNING, "The window icon could not be decoded; it must be PNG or ICO data");
        }
        if (options.backgroundRgb() != null) {
            Gtk.setBackgroundColor(webView, options.backgroundRgb());
        }

        if (options.minWidth() > 0 || options.minHeight() > 0) {
            Gtk.windowSetMinSize(window, options.minWidth(), options.minHeight());
        }

        Gtk.containerAdd(window, webView);
        Gtk.widgetShow(webView);
        if (options.maximized()) {
            Gtk.windowMaximize(window); // before the window is shown this sets the state it opens in
            maximizeWhenShown = true;
        } else if (options.centered()) {
            Gtk.windowSetPosition(window, Gtk.WIN_POS_CENTER);
        }
        if (options.visible()) {
            Gtk.widgetShow(window);
            shown = true;
            maximizeWhenShown = false; // from here the window manager's state counts
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
            Gtk.loadUri(webView, GtkResources.ORIGIN + "/");
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
                maximizeWhenShown = false;
            }
        });
    }

    @Override
    public void hide() {
        app.dispatch(() -> {
            if (!destroyed) {
                Gtk.widgetHide(window);
            }
        });
    }

    @Override
    public void maximize() {
        app.dispatch(() -> {
            if (!destroyed) {
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

    /** The window-state signal: the window manager maximized or restored the window. */
    void onMaximizedChanged(boolean nowMaximized) {
        maximized = nowMaximized;
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
            Gtk.widgetDestroy(window);
        }
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
        if (guard.allowPermission(destroyed ? null : Gtk.webViewUri(webView))) {
            return false;
        }
        Gtk.denyPermission(request);
        return true;
    }

    void onScriptMessage(String text) {
        if (text == null || destroyed) {
            return;
        }
        // The page that sent it. WebKitGTK does not say which frame, so the top-level page stands in; with navigation
        // locked, no untrusted frame can be inside a trusted page.
        if (!guard.allowMessageFrom(Gtk.webViewUri(webView))) {
            return;
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
        WindowClosedException closed = new WindowClosedException();
        for (CompletableFuture<?> future : new ArrayList<>(inflight)) {
            future.completeExceptionally(closed);
        }
        inflight.clear();
        ready.completeExceptionally(closed); // no-op if already completed

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
