package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.BlockedEvent;
import io.github.b077as.jolt.ColorScheme;
import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.SecurityPolicy;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowClosedException;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.internal.BridgeScript;
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
final class LinuxWindow implements WebWindow {

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

        Gtk.containerAdd(window, webView);
        Gtk.widgetShow(webView);
        if (options.visible()) {
            Gtk.widgetShow(window);
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
