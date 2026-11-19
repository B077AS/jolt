package io.github.b077as.jolt.win32;

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
import io.github.b077as.jolt.Shortcut;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowClosedException;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.internal.BridgeScript;
import io.github.b077as.jolt.internal.FileDragProtocol;
import io.github.b077as.jolt.internal.FileDropProtocol;
import io.github.b077as.jolt.internal.FrameProtocol;
import io.github.b077as.jolt.internal.FrameScript;
import io.github.b077as.jolt.internal.Icons;
import io.github.b077as.jolt.internal.NativeDialogHost;
import io.github.b077as.jolt.internal.NativeFileDragHost;
import io.github.b077as.jolt.internal.NativeResourceHost;
import io.github.b077as.jolt.internal.PageFileDrag;
import io.github.b077as.jolt.internal.PageRpc;
import io.github.b077as.jolt.internal.PageFileDrop;
import io.github.b077as.jolt.internal.ResourceService;
import io.github.b077as.jolt.internal.SecurityGuard;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A top-level Win32 window with a WebView2 controller filling its client area.
 *
 * <p>Public methods may be called from any thread; they hop to the UI thread. Everything below the
 * "UI thread" marker must only run there.
 */
final class WindowsWindow implements WebWindow, NativeResourceHost, NativeDialogHost, NativeFileDragHost, FrameProtocol.Host {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.win32");
    private static final int STYLE = Win32.WS_OVERLAPPEDWINDOW | Win32.WS_CLIPCHILDREN | Win32.WS_CLIPSIBLINGS;
    /**
     * No caption: the page draws the title bar. The sizing frame is kept so Windows still snaps, animates and shades the window;
     * WM_NCCALCSIZE gives the whole window to the web view, so the frame itself is never visible.
     */
    private static final int FRAMELESS_STYLE = Win32.WS_THICKFRAME | Win32.WS_SYSMENU | Win32.WS_MINIMIZEBOX | Win32.WS_MAXIMIZEBOX
            | Win32.WS_CLIPCHILDREN | Win32.WS_CLIPSIBLINGS;
    private static final int MOVE_FOCUS_PROGRAMMATIC = 0;
    /** Requests to this origin are answered from memory and never reach the network. */
    private static final String VIRTUAL_ORIGIN = SecurityPolicy.APP_ORIGIN;
    private static final int PERMISSION_STATE_ALLOW = 1; // COREWEBVIEW2_PERMISSION_STATE_ALLOW
    private static final int PERMISSION_STATE_DENY = 2; // COREWEBVIEW2_PERMISSION_STATE_DENY
    private static final int RESOURCE_CONTEXT_ALL = 0; // COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL

    private final WindowsApplication app;
    private final WindowOptions options;
    private final int style;
    private volatile CloseBehavior closeBehavior = CloseBehavior.CLOSE;
    private final SecurityGuard guard;
    private final MemorySegment hwnd;
    private final WindowsTaskbarButtons taskbarButtons;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CopyOnWriteArrayList<Consumer<String>> messageListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> closedListeners = new CopyOnWriteArrayList<>();

    // UI thread only
    private final List<Runnable> pending = new ArrayList<>();
    private final Set<CompletableFuture<?>> inflight = new HashSet<>();
    private final List<ComHandler> handlers = new ArrayList<>();
    private MemorySegment controller = MemorySegment.NULL;
    private MemorySegment webView = MemorySegment.NULL;
    private MemorySegment environment = MemorySegment.NULL; // borrowed from the application, which outlives the window
    private final Map<String, byte[]> served = new HashMap<>();
    private boolean resourceInterceptionInstalled;
    private ResourceService resourceService;
    private long documentCounter;
    private MemorySegment bigIcon = MemorySegment.NULL;
    private MemorySegment smallIcon = MemorySegment.NULL;
    private boolean webViewReady;
    private boolean destroyed;
    /** The menu bar on the window, or null. UI thread. */
    private WindowsMenus.Built menuBar;
    private volatile ContextMenuProvider contextMenuProvider;
    private boolean contextMenuHooked;
    /** A context menu is open (its own message loop is running). UI thread. */
    private boolean contextMenuOpen;
    /** {@link #destroyNow} has started; the window goes as soon as an open dialog has returned. */
    private volatile boolean closing;
    /** Fills the window behind the web view, so what shows before the web view has painted is the page's colour, not white. */
    private MemorySegment backgroundBrush = MemorySegment.NULL;
    /** The window was asked to be maximized while hidden (or at creation): it is shown maximized. Readable from any thread. */
    private volatile boolean maximizeOnFirstShow;
    /** Frameless pages are told when this changes. Starts true: a window that never gets an activation message is not dimmed. */
    private boolean active = true;
    private boolean sentMaximized;
    private volatile boolean fullscreen;
    private boolean fullscreenWasMaximized;
    private long fullscreenStyle;
    private int[] fullscreenRestore;
    /** Windows 11 (the system rounds frameless windows and shows snap layouts); decided when the window is created. */
    private boolean snapLayouts;
    private MemorySegment snapOverlay = MemorySegment.NULL;
    private volatile int[] overlayRect;
    private boolean overlayHover;
    private boolean overlayPressed;
    /** Off in tests that send the overlay pointer messages by hand: the real pointer is not over it, so Windows would end the hover at once. */
    boolean trackPointerLeave = true;

    /** Must be called on the UI thread. */
    WindowsWindow(WindowsApplication app, WindowOptions options) {
        this.app = app;
        this.options = options;
        int baseStyle = options.frameless() ? FRAMELESS_STYLE : STYLE;
        // A window the user cannot resize has no maximize box, and (unless it is frameless, which needs the frame for its shadow and
        // draws no visible border anyway) no sizing border.
        this.style = options.resizable() ? baseStyle
                : baseStyle & ~(options.frameless() ? Win32.WS_MAXIMIZEBOX : Win32.WS_MAXIMIZEBOX | Win32.WS_THICKFRAME);
        this.guard = new SecurityGuard(options.security(), app::dispatch);

        // An owner (not a parent: the style is not WS_CHILD) keeps this window in front of it, minimizes with it and takes it
        // down when it is destroyed. A closed owner, or one from another application, is ignored.
        MemorySegment ownerHandle = options.owner() instanceof WindowsWindow o && o.app == app && !o.destroyed
                ? o.hwnd : MemorySegment.NULL;
        int[] error = new int[1];
        try (Arena arena = Arena.ofConfined()) {
            this.hwnd = Win32.createWindow(0, app.className(), Native.allocWide(arena, options.title()), style,
                    Win32.CW_USEDEFAULT, Win32.CW_USEDEFAULT, Win32.CW_USEDEFAULT, Win32.CW_USEDEFAULT,
                    ownerHandle, app.hInstance(), error);
        }
        if (hwnd.equals(MemorySegment.NULL)) {
            throw new JoltException("CreateWindowExW failed: Win32 error " + error[0]);
        }
        app.register(hwnd, this);
        taskbarButtons = new WindowsTaskbarButtons(hwnd);
        if (options.frameless()) {
            // The first WM_NCCALCSIZE came before this window was known to the application; ask for it again now.
            Win32.setWindowPos(hwnd, 0, 0, 0, 0, Win32.SWP_FRAMECHANGED | Win32.SWP_NOMOVE | Win32.SWP_NOSIZE
                    | Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE);
            Win32.extendFrameIntoClientArea(hwnd, 0, 0, 1, 0); // a one-pixel frame is what makes Windows draw the shadow
            // Only Windows 11 knows this attribute, so whether it is accepted also says whether snap layouts exist.
            snapLayouts = Win32.dwmSetAttribute(hwnd, Win32.DWMWA_WINDOW_CORNER_PREFERENCE, Win32.DWMWCP_ROUND) >= 0;
        }
        // Appearance is applied before the window is first shown, so it never flashes the wrong title bar colour.
        if (options.titleBarScheme() != null) {
            applyTitleBarScheme(options.titleBarScheme());
        }
        if (options.icon() != null) {
            applyIcon(options.icon());
        }
        if (options.backgroundRgb() != null) {
            setBackgroundBrush(options.backgroundRgb()); // painted behind the web view, so it never starts out white
        }
        applyClientSize(options.width(), options.height());
        boolean startMaximized = options.maximized() && options.resizable();
        if (options.centered() && !startMaximized) {
            center();
        }
        maximizeOnFirstShow = startMaximized;
        if (options.visible()) {
            display();
        }
        initWebView();
    }

    // ---- WebWindow (any thread) ----

    @Override
    public CompletableFuture<Void> ready() {
        return ready;
    }

    @Override
    public void loadUrl(String url) {
        whenReady(() -> navigate(WebView2Abi.ICoreWebView2.NAVIGATE, url));
    }

    @Override
    public void loadHtml(String html) {
        // Always served from the virtual origin, so the page has a real origin that the security policy can recognise.
        whenReady(() -> serveAndNavigate(html));
    }

/**
 * Serves the app's files inside the web view: requests under the virtual origin are answered from {@code service} and never
 * reach the network. The origin is {@link SecurityPolicy#APP_ORIGIN}, which is always trusted, so loadHtml pages and the app
 * share it (and its storage).
 */
@Override
public boolean loadResources(ResourceService service) {
    whenReady(() -> {
        resourceService = service;
        installResourceInterception();
        navigate(WebView2Abi.ICoreWebView2.NAVIGATE, VIRTUAL_ORIGIN + service.startPath());
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
            onceReady(() -> executeScript(script, result));
        });
        return result;
    }

    @Override
    public void addInitScript(String script) {
        whenReady(() -> addScriptOnDocumentCreated(script));
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
                try (Arena arena = Arena.ofConfined()) {
                    Win32.setWindowText(hwnd, Native.allocWide(arena, title));
                }
            }
        });
    }

    @Override
    public void setIcon(byte[] png) {
        byte[] copy = png == null ? null : png.clone();
        app.dispatch(() -> {
            if (!destroyed && copy != null) {
                applyIcon(copy);
            }
        });
    }

    @Override
    public void setTitleBarScheme(ColorScheme scheme) {
        app.dispatch(() -> {
            if (!destroyed && scheme != null) {
                applyTitleBarScheme(scheme);
            }
        });
    }

    @Override
    public void setTitleBarColors(int backgroundRgb, int textRgb) {
        app.dispatch(() -> {
            if (!destroyed) {
                // Windows 11 only: earlier versions reject these attributes, which is fine, so just note it.
                int background = colorRef(backgroundRgb);
                boolean supported = Win32.dwmSetAttribute(hwnd, Win32.DWMWA_CAPTION_COLOR, background) >= 0;
                Win32.dwmSetAttribute(hwnd, Win32.DWMWA_BORDER_COLOR, background);
                Win32.dwmSetAttribute(hwnd, Win32.DWMWA_TEXT_COLOR, colorRef(textRgb));
                if (!supported) {
                    LOG.log(System.Logger.Level.DEBUG, "Custom title bar colours need Windows 11; ignored");
                }
                redrawFrame();
            }
        });
    }

    @Override
    public void setBackgroundColor(int rgb) {
        app.dispatch(() -> {
            if (!destroyed) {
                setBackgroundBrush(rgb);
            }
        });
        whenReady(() -> applyBackground(rgb));
    }

    @Override
    public void setSize(int width, int height) {
        app.dispatch(() -> {
            if (!destroyed) {
                applyClientSize(width, height);
            }
        });
    }

    @Override
    public void show() {
        app.dispatch(() -> {
            if (!destroyed) {
                display();
            }
        });
    }

    @Override
    public void hide() {
        app.dispatch(() -> {
            if (!destroyed) {
                Win32.showWindow(hwnd, Win32.SW_HIDE);
            }
        });
    }

    @Override
    public void minimize() {
        app.dispatch(() -> {
            if (!destroyed) {
                Win32.showWindow(hwnd, Win32.SW_MINIMIZE);
            }
        });
    }

    @Override
    public boolean isMinimized() {
        return !destroyed && Win32.isIconic(hwnd);
    }

    @Override
    public boolean isFocused() {
        return !destroyed && Win32.isForeground(hwnd) && Win32.isWindowVisible(hwnd) && !Win32.isIconic(hwnd);
    }

    @Override
    public boolean isVisible() {
        return !destroyed && Win32.isWindowVisible(hwnd);
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
        Win32.showWindow(hwnd, Win32.isZoomed(hwnd) ? Win32.SW_RESTORE : Win32.SW_SHOWMAXIMIZED);
    }

    @Override
    public void beginMove() {
        beginNonClientDrag(Win32.HTCAPTION);
    }

    @Override
    public void beginResize(int edge) {
        if (!options.resizable()) {
            return;
        }
        beginNonClientDrag(9 + edge); // edges 1 (left) .. 8 (bottom-right) are HTLEFT (10) .. HTBOTTOMRIGHT (17) minus 9
    }

    @Override
    public void reportState() {
        pushFrameState();
    }

    /** Tells a frameless page whether the window is maximized and in front. UI thread. */
    private void pushFrameState() {
        if (destroyed || !options.frameless() || !webViewReady) {
            return;
        }
        boolean maximized = Win32.isZoomed(hwnd);
        sentMaximized = maximized;
        eval(FrameProtocol.stateScript(maximized, active, fullscreen)).exceptionally(t -> null);
    }

    // ---- full screen ----

    @Override
    public void setFullscreen(boolean on) {
        app.dispatch(() -> {
            if (!destroyed) {
                applyFullscreen(on);
            }
        });
    }

    @Override
    public void toggleFullscreen() {
        setFullscreen(!fullscreen);
    }

    @Override
    public boolean isFullscreen() {
        return fullscreen;
    }

    /**
     * Full screen is a window with no caption and no sizing frame placed over the whole monitor (the taskbar included), so
     * Windows hides the taskbar for it. What the window looked like is kept and put back. UI thread.
     */
    private void applyFullscreen(boolean on) {
        if (on == fullscreen) {
            return;
        }
        if (on) {
            fullscreenWasMaximized = maximizeOnFirstShow || Win32.isZoomed(hwnd);
            maximizeOnFirstShow = false;
            if (Win32.isZoomed(hwnd)) {
                Win32.showWindow(hwnd, Win32.SW_RESTORE); // so the rectangle saved below is the normal one
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment rect = arena.allocate(Win32.RECT);
                Win32.getWindowRect(hwnd, rect);
                fullscreenRestore = new int[] {rect.get(JAVA_INT, 0), rect.get(JAVA_INT, 4),
                        rect.get(JAVA_INT, 8) - rect.get(JAVA_INT, 0), rect.get(JAVA_INT, 12) - rect.get(JAVA_INT, 4)};
            }
            int[] monitor = Win32.monitorRectOf(hwnd);
            if (monitor == null) {
                return;
            }
            fullscreenStyle = Win32.windowStyle(hwnd);
            fullscreen = true; // before the size changes: WM_GETMINMAXINFO reads it
            if (!options.frameless()) {
                Win32.setWindowStyle(hwnd, fullscreenStyle & ~(long) (Win32.WS_CAPTION | Win32.WS_THICKFRAME));
            }
            Win32.dwmSetAttribute(hwnd, Win32.DWMWA_WINDOW_CORNER_PREFERENCE, Win32.DWMWCP_DONOTROUND);
            Win32.placeOnTop(hwnd, monitor[0], monitor[1], monitor[2] - monitor[0], monitor[3] - monitor[1]);
        } else {
            fullscreen = false;
            if (!options.frameless()) {
                Win32.setWindowStyle(hwnd, fullscreenStyle);
            }
            Win32.dwmSetAttribute(hwnd, Win32.DWMWA_WINDOW_CORNER_PREFERENCE, options.frameless() && snapLayouts ? Win32.DWMWCP_ROUND : 0);
            int[] at = fullscreenRestore;
            Win32.placeOnTop(hwnd, at[0], at[1], at[2], at[3]);
            if (fullscreenWasMaximized) {
                if (Win32.isWindowVisible(hwnd)) {
                    Win32.showWindow(hwnd, Win32.SW_SHOWMAXIMIZED);
                } else {
                    maximizeOnFirstShow = true;
                }
            }
        }
        resizeWebView();
        pushFrameState();
    }

    @Override
    public void setMaximizeButton(int x, int y, int width, int height) {
        if (destroyed || !snapLayouts || !options.resizable()) {
            return;
        }
        if (snapOverlay.equals(MemorySegment.NULL)) {
            int[] error = new int[1];
            // A nearly invisible child window above the web view. Windows asks the window under the pointer what part of the window
            // it is; answering "maximize button" is what makes Windows 11 show its snap layouts. The web view's own window cannot
            // answer, so this one does, and it feeds hover and press back to the page.
            snapOverlay = Win32.createWindow(Win32.WS_EX_LAYERED, app.className(), MemorySegment.NULL,
                    Win32.WS_CHILD | Win32.WS_CLIPSIBLINGS, 0, 0, 1, 1, hwnd, app.hInstance(), error);
            if (snapOverlay.equals(MemorySegment.NULL)) {
                LOG.log(System.Logger.Level.WARNING, "snap-layout overlay not created: Win32 error " + error[0]);
                snapLayouts = false;
                return;
            }
            app.registerOverlay(snapOverlay, this);
            Win32.setLayeredAlpha(snapOverlay, 1);
        }
        overlayRect = new int[] {x, y, width, height};
        Win32.raiseAndShow(snapOverlay, x, y, width, height);
    }

    @Override
    public void clearMaximizeButton() {
        if (destroyed || snapOverlay.equals(MemorySegment.NULL)) {
            return;
        }
        overlayRect = null;
        overlayHover = false;
        overlayPressed = false;
        Win32.showWindow(snapOverlay, Win32.SW_HIDE);
    }

    /** The snap-layout overlay's rectangle in client pixels, or {@code null} while there is none. For tests. */
    int[] snapOverlayRect() {
        return overlayRect == null ? null : overlayRect.clone();
    }

    MemorySegment snapOverlay() {
        return snapOverlay;
    }

    /** Whether this system shows snap layouts (Windows 11) and the window may use them. For tests. */
    boolean snapLayoutsAvailable() {
        return snapLayouts;
    }

    /** Messages for the overlay child window. UI thread. */
    Long handleOverlayMessage(MemorySegment overlay, int msg, long wParam, long lParam) {
        switch (msg) {
            case Win32.WM_NCHITTEST -> {
                return (long) Win32.HTMAXBUTTON;
            }
            case Win32.WM_ERASEBKGND -> {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment rect = arena.allocate(Win32.RECT);
                    Win32.getClientRect(overlay, rect);
                    MemorySegment black = Win32.createSolidBrush(0);
                    Win32.fillRect(MemorySegment.ofAddress(wParam), rect, black);
                    Win32.deleteObject(black);
                }
                return 1L;
            }
            case Win32.WM_NCMOUSEMOVE -> {
                if (!overlayHover) {
                    overlayHover = true;
                    if (trackPointerLeave) {
                        Win32.trackNonClientLeave(overlay);
                    }
                    pushHover();
                }
                return 0L;
            }
            case Win32.WM_NCMOUSELEAVE -> {
                overlayHover = false;
                overlayPressed = false;
                pushHover();
                return 0L;
            }
            case Win32.WM_NCLBUTTONDOWN, Win32.WM_NCLBUTTONDBLCLK -> {
                overlayHover = true;
                overlayPressed = true;
                pushHover();
                return 0L;
            }
            case Win32.WM_NCLBUTTONUP -> {
                boolean click = overlayPressed;
                overlayPressed = false;
                pushHover();
                if (click) {
                    toggleMaximize();
                }
                return 0L;
            }
            default -> {
                return null;
            }
        }
    }

    private void pushHover() {
        if (!destroyed && webViewReady) {
            eval(FrameProtocol.hoverScript(overlayHover ? "max" : null, overlayPressed)).exceptionally(t -> null);
        }
    }

    @Override
    public void showWindowMenu() {
        app.dispatch(() -> {
            if (destroyed) {
                return;
            }
            MemorySegment menu = Win32.systemMenu(hwnd);
            if (menu.equals(MemorySegment.NULL)) {
                return;
            }
            boolean zoomed = Win32.isZoomed(hwnd);
            Win32.enableSystemMenuItem(menu, Win32.SC_RESTORE, zoomed);
            Win32.enableSystemMenuItem(menu, Win32.SC_MOVE, !zoomed);
            Win32.enableSystemMenuItem(menu, Win32.SC_SIZE, !zoomed && options.resizable());
            Win32.enableSystemMenuItem(menu, Win32.SC_MAXIMIZE, !zoomed && options.resizable());
            Win32.enableSystemMenuItem(menu, Win32.SC_MINIMIZE, true);
            Win32.setForegroundWindow(hwnd); // so the menu closes when the user clicks elsewhere
            int[] at = Win32.cursorPosition();
            int command = Win32.trackMenu(menu, hwnd, at[0], at[1]);
            if (command != 0 && !destroyed) {
                Win32.postMessage(hwnd, Win32.WM_SYSCOMMAND, command, 0);
            }
        });
    }

    /** Hands the pointer, which is down, to Windows as if it had pressed a caption or a sizing border. */
    private void beginNonClientDrag(int hitTest) {
        if (destroyed || Win32.isZoomed(hwnd) && hitTest != Win32.HTCAPTION) {
            return;
        }
        int[] cursor = Win32.cursorPosition();
        Win32.releaseCapture();
        Win32.postMessage(hwnd, Win32.WM_NCLBUTTONDOWN, hitTest, ((long) (cursor[1] & 0xFFFF) << 16) | (cursor[0] & 0xFFFF));
    }

    @Override
    public void focus() {
        app.dispatch(() -> {
            if (destroyed) {
                return;
            }
            if (!Win32.isWindowVisible(hwnd)) {
                display();
            } else if (Win32.isIconic(hwnd)) {
                Win32.showWindow(hwnd, Win32.SW_RESTORE);
            }
            Win32.setForegroundWindow(hwnd);
        });
    }

    @Override
    public void maximize() {
        app.dispatch(() -> {
            if (destroyed || !options.resizable()) {
                return;
            }
            if (Win32.isWindowVisible(hwnd)) {
                Win32.showWindow(hwnd, Win32.SW_SHOWMAXIMIZED); // same value as SW_MAXIMIZE
            } else {
                maximizeOnFirstShow = true; // maximizing a hidden window must not make it appear
            }
        });
    }

    @Override
    public void restore() {
        app.dispatch(() -> {
            if (destroyed) {
                return;
            }
            maximizeOnFirstShow = false;
            if (Win32.isWindowVisible(hwnd)) {
                Win32.showWindow(hwnd, Win32.SW_RESTORE);
            }
        });
    }

    @Override
    public boolean isMaximized() {
        return maximizeOnFirstShow || Win32.isZoomed(hwnd);
    }

    @Override
    public void openDevTools() {
        whenReady(() -> {
            if (options.devTools()) {
                Com.callChecked("OpenDevToolsWindow", webView, WebView2Abi.ICoreWebView2.OPEN_DEV_TOOLS_WINDOW, Com.HR_VOID);
            } else {
                LOG.log(System.Logger.Level.WARNING, "openDevTools ignored: devTools are disabled in WindowOptions");
            }
        });
    }

    // ---- menu bar, context menu, editing commands and printing ----

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
        return !options.frameless();
    }

    /** UI thread. */
    private void applyMenuBar(Menu menu) {
        if (destroyed) {
            return;
        }
        WindowsMenus.Built built = menu == null || menu.items().isEmpty() ? null : WindowsMenus.build(menu, true);
        int before = clientHeight();
        MemorySegment old = Win32.getMenu(hwnd);
        Win32.setMenu(hwnd, built == null ? MemorySegment.NULL : built.handle);
        Win32.drawMenuBar(hwnd);
        if (!old.equals(MemorySegment.NULL)) {
            Win32.destroyMenu(old);
        }
        menuBar = built;
        // The bar takes (or gives back) part of the page area; the window grows or shrinks so the page keeps its size
        int taken = before - clientHeight();
        if (taken != 0 && !fullscreen && !Win32.isZoomed(hwnd) && !Win32.isIconic(hwnd)) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment rect = arena.allocate(Win32.RECT);
                Win32.getWindowRect(hwnd, rect);
                Win32.setWindowPos(hwnd, 0, 0, rect.get(JAVA_INT, 8) - rect.get(JAVA_INT, 0),
                        rect.get(JAVA_INT, 12) - rect.get(JAVA_INT, 4) + taken, Win32.SWP_NOMOVE | Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE);
            }
        }
        resizeWebView();
    }

    private int clientHeight() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rect = arena.allocate(Win32.RECT);
            Win32.getClientRect(hwnd, rect);
            return rect.get(JAVA_INT, 12) - rect.get(JAVA_INT, 4);
        }
    }

    /** A key press in the web view that may be one of the menu bar's accelerators. Runs inside the web view's event: it only decides and queues. */
    private void onAcceleratorKey(MemorySegment sender, MemorySegment args) {
        WindowsMenus.Built bar = menuBar;
        if (bar == null || !bar.hasAccelerators()) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_INT);
            if (Com.call(args, WebView2Abi.ICoreWebView2AcceleratorKeyPressedEventArgs.GET_KEY_EVENT_KIND, Com.HR_PTR, out) < 0) {
                return;
            }
            int kind = out.get(JAVA_INT, 0);
            if (kind != KEY_EVENT_KEY_DOWN && kind != KEY_EVENT_SYSTEM_KEY_DOWN) {
                return;
            }
            if (Com.call(args, WebView2Abi.ICoreWebView2AcceleratorKeyPressedEventArgs.GET_VIRTUAL_KEY, Com.HR_PTR, out) < 0) {
                return;
            }
            Shortcut shortcut = WindowsKeys.shortcutFor(out.get(JAVA_INT, 0), Win32.isKeyDown(WindowsKeys.VK_CONTROL),
                    Win32.isKeyDown(WindowsKeys.VK_SHIFT), Win32.isKeyDown(WindowsKeys.VK_MENU),
                    Win32.isKeyDown(WindowsKeys.VK_LWIN) || Win32.isKeyDown(WindowsKeys.VK_RWIN));
            Integer commandId = shortcut == null ? null : bar.commandFor(shortcut);
            if (commandId == null) {
                return;
            }
            Com.call(args, WebView2Abi.ICoreWebView2AcceleratorKeyPressedEventArgs.PUT_HANDLED, Com.HR_INT, 1); // the page never sees it
            boolean repeat = Com.call(args, WebView2Abi.ICoreWebView2AcceleratorKeyPressedEventArgs.GET_KEY_EVENT_LPARAM, Com.HR_PTR, out) >= 0
                    && (out.get(JAVA_INT, 0) >> 30 & 1) != 0; // bit 30: the key was already down, so this is auto-repeat
            if (!repeat) {
                int id = commandId;
                app.dispatch(() -> {
                    if (menuBar == bar) {
                        bar.activate(id, true);
                    }
                });
            }
        }
    }

    @Override
    public void setContextMenu(ContextMenuProvider provider) {
        contextMenuProvider = provider;
        whenReady(() -> {
            if (provider != null && !contextMenuHooked) {
                hookContextMenu();
            }
            applyDefaultContextMenus();
        });
    }

    @Override
    public boolean supportsContextMenu() {
        return true;
    }

    /**
     * The web view raises {@code ContextMenuRequested} only while its default context menus are enabled, so they are switched on while
     * a provider is installed (our handler then always takes over, and the web view shows nothing itself) and go back to what
     * {@link WindowOptions#devTools()} says when it is removed. UI thread.
     */
    private void applyDefaultContextMenus() {
        boolean enabled = options.devTools() || contextMenuProvider != null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            Com.callChecked("get_Settings", webView, WebView2Abi.ICoreWebView2.GET_SETTINGS, Com.HR_PTR, out);
            MemorySegment settings = Native.readPointer(out);
            try {
                Com.call(settings, WebView2Abi.ICoreWebView2Settings.PUT_ARE_DEFAULT_CONTEXT_MENUS_ENABLED, Com.HR_INT, enabled ? 1 : 0);
            } finally {
                Com.release(settings);
            }
        }
    }

    /** UI thread, after the web view is ready. The handler stays installed; it does nothing while there is no provider. */
    private void hookContextMenu() {
        MemorySegment web11 = Com.queryInterface(webView, WebView2Abi.ICoreWebView2_11.IID);
        if (web11.equals(MemorySegment.NULL)) {
            LOG.log(System.Logger.Level.WARNING, "setContextMenu has no effect: the installed WebView2 runtime is too old for it");
            return;
        }
        try {
            ComHandler handler = ComHandler.ptrPtr(WebView2Abi.ICoreWebView2ContextMenuRequestedEventHandler.IID,
                    this::onContextMenuRequested, app::deferFree);
            handlers.add(handler);
            try (Arena arena = Arena.ofConfined()) {
                Com.callChecked("add_ContextMenuRequested", web11, WebView2Abi.ICoreWebView2_11.ADD_CONTEXT_MENU_REQUESTED,
                        Com.HR_PTR_PTR, handler.object, arena.allocate(JAVA_LONG));
            }
            contextMenuHooked = true;
        } finally {
            Com.release(web11);
        }
    }

    /** The user asked for a context menu. Runs inside the web view's event: reads what was clicked, takes over, and queues the menu. */
    private void onContextMenuRequested(MemorySegment sender, MemorySegment args) {
        ContextMenuProvider provider = contextMenuProvider;
        if (provider == null) {
            return; // the web view does what it would have done
        }
        ContextMenuInfo info;
        int[] at = new int[2];
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment point = arena.allocate(8, 4);
            if (Com.call(args, WebView2Abi.ICoreWebView2ContextMenuRequestedEventArgs.GET_LOCATION, Com.HR_PTR, point) >= 0) {
                at[0] = point.get(JAVA_INT, 0);
                at[1] = point.get(JAVA_INT, 4);
            }
            MemorySegment out = arena.allocate(ADDRESS);
            String page = "", link = "", source = "", selection = "";
            boolean editable = false, hasSelection = false;
            if (Com.call(args, WebView2Abi.ICoreWebView2ContextMenuRequestedEventArgs.GET_CONTEXT_MENU_TARGET, Com.HR_PTR, out) >= 0) {
                MemorySegment target = Native.readPointer(out);
                try {
                    editable = targetFlag(target, WebView2Abi.ICoreWebView2ContextMenuTarget.GET_IS_EDITABLE);
                    page = targetText(target, WebView2Abi.ICoreWebView2ContextMenuTarget.GET_PAGE_URI);
                    if (targetFlag(target, WebView2Abi.ICoreWebView2ContextMenuTarget.GET_HAS_LINK_URI)) {
                        link = targetText(target, WebView2Abi.ICoreWebView2ContextMenuTarget.GET_LINK_URI);
                    }
                    if (targetFlag(target, WebView2Abi.ICoreWebView2ContextMenuTarget.GET_HAS_SOURCE_URI)) {
                        source = targetText(target, WebView2Abi.ICoreWebView2ContextMenuTarget.GET_SOURCE_URI);
                    }
                    hasSelection = targetFlag(target, WebView2Abi.ICoreWebView2ContextMenuTarget.GET_HAS_SELECTION);
                    if (hasSelection) {
                        selection = targetText(target, WebView2Abi.ICoreWebView2ContextMenuTarget.GET_SELECTION_TEXT);
                    }
                } finally {
                    Com.release(target);
                }
            }
            int dpi = Win32.dpiForWindow(hwnd);
            info = new ContextMenuInfo(Math.round(at[0] * 96f / dpi), Math.round(at[1] * 96f / dpi), page, link, source, editable,
                    hasSelection, selection);
        }
        Com.call(args, WebView2Abi.ICoreWebView2ContextMenuRequestedEventArgs.PUT_HANDLED, Com.HR_INT, 1); // the web view shows nothing
        app.dispatch(() -> showContextMenu(provider, info, at[0], at[1]));
    }

    private static boolean targetFlag(MemorySegment target, int slot) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_INT);
            return Com.call(target, slot, Com.HR_PTR, out) >= 0 && out.get(JAVA_INT, 0) != 0;
        }
    }

    private static String targetText(MemorySegment target, int slot) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            if (Com.call(target, slot, Com.HR_PTR, out) < 0) {
                return "";
            }
            MemorySegment raw = Native.readPointer(out);
            if (raw.equals(MemorySegment.NULL)) {
                return "";
            }
            String text = Native.readWide(raw);
            Win32.coTaskMemFree(raw);
            return text;
        }
    }

    /** UI thread, outside the web view's event: asks the provider and shows the menu in its own loop. */
    private void showContextMenu(ContextMenuProvider provider, ContextMenuInfo info, int clientX, int clientY) {
        if (destroyed || contextMenuOpen) {
            return;
        }
        Menu menu;
        try {
            menu = provider.menuFor(info);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "the context menu provider failed", e);
            return;
        }
        if (menu == null || menu.items().isEmpty()) {
            return;
        }
        WindowsMenus.Built popup = null;
        contextMenuOpen = true;
        try {
            WindowsTray.makeMenusThemeAware();
            popup = WindowsMenus.build(menu, false);
            int[] screen = Win32.clientToScreen(hwnd, clientX, clientY);
            Win32.setForegroundWindow(hwnd); // without it the menu does not close when the user clicks elsewhere
            int chosen = WindowsMenus.track(popup.handle, hwnd, screen[0], screen[1]);
            Win32.postMessage(hwnd, 0, 0, 0); // WM_NULL: lets the menu loop finish cleanly
            if (chosen != 0) {
                WindowsMenus.Built shown = popup;
                int id = chosen;
                app.dispatch(() -> shown.activate(id, false));
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "showing the context menu failed", e);
        } finally {
            contextMenuOpen = false;
            if (popup != null) {
                popup.destroy();
            }
        }
    }

    @Override
    public void edit(EditCommand command) {
        String name = switch (command) {
            case UNDO -> "undo";
            case REDO -> "redo";
            case CUT -> "cut";
            case COPY -> "copy";
            case PASTE -> "paste";
            case SELECT_ALL -> "selectAll";
        };
        // The browser's own editing commands, as a key press would run them: they work without a user gesture and use the real clipboard
        whenReady(() -> callDevTools("Input.dispatchKeyEvent", "{\"type\":\"rawKeyDown\",\"commands\":[\"" + name + "\"]}")
                .exceptionally(t -> {
                    LOG.log(System.Logger.Level.WARNING, "edit({0}) failed: {1}", command, t.toString());
                    return null;
                }));
    }

    /** Calls a DevTools Protocol method; the future gets the raw JSON answer. UI thread, web view ready. Package-private for tests, which synthesize input with it. */
    CompletableFuture<String> callDevTools(String method, String paramsJson) {
        CompletableFuture<String> result = new CompletableFuture<>();
        ComHandler[] handler = new ComHandler[1];
        handler[0] = ComHandler.hrPtr(WebView2Abi.ICoreWebView2CallDevToolsProtocolMethodCompletedHandler.IID, (hr, json) -> {
            try {
                if (hr < 0) {
                    result.completeExceptionally(new JoltException(method + " failed: " + Com.hex(hr)));
                } else {
                    result.complete(Native.readWide(json));
                }
            } finally {
                handler[0].release();
            }
        }, app::deferFree);
        try (Arena arena = Arena.ofConfined()) {
            int hr = Com.call(webView, WebView2Abi.ICoreWebView2.CALL_DEV_TOOLS_PROTOCOL_METHOD, Com.HR_PTR_PTR_PTR,
                    Native.allocWide(arena, method), Native.allocWide(arena, paramsJson), handler[0].object);
            if (hr < 0) {
                handler[0].release();
                result.completeExceptionally(new JoltException("CallDevToolsProtocolMethod failed: " + Com.hex(hr)));
            }
        }
        return result;
    }

    @Override
    public CompletableFuture<byte[]> printToPdf(PrintOptions options) {
        java.util.Objects.requireNonNull(options, "options");
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        app.dispatch(() -> {
            if (destroyed) {
                result.completeExceptionally(new WindowClosedException());
                return;
            }
            inflight.add(result);
            result.whenComplete((r, e) -> inflight.remove(result));
            onceReady(() -> startPrintToPdf(options, result));
        });
        return result;
    }

    /** UI thread, web view ready: has WebView2 write the PDF to a temporary file, then reads it back. */
    private void startPrintToPdf(PrintOptions printOptions, CompletableFuture<byte[]> result) {
        MemorySegment web7 = Com.queryInterface(webView, WebView2Abi.ICoreWebView2_7.IID);
        if (web7.equals(MemorySegment.NULL)) {
            result.completeExceptionally(new UnsupportedOperationException("the installed WebView2 runtime is too old to print to PDF"));
            return;
        }
        MemorySegment settings = MemorySegment.NULL;
        java.nio.file.Path file = null;
        try {
            settings = createPrintSettings(printOptions);
            if (settings.equals(MemorySegment.NULL)) {
                result.completeExceptionally(new UnsupportedOperationException("the installed WebView2 runtime is too old to print to PDF"));
                return;
            }
            java.nio.file.Path target = file = java.nio.file.Files.createTempFile("jolt-print-", ".pdf");
            ComHandler[] handler = new ComHandler[1];
            handler[0] = ComHandler.hrInt(WebView2Abi.ICoreWebView2PrintToPdfCompletedHandler.IID,
                    (hr, success) -> {
                        try {
                            finishPrintToPdf(hr, success != 0, target, result);
                        } finally {
                            handler[0].release();
                        }
                    }, app::deferFree);
            try (Arena arena = Arena.ofConfined()) {
                int hr = Com.call(web7, WebView2Abi.ICoreWebView2_7.PRINT_TO_PDF, Com.HR_PTR_PTR_PTR,
                        Native.allocWide(arena, target.toString()), settings, handler[0].object);
                if (hr < 0) {
                    handler[0].release();
                    deleteQuietly(target);
                    result.completeExceptionally(new JoltException("PrintToPdf failed: " + Com.hex(hr)));
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            deleteQuietly(file);
            result.completeExceptionally(e instanceof JoltException ? e : new JoltException("Printing to PDF failed: " + e.getMessage(), e));
        } finally {
            if (!settings.equals(MemorySegment.NULL)) {
                Com.release(settings);
            }
            Com.release(web7);
        }
    }

    private void finishPrintToPdf(int hr, boolean success, java.nio.file.Path file, CompletableFuture<byte[]> result) {
        try {
            if (hr < 0 || !success) {
                result.completeExceptionally(new JoltException("Printing to PDF failed" + (hr < 0 ? ": " + Com.hex(hr) : "")));
            } else {
                result.complete(java.nio.file.Files.readAllBytes(file));
            }
        } catch (java.io.IOException e) {
            result.completeExceptionally(new JoltException("The PDF could not be read back: " + e.getMessage(), e));
        } finally {
            deleteQuietly(file);
        }
    }

    private static void deleteQuietly(java.nio.file.Path file) {
        if (file != null) {
            try {
                java.nio.file.Files.deleteIfExists(file);
            } catch (java.io.IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "could not delete {0}: {1}", file, e.toString());
            }
        }
    }

    /** A new {@code ICoreWebView2PrintSettings} with {@code printOptions} applied (the caller releases it), or NULL if the runtime has none. */
    private MemorySegment createPrintSettings(PrintOptions printOptions) {
        MemorySegment env6 = Com.queryInterface(environment, WebView2Abi.ICoreWebView2Environment6.IID);
        if (env6.equals(MemorySegment.NULL)) {
            return MemorySegment.NULL;
        }
        MemorySegment settings;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            Com.callChecked("CreatePrintSettings", env6, WebView2Abi.ICoreWebView2Environment6.CREATE_PRINT_SETTINGS, Com.HR_PTR, out);
            settings = Native.readPointer(out);
        } finally {
            Com.release(env6);
        }
        double inch = 25.4;
        Com.callChecked("put_Orientation", settings, WebView2Abi.ICoreWebView2PrintSettings.PUT_ORIENTATION, Com.HR_INT,
                printOptions.landscape() ? 1 : 0);
        Com.callChecked("put_ScaleFactor", settings, WebView2Abi.ICoreWebView2PrintSettings.PUT_SCALE_FACTOR, Com.HR_DOUBLE, printOptions.scale());
        Com.callChecked("put_PageWidth", settings, WebView2Abi.ICoreWebView2PrintSettings.PUT_PAGE_WIDTH, Com.HR_DOUBLE,
                printOptions.paperWidthMm() / inch);
        Com.callChecked("put_PageHeight", settings, WebView2Abi.ICoreWebView2PrintSettings.PUT_PAGE_HEIGHT, Com.HR_DOUBLE,
                printOptions.paperHeightMm() / inch);
        Com.callChecked("put_MarginTop", settings, WebView2Abi.ICoreWebView2PrintSettings.PUT_MARGIN_TOP, Com.HR_DOUBLE,
                printOptions.marginTopMm() / inch);
        Com.callChecked("put_MarginRight", settings, WebView2Abi.ICoreWebView2PrintSettings.PUT_MARGIN_RIGHT, Com.HR_DOUBLE,
                printOptions.marginRightMm() / inch);
        Com.callChecked("put_MarginBottom", settings, WebView2Abi.ICoreWebView2PrintSettings.PUT_MARGIN_BOTTOM, Com.HR_DOUBLE,
                printOptions.marginBottomMm() / inch);
        Com.callChecked("put_MarginLeft", settings, WebView2Abi.ICoreWebView2PrintSettings.PUT_MARGIN_LEFT, Com.HR_DOUBLE,
                printOptions.marginLeftMm() / inch);
        Com.callChecked("put_ShouldPrintBackgrounds", settings, WebView2Abi.ICoreWebView2PrintSettings.PUT_SHOULD_PRINT_BACKGROUNDS,
                Com.HR_INT, printOptions.backgrounds() ? 1 : 0);
        Com.callChecked("put_ShouldPrintHeaderAndFooter", settings, WebView2Abi.ICoreWebView2PrintSettings.PUT_SHOULD_PRINT_HEADER_AND_FOOTER,
                Com.HR_INT, printOptions.headerAndFooter() ? 1 : 0);
        return settings;
    }

    private static final int KEY_EVENT_KEY_DOWN = 0, KEY_EVENT_SYSTEM_KEY_DOWN = 2; // COREWEBVIEW2_KEY_EVENT_KIND

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

    /** Destroys the native window now; used by the application on shutdown. */
    void destroyNow() {
        if (!destroyed) {
            closing = true;
            if (dialogs != null && dialogs.isShowing()) {
                // Destroying the owner of an open file dialog leaves the shell in a state that crashes the next dialog, so the
                // dialog is closed first and the window destroyed once its modal loop has returned.
                dialogs.cancelShowing();
                return; // WindowsDialogs calls destroyNow again when the dialog is gone
            }
            Win32.destroyWindow(hwnd); // synchronously delivers WM_DESTROY to handleMessage
        }
    }

    /** Window messages for this window; returns the LRESULT, or {@code null} for default processing. */
    Long handleMessage(int msg, long wParam, long lParam) {
        if (taskbarButtons.isButtonCreatedMessage(msg)) {
            taskbarButtons.onButtonCreated();
            return 0L;
        }
        switch (msg) {
            case Win32.WM_NCCALCSIZE -> {
                if (!options.frameless() || wParam == 0) {
                    return null;
                }
                if (Win32.isZoomed(hwnd)) {
                    // A maximized window with a sizing frame is laid out that many pixels past each screen edge; pull it back.
                    int dpi = Win32.dpiForWindow(hwnd);
                    int x = Win32.systemMetricForDpi(Win32.SM_CXSIZEFRAME, dpi) + Win32.systemMetricForDpi(Win32.SM_CXPADDEDBORDER, dpi);
                    int y = Win32.systemMetricForDpi(Win32.SM_CYSIZEFRAME, dpi) + Win32.systemMetricForDpi(Win32.SM_CXPADDEDBORDER, dpi);
                    MemorySegment rect = MemorySegment.ofAddress(lParam).reinterpret(16); // NCCALCSIZE_PARAMS starts with RECT rgrc[3]
                    rect.set(JAVA_INT, 0, rect.get(JAVA_INT, 0) + x);
                    rect.set(JAVA_INT, 4, rect.get(JAVA_INT, 4) + y);
                    rect.set(JAVA_INT, 8, rect.get(JAVA_INT, 8) - x);
                    rect.set(JAVA_INT, 12, rect.get(JAVA_INT, 12) - y);
                }
                return 0L; // the client area is the whole window
            }
            case Win32.WM_SIZE -> {
                resizeWebView();
                if (options.frameless() && (wParam == Win32.SIZE_MAXIMIZED || wParam == Win32.SIZE_RESTORED)
                        && (wParam == Win32.SIZE_MAXIMIZED) != sentMaximized) {
                    pushFrameState();
                }
                return 0L;
            }
            case Win32.WM_ACTIVATE -> {
                boolean nowActive = (wParam & 0xFFFF) != 0; // WA_INACTIVE is 0
                if (nowActive != active) {
                    active = nowActive;
                    pushFrameState();
                }
                return null; // the system still moves the focus
            }
            case Win32.WM_GETMINMAXINFO -> {
                if (fullscreen) {
                    // Windows would clamp a window to the primary monitor's size plus its frame; full screen may be on a bigger one.
                    int[] monitor = Win32.monitorRectOf(hwnd);
                    if (monitor != null) {
                        MemorySegment info = MemorySegment.ofAddress(lParam).reinterpret(40); // MINMAXINFO: five POINTs
                        info.set(JAVA_INT, 8, monitor[2] - monitor[0]);
                        info.set(JAVA_INT, 12, monitor[3] - monitor[1]);
                        info.set(JAVA_INT, 32, monitor[2] - monitor[0]);
                        info.set(JAVA_INT, 36, monitor[3] - monitor[1]);
                        return 0L;
                    }
                }
                if (!options.resizable() || options.minWidth() == 0 && options.minHeight() == 0) {
                    return null;
                }
                applyMinTrackSize(lParam);
                return 0L;
            }
            case Win32.WM_SHOWWINDOW -> {
                // A web view created while its window was hidden does not paint when the window is shown later unless it is told.
                syncWebViewVisibility(wParam != 0);
                return null; // the system still does its own handling
            }
            case Win32.WM_ERASEBKGND -> {
                if (backgroundBrush.equals(MemorySegment.NULL)) {
                    return null;
                }
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment rect = arena.allocate(Win32.RECT);
                    Win32.getClientRect(hwnd, rect);
                    Win32.fillRect(MemorySegment.ofAddress(wParam), rect, backgroundBrush);
                }
                return 1L; // erased
            }
            case Win32.WM_SETFOCUS -> {
                if (webViewReady) {
                    Com.call(controller, WebView2Abi.ICoreWebView2Controller.MOVE_FOCUS, Com.HR_INT, MOVE_FOCUS_PROGRAMMATIC);
                }
                return 0L;
            }
            case Win32.WM_DPICHANGED -> {
                // lParam points at the suggested new outer rectangle
                MemorySegment rect = MemorySegment.ofAddress(lParam).reinterpret(Win32.RECT.byteSize());
                int left = rect.get(JAVA_INT, 0);
                int top = rect.get(JAVA_INT, 4);
                Win32.setWindowPos(hwnd, left, top, rect.get(JAVA_INT, 8) - left, rect.get(JAVA_INT, 12) - top,
                        Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE);
                return 0L;
            }
            case Win32.WM_SYSCOMMAND -> {
                // Alt+Space and the window menu of a frameless window still offer Size and Maximize; the low four bits are the system's own
                int command = (int) (wParam & 0xFFF0);
                if (!options.resizable() && (command == Win32.SC_SIZE || command == Win32.SC_MAXIMIZE)) {
                    return 0L;
                }
                return null;
            }
            case Win32.WM_COMMAND -> {
                WindowsMenus.Built bar = menuBar;
                int commandId = (int) (wParam & 0xFFFF);
                // From a menu the high word is 0 and lParam is 0 (a taskbar button has THBN_CLICKED in the high word)
                if (bar != null && (wParam >> 16 & 0xFFFF) == 0 && lParam == 0 && bar.handles(commandId)) {
                    app.dispatch(() -> {
                        if (menuBar == bar) {
                            bar.activate(commandId, true);
                        }
                    });
                    return 0L;
                }
                return taskbarButtons.onCommand(wParam) ? 0L : null;
            }
            case Win32.WM_CLOSE -> {
                if (closeBehavior == CloseBehavior.HIDE && !closing) {
                    Win32.showWindow(hwnd, Win32.SW_HIDE); // the application lives on, for example in the tray
                } else {
                    Win32.destroyWindow(hwnd);
                }
                return 0L;
            }
            case Win32.WM_DESTROY -> {
                onDestroyed();
                return 0L;
            }
            default -> {
                return null;
            }
        }
    }

    /** Shows the window (maximized if it was asked to start that way) and makes sure the web view is awake. UI thread. */
    private void display() {
        int command = maximizeOnFirstShow ? Win32.SW_SHOWMAXIMIZED : Win32.SW_SHOW;
        maximizeOnFirstShow = false;
        Win32.showWindow(hwnd, command);
        Win32.updateWindow(hwnd);
        // WM_SHOWWINDOW arrives before the window has its final position and size (maximizing changes both), so tell the web
        // view again now that they are settled.
        syncWebViewVisibility(true);
    }

    /** Puts the window in the middle of the usable area (without the taskbar) of the monitor it is on. UI thread. */
    private void center() {
        int[] work = Win32.workAreaOf(hwnd);
        if (work == null) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rect = arena.allocate(Win32.RECT);
            Win32.getWindowRect(hwnd, rect);
            int width = rect.get(JAVA_INT, 8) - rect.get(JAVA_INT, 0);
            int height = rect.get(JAVA_INT, 12) - rect.get(JAVA_INT, 4);
            int x = work[0] + (work[2] - work[0] - width) / 2;
            int y = work[1] + (work[3] - work[1] - height) / 2;
            // A window bigger than the work area keeps its top-left corner (and so its title bar) on screen.
            Win32.setWindowPos(hwnd, Math.max(x, work[0]), Math.max(y, work[1]), 0, 0,
                    Win32.SWP_NOSIZE | Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE);
        }
    }

    private void setBackgroundBrush(int rgb) {
        MemorySegment old = backgroundBrush;
        backgroundBrush = Win32.createSolidBrush(colorRef(rgb));
        if (!old.equals(MemorySegment.NULL)) {
            Win32.deleteObject(old);
        }
    }

    /** Keeps the web view's own visibility in step with its window's. UI thread. */
    private void syncWebViewVisibility(boolean visible) {
        if (controller.equals(MemorySegment.NULL) || destroyed) {
            return;
        }
        Com.call(controller, WebView2Abi.ICoreWebView2Controller.PUT_IS_VISIBLE, Com.HR_INT, visible ? 1 : 0);
        if (visible) {
            resizeWebView();
            Com.call(controller, WebView2Abi.ICoreWebView2Controller.NOTIFY_PARENT_WINDOW_POSITION_CHANGED, Com.HR_VOID);
        }
    }

    private void whenReady(Runnable op) {
        app.dispatch(() -> onceReady(op));
    }

    private void onceReady(Runnable op) {
        if (destroyed) {
            return;
        }
        if (webViewReady) {
            op.run();
        } else {
            pending.add(op);
        }
    }

    private WindowsDialogs dialogs;

    @Override
    public synchronized WindowsDialogs dialogs() {
        if (dialogs == null) {
            dialogs = new WindowsDialogs(app, this);
        }
        return dialogs;
    }

    /** Whether the native window is destroyed or about to be. */
    boolean isDestroyed() {
        return destroyed || closing;
    }

    /** The native window handle, for tests that check the real window state. */
    MemorySegment hwnd() {
        return hwnd;
    }

    /** The colour the web view currently shows behind the page as {@code 0xRRGGBB}, or -1 if it cannot be read. For tests. */
    int readBackgroundRgb() {
        MemorySegment controller2 = Com.queryInterface(controller, WebView2Abi.ICoreWebView2Controller2.IID);
        if (controller2.equals(MemorySegment.NULL)) {
            return -1;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment color = arena.allocate(Com.COLOR);
            if (Com.call(controller2, WebView2Abi.ICoreWebView2Controller2.GET_DEFAULT_BACKGROUND_COLOR, Com.HR_PTR, color) < 0) {
                return -1;
            }
            int red = color.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 1) & 0xFF;
            int green = color.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 2) & 0xFF;
            int blue = color.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 3) & 0xFF;
            return (red << 16) | (green << 8) | blue;
        } finally {
            Com.release(controller2);
        }
    }

    /** The window's outer rectangle as {left, top, right, bottom} in screen coordinates. For tests; UI thread. */
    int[] outerBounds() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rect = arena.allocate(Win32.RECT);
            Win32.getWindowRect(hwnd, rect);
            return new int[] {rect.get(JAVA_INT, 0), rect.get(JAVA_INT, 4), rect.get(JAVA_INT, 8), rect.get(JAVA_INT, 12)};
        }
    }

    /** The colour the window itself is painted with behind the web view as {@code 0xRRGGBB}, or -1 if it has none. For tests. */
    int readWindowBackgroundRgb() {
        if (backgroundBrush.equals(MemorySegment.NULL)) {
            return -1;
        }
        int colorRef = Win32.brushColorRef(backgroundBrush);
        return colorRef < 0 ? -1 : colorRef(colorRef) & 0xFFFFFF; // COLORREF <-> RGB is the same byte swap in both directions
    }

    /** Win32 {@code COLORREF} is {@code 0x00BBGGRR}; ours is {@code 0xRRGGBB}. */
    static int colorRef(int rgb) {
        return ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
    }

    private void applyTitleBarScheme(ColorScheme scheme) {
        int dark = scheme == ColorScheme.DARK ? 1 : 0;
        if (Win32.dwmSetAttribute(hwnd, Win32.DWMWA_USE_IMMERSIVE_DARK_MODE, dark) < 0) {
            LOG.log(System.Logger.Level.DEBUG, "The title bar colour scheme needs Windows 10 version 2004 or later; ignored");
        }
        redrawFrame();
    }

    /** Makes Windows repaint the title bar so a changed attribute shows at once. */
    private void redrawFrame() {
        Win32.setWindowPos(hwnd, 0, 0, 0, 0,
                Win32.SWP_NOMOVE | Win32.SWP_NOSIZE | Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE | Win32.SWP_FRAMECHANGED);
    }

    /** The image that is nearest in size to what Windows asks for (title bar and taskbar), installed as the window icon. */
    private void applyIcon(byte[] data) {
        int dpi = Win32.dpiForWindow(hwnd);
        MemorySegment big = iconOfSize(data, Win32.systemMetricForDpi(Win32.SM_CXICON, dpi));
        MemorySegment small = iconOfSize(data, Win32.systemMetricForDpi(Win32.SM_CXSMICON, dpi));
        if (big.equals(MemorySegment.NULL) || small.equals(MemorySegment.NULL)) {
            LOG.log(System.Logger.Level.WARNING, "The window icon could not be decoded; it must be PNG or ICO data");
            if (!big.equals(MemorySegment.NULL)) {
                Win32.destroyIcon(big);
            }
            if (!small.equals(MemorySegment.NULL)) {
                Win32.destroyIcon(small);
            }
            return;
        }
        Win32.sendMessage(hwnd, Win32.WM_SETICON, Win32.ICON_BIG, big.address());
        Win32.sendMessage(hwnd, Win32.WM_SETICON, Win32.ICON_SMALL, small.address());
        destroyIcons(); // the previous pair, now unused
        bigIcon = big;
        smallIcon = small;
    }

    /**
     * An icon of {@code pixels} pixels square from PNG data or an {@code .ico} file. From an icon file the image that
     * already has (or is just above) that size is used, so Windows never has to enlarge or heavily shrink one, which is
     * what makes taskbar icons look grainy when only one big image is supplied.
     */
    static MemorySegment iconOfSize(byte[] data, int pixels) {
        if (Icons.isIco(data)) {
            Icons.Entry entry = Icons.best(data, pixels);
            if (entry == null) {
                return MemorySegment.NULL;
            }
            // Bitmap entries become PNG first: an icon made from one is drawn as a generic placeholder on the taskbar.
            byte[] image = Icons.asPng(data, entry);
            return Win32.createIcon(image != null ? image : Icons.image(data, entry), pixels, pixels);
        }
        return Win32.createIcon(data, pixels, pixels);
    }

    private void destroyIcons() {
        if (!bigIcon.equals(MemorySegment.NULL)) {
            Win32.destroyIcon(bigIcon);
        }
        if (!smallIcon.equals(MemorySegment.NULL)) {
            Win32.destroyIcon(smallIcon);
        }
        bigIcon = smallIcon = MemorySegment.NULL;
    }

    /** Colour shown behind the page, set through {@code ICoreWebView2Controller2}. Opaque colours only. */
    private void applyBackground(int rgb) {
        MemorySegment controller2 = Com.queryInterface(controller, WebView2Abi.ICoreWebView2Controller2.IID);
        if (controller2.equals(MemorySegment.NULL)) {
            LOG.log(System.Logger.Level.DEBUG, "This WebView2 runtime cannot set the background colour; ignored");
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment color = arena.allocate(Com.COLOR);
            color.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 0xFF);       // alpha: opaque
            color.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 1, (byte) (rgb >> 16)); // red
            color.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 2, (byte) (rgb >> 8));  // green
            color.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 3, (byte) rgb);         // blue
            Com.call(controller2, WebView2Abi.ICoreWebView2Controller2.PUT_DEFAULT_BACKGROUND_COLOR, Com.HR_COLOR, color);
        } finally {
            Com.release(controller2);
        }
    }

    private void applyClientSize(int logicalWidth, int logicalHeight) {
        int dpi = Win32.dpiForWindow(hwnd);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rect = arena.allocate(Win32.RECT);
            rect.set(JAVA_INT, 8, Math.round(logicalWidth * dpi / 96f));
            rect.set(JAVA_INT, 12, Math.round(logicalHeight * dpi / 96f));
            if (!options.frameless()) {
                Win32.adjustWindowRectExForDpi(rect, style, menuBar != null, 0, dpi); // a frameless window is all client area
            }
            Win32.setWindowPos(hwnd, 0, 0, rect.get(JAVA_INT, 8) - rect.get(JAVA_INT, 0),
                    rect.get(JAVA_INT, 12) - rect.get(JAVA_INT, 4),
                    Win32.SWP_NOMOVE | Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE);
        }
    }

    /** Fills in {@code ptMinTrackSize} of the MINMAXINFO that {@code lParam} points at, with the frame added to the client minimum. */
    private void applyMinTrackSize(long lParam) {
        int dpi = Win32.dpiForWindow(hwnd);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rect = arena.allocate(Win32.RECT);
            rect.set(JAVA_INT, 8, Math.round(options.minWidth() * dpi / 96f));
            rect.set(JAVA_INT, 12, Math.round(options.minHeight() * dpi / 96f));
            Win32.adjustWindowRectExForDpi(rect, STYLE, menuBar != null, 0, dpi);
            MemorySegment info = MemorySegment.ofAddress(lParam).reinterpret(40); // MINMAXINFO: five POINTs
            info.set(JAVA_INT, 24, rect.get(JAVA_INT, 8) - rect.get(JAVA_INT, 0));
            info.set(JAVA_INT, 28, rect.get(JAVA_INT, 12) - rect.get(JAVA_INT, 4));
        }
    }

    private void resizeWebView() {
        if (controller.equals(MemorySegment.NULL) || destroyed) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rect = arena.allocate(Win32.RECT);
            Win32.getClientRect(hwnd, rect);
            Com.call(controller, WebView2Abi.ICoreWebView2Controller.PUT_BOUNDS, Com.HR_RECT, rect);
        }
    }

    private void initWebView() {
        app.environment().whenComplete((env, failure) -> {
            if (failure != null) {
                ready.completeExceptionally(failure);
                return;
            }
            if (destroyed) {
                return;
            }
            environment = env;
            ComHandler[] handler = new ComHandler[1];
            handler[0] = ComHandler.hrPtr(WebView2Abi.ICoreWebView2CreateCoreWebView2ControllerCompletedHandler.IID,
                    (hr, created) -> {
                        try {
                            onControllerCreated(hr, created);
                        } finally {
                            handler[0].release();
                        }
                    }, app::deferFree);
            int hr = Com.call(env, WebView2Abi.ICoreWebView2Environment.CREATE_CORE_WEB_VIEW2_CONTROLLER,
                    Com.HR_PTR_PTR, hwnd, handler[0].object);
            if (hr < 0) {
                handler[0].release();
                ready.completeExceptionally(new JoltException("CreateCoreWebView2Controller failed: " + Com.hex(hr)));
            }
        });
    }

    private void onControllerCreated(int hr, MemorySegment created) {
        if (hr < 0 || created.equals(MemorySegment.NULL)) {
            ready.completeExceptionally(new JoltException("Creating the WebView2 controller failed: " + Com.hex(hr)));
            return;
        }
        if (destroyed) {
            Com.call(created, WebView2Abi.ICoreWebView2Controller.CLOSE, Com.HR_VOID);
            return;
        }
        try {
            Com.addRef(created); // keep it past the end of this callback
            controller = created;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(ADDRESS);
                Com.callChecked("get_CoreWebView2", controller,
                        WebView2Abi.ICoreWebView2Controller.GET_CORE_WEB_VIEW2, Com.HR_PTR, out);
                webView = Native.readPointer(out); // returned with a reference we now own
            }
            configureSettings();
            if (options.backgroundRgb() != null) {
                applyBackground(options.backgroundRgb()); // before the first navigation, so there is no white flash
            }
            addEvent(WebView2Abi.ICoreWebView2.ADD_WEB_MESSAGE_RECEIVED, ComHandler.ptrPtr(
                    WebView2Abi.ICoreWebView2WebMessageReceivedEventHandler.IID, this::onWebMessage, app::deferFree));
            addEvent(WebView2Abi.ICoreWebView2.ADD_WINDOW_CLOSE_REQUESTED, ComHandler.ptrPtr(
                    WebView2Abi.ICoreWebView2WindowCloseRequestedEventHandler.IID, (sender, args) -> close(), app::deferFree));
            addEvent(WebView2Abi.ICoreWebView2.ADD_NAVIGATION_STARTING, ComHandler.ptrPtr(
                    WebView2Abi.ICoreWebView2NavigationStartingEventHandler.IID, this::onNavigationStarting, app::deferFree));
            addEvent(WebView2Abi.ICoreWebView2.ADD_FRAME_NAVIGATION_STARTING, ComHandler.ptrPtr(
                    WebView2Abi.ICoreWebView2NavigationStartingEventHandler.IID, this::onNavigationStarting, app::deferFree));
            addEvent(WebView2Abi.ICoreWebView2.ADD_NEW_WINDOW_REQUESTED, ComHandler.ptrPtr(
                    WebView2Abi.ICoreWebView2NewWindowRequestedEventHandler.IID, this::onNewWindowRequested, app::deferFree));
            addEvent(WebView2Abi.ICoreWebView2.ADD_PERMISSION_REQUESTED, ComHandler.ptrPtr(
                    WebView2Abi.ICoreWebView2PermissionRequestedEventHandler.IID, this::onPermissionRequested, app::deferFree));
            // The web view takes the keyboard: it is the only way the menu bar's accelerators get to see a key press at all
            ComHandler accelerator = ComHandler.ptrPtr(WebView2Abi.ICoreWebView2AcceleratorKeyPressedEventHandler.IID,
                    this::onAcceleratorKey, app::deferFree);
            handlers.add(accelerator);
            try (Arena arena = Arena.ofConfined()) {
                Com.callChecked("add_AcceleratorKeyPressed", controller, WebView2Abi.ICoreWebView2Controller.ADD_ACCELERATOR_KEY_PRESSED,
                        Com.HR_PTR_PTR, accelerator.object, arena.allocate(JAVA_LONG));
            }
            resizeWebView();
            Com.call(controller, WebView2Abi.ICoreWebView2Controller.MOVE_FOCUS, Com.HR_INT, MOVE_FOCUS_PROGRAMMATIC);

            // Operations queued by callers run only after the bridge script is registered, so every navigation sees it.
            String bridge = BridgeScript.build("window.chrome.webview.postMessage(m)", options.security().bridgeInSubframes());
            if (options.frameless()) {
                addScriptOnDocumentCreated(bridge, () -> addScriptOnDocumentCreated(FrameScript.build("windows", options.resizable()), this::finishInit));
            } else {
                addScriptOnDocumentCreated(bridge, this::finishInit);
            }
        } catch (RuntimeException e) {
            ready.completeExceptionally(e);
            LOG.log(System.Logger.Level.ERROR, "WebView2 initialization failed", e);
        }
    }

    private void finishInit() {
        webViewReady = true;
        List<Runnable> queued = new ArrayList<>(pending);
        pending.clear();
        for (Runnable op : queued) {
            try {
                op.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "queued window operation failed", e);
            }
        }
        ready.complete(null);
    }

    private void configureSettings() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            Com.callChecked("get_Settings", webView, WebView2Abi.ICoreWebView2.GET_SETTINGS, Com.HR_PTR, out);
            MemorySegment settings = Native.readPointer(out);
            try {
                int devTools = options.devTools() ? 1 : 0;
                Com.call(settings, WebView2Abi.ICoreWebView2Settings.PUT_ARE_DEV_TOOLS_ENABLED, Com.HR_INT, devTools);
                Com.call(settings, WebView2Abi.ICoreWebView2Settings.PUT_ARE_DEFAULT_CONTEXT_MENUS_ENABLED, Com.HR_INT, devTools);
                Com.call(settings, WebView2Abi.ICoreWebView2Settings.PUT_IS_STATUS_BAR_ENABLED, Com.HR_INT, 0);
                // No zooming: Ctrl+plus/minus/0, Ctrl+wheel and pinch all stay at 100%.
                Com.call(settings, WebView2Abi.ICoreWebView2Settings.PUT_IS_ZOOM_CONTROL_ENABLED, Com.HR_INT, 0);
                // Browser shortcuts (Ctrl+F find bar, Ctrl+P, F5, Ctrl+R, zoom...) make no sense in an app window.
                // The property lives in ICoreWebView2Settings3 (older runtimes lack it: ignore).
                MemorySegment settings3 = Com.queryInterface(settings, IID_SETTINGS3);
                if (settings3 != null && !settings3.equals(MemorySegment.NULL)) {
                    try {
                        Com.call(settings3, SETTINGS3_PUT_ARE_BROWSER_ACCELERATOR_KEYS_ENABLED, Com.HR_INT, 0);
                    } finally {
                        Com.release(settings3);
                    }
                }
            } finally {
                Com.release(settings);
            }
        }
    }

    /** {@code ICoreWebView2Settings3} (WebView2.h): Settings 0-20, Settings2 UserAgent 21-22, then 23 get / 24 put. */
    private static final String IID_SETTINGS3 = "fdb5ab74-af33-4854-84f0-0a631deb5eba";
    private static final int SETTINGS3_PUT_ARE_BROWSER_ACCELERATOR_KEYS_ENABLED = 24;

    private void addEvent(int addSlot, ComHandler handler) {
        handlers.add(handler);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment token = arena.allocate(JAVA_LONG);
            Com.callChecked("add_event(slot " + addSlot + ")", webView, addSlot, Com.HR_PTR_PTR, handler.object, token);
        }
    }

    /** Top-level and frame navigations; also covers redirects and links. Cancelled unless the policy allows the target. */
    private void onNavigationStarting(MemorySegment sender, MemorySegment args) {
        String uri = readUri(args, WebView2Abi.ICoreWebView2NavigationStartingEventArgs.GET_URI);
        if (!guard.allowNavigation(uri)) {
            Com.call(args, WebView2Abi.ICoreWebView2NavigationStartingEventArgs.PUT_CANCEL, Com.HR_INT, 1);
        }
    }

    private void onNewWindowRequested(MemorySegment sender, MemorySegment args) {
        String uri = readUri(args, WebView2Abi.ICoreWebView2NewWindowRequestedEventArgs.GET_URI);
        if (!guard.allowNewWindow(uri)) {
            Com.call(args, WebView2Abi.ICoreWebView2NewWindowRequestedEventArgs.PUT_HANDLED, Com.HR_INT, 1);
        }
    }

    private void onPermissionRequested(MemorySegment sender, MemorySegment args) {
        String uri = readUri(args, WebView2Abi.ICoreWebView2PermissionRequestedEventArgs.GET_URI);
        io.github.b077as.jolt.Permission kind = permissionOf(args);
        if (guard.grants(uri, kind)) {
            // named in the SecurityPolicy for our own content: answer here, so the web view neither asks nor remembers a "no"
            answerPermission(args, PERMISSION_STATE_ALLOW);
        } else if (!guard.allowPermission(uri, kind)) {
            answerPermission(args, PERMISSION_STATE_DENY);
        }
    }

    /**
     * Sets the answer to a permission request and tells WebView2 not to keep it in the profile. A kept answer is applied
     * to later requests without raising the event, so a changed {@link SecurityPolicy} (or a different window sharing
     * the profile) would no longer be asked.
     */
    private static void answerPermission(MemorySegment args, int state) {
        Com.call(args, WebView2Abi.ICoreWebView2PermissionRequestedEventArgs.PUT_STATE, Com.HR_INT, state);
        MemorySegment args3 = Com.queryInterface(args, WebView2Abi.ICoreWebView2PermissionRequestedEventArgs3.IID);
        if (args3 != null && !args3.equals(MemorySegment.NULL)) { // older runtimes lack it
            try {
                Com.call(args3, WebView2Abi.ICoreWebView2PermissionRequestedEventArgs3.PUT_SAVES_IN_PROFILE, Com.HR_INT, 0);
            } finally {
                Com.release(args3);
            }
        }
    }

    /** The permission a request is for, or {@code null} for a kind {@link io.github.b077as.jolt.Permission} has no name for. */
    private static io.github.b077as.jolt.Permission permissionOf(MemorySegment args) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(java.lang.foreign.ValueLayout.JAVA_INT);
            if (Com.call(args, WebView2Abi.ICoreWebView2PermissionRequestedEventArgs.GET_PERMISSION_KIND, Com.HR_PTR, out) < 0) {
                return null;
            }
            return switch (out.get(java.lang.foreign.ValueLayout.JAVA_INT, 0)) { // COREWEBVIEW2_PERMISSION_KIND
                case 1 -> io.github.b077as.jolt.Permission.MICROPHONE;
                case 2 -> io.github.b077as.jolt.Permission.CAMERA;
                default -> null;
            };
        }
    }

    /** A string property of an event-args object ({@code LPWSTR*} out parameter), or {@code null} if it cannot be read. */
    private static String readUri(MemorySegment args, int getterSlot) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            if (Com.call(args, getterSlot, Com.HR_PTR, out) < 0) {
                return null;
            }
            MemorySegment raw = Native.readPointer(out);
            String uri = Native.readWide(raw);
            Win32.coTaskMemFree(raw);
            return uri;
        }
    }

    @Override
    public void setTaskbarButtons(List<io.github.b077as.jolt.TaskbarButton> buttons) {
        List<io.github.b077as.jolt.TaskbarButton> copy = List.copyOf(buttons);
        taskbarButtons.reserve(copy.size()); // fails here, on the caller's thread, if it is too many
        app.dispatch(() -> {
            if (!destroyed) {
                taskbarButtons.set(copy);
            }
        });
    }

    @Override
    public boolean supportsTaskbarButtons() {
        return true;
    }

    int taskbarButtonsResult() {
        return taskbarButtons.lastResult;
    }

    @Override
    public void setTaskbarProgress(io.github.b077as.jolt.TaskbarProgress progress) {
        java.util.Objects.requireNonNull(progress, "progress");
        app.dispatch(() -> {
            if (!destroyed) {
                taskbarButtons.setProgress(progress);
            }
        });
    }

    @Override
    public boolean supportsTaskbarProgress() {
        return true;
    }

    int taskbarProgressResult() {
        return taskbarButtons.lastProgressResult;
    }

    @Override
    public boolean beginFileDrag(List<java.nio.file.Path> files, Consumer<Boolean> finished) {
        if (destroyed) {
            return false;
        }
        // Not inside the web view's own message handler: the drag runs a modal loop that must not nest in it.
        app.dispatch(() -> {
            boolean dropped = false;
            if (!destroyed) {
                try {
                    dropped = WindowsFileDrag.run(hwnd, files);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "dragging files out failed", e);
                }
            }
            finished.accept(dropped);
        });
        return true;
    }

    /**
     * The paths of the {@code File} objects the page attached to a message with {@code postMessageWithAdditionalObjects}: the
     * only way a web page can hand WebView2's host a file's location. Empty if there are none or the runtime is too old.
     */
    private static java.util.List<java.nio.file.Path> droppedFiles(MemorySegment args) {
        MemorySegment args2 = Com.queryInterface(args, WebView2Abi.ICoreWebView2WebMessageReceivedEventArgs2.IID);
        if (args2.equals(MemorySegment.NULL)) {
            return java.util.List.of();
        }
        java.util.List<String> raw = new java.util.ArrayList<>();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            if (Com.call(args2, WebView2Abi.ICoreWebView2WebMessageReceivedEventArgs2.GET_ADDITIONAL_OBJECTS, Com.HR_PTR, out) < 0) {
                return java.util.List.of();
            }
            MemorySegment view = Native.readPointer(out);
            if (view.equals(MemorySegment.NULL)) {
                return java.util.List.of();
            }
            try {
                MemorySegment count = arena.allocate(java.lang.foreign.ValueLayout.JAVA_INT);
                if (Com.call(view, WebView2Abi.ICoreWebView2ObjectCollectionView.GET_COUNT, Com.HR_PTR, count) < 0) {
                    return java.util.List.of();
                }
                int n = count.get(java.lang.foreign.ValueLayout.JAVA_INT, 0);
                if (n < 0 || n > io.github.b077as.jolt.internal.ClipboardData.MAX_FILES) {
                    return java.util.List.of(); // more than we hand out: refuse the lot rather than cut it
                }
                for (int i = 0; i < n; i++) {
                    String path = filePath(arena, view, i);
                    if (path != null) {
                        raw.add(path);
                    }
                }
            } finally {
                Com.release(view);
            }
        } finally {
            Com.release(args2);
        }
        return PageFileDrop.paths(raw);
    }

    private static String filePath(Arena arena, MemorySegment view, int index) {
        MemorySegment out = arena.allocate(ADDRESS);
        if (Com.call(view, WebView2Abi.ICoreWebView2ObjectCollectionView.GET_VALUE_AT_INDEX, Com.HR_INT_PTR, index, out) < 0) {
            return null;
        }
        MemorySegment object = Native.readPointer(out);
        if (object.equals(MemorySegment.NULL)) {
            return null;
        }
        try {
            MemorySegment file = Com.queryInterface(object, WebView2Abi.ICoreWebView2File.IID);
            if (file.equals(MemorySegment.NULL)) {
                return null; // some other kind of object
            }
            try {
                MemorySegment path = arena.allocate(ADDRESS);
                if (Com.call(file, WebView2Abi.ICoreWebView2File.GET_PATH, Com.HR_PTR, path) < 0) {
                    return null;
                }
                MemorySegment raw = Native.readPointer(path);
                String text = Native.readWide(raw);
                Win32.coTaskMemFree(raw);
                return text;
            } finally {
                Com.release(file);
            }
        } finally {
            Com.release(object);
        }
    }

    private void onWebMessage(MemorySegment sender, MemorySegment args) {
        String source = readUri(args, WebView2Abi.ICoreWebView2WebMessageReceivedEventArgs.GET_SOURCE);
        if (!guard.allowMessageFrom(source)) {
            return;
        }
        String text;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = Com.call(args, WebView2Abi.ICoreWebView2WebMessageReceivedEventArgs.TRY_GET_WEB_MESSAGE_AS_STRING,
                    Com.HR_PTR, out);
            if (hr < 0) {
                return; // not a string message; the bridge only sends strings
            }
            MemorySegment raw = Native.readPointer(out);
            text = Native.readWide(raw);
            Win32.coTaskMemFree(raw);
        }
        if (FrameProtocol.isFrameMessage(text)) {
            if (options.frameless()) {
                FrameProtocol.handle(text, this);
            }
            return; // never reaches the application, frameless or not
        }
        if (FileDropProtocol.isDropMessage(text)) {
            PageFileDrop.deliver(this, text, droppedFiles(args));
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
     * Serves {@code html} from memory at a virtual {@code https://jolt.localhost/...} address and navigates there.
     * {@code NavigateToString} rejects documents over about 2 MB ({@code E_INVALIDARG}). Intercepting the request
     * instead has no size limit, writes nothing to disk, and gives the page a real HTTPS origin. Only the newest
     * document is kept.
     */
    private void serveAndNavigate(String html) {
        String path = "/doc/" + (++documentCounter) + ".html";
        served.clear();
        served.put(path, html.getBytes(StandardCharsets.UTF_8));
        installResourceInterception();
        navigate(WebView2Abi.ICoreWebView2.NAVIGATE, VIRTUAL_ORIGIN + path);
    }

    private void installResourceInterception() {
        if (resourceInterceptionInstalled) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            Com.callChecked("AddWebResourceRequestedFilter", webView,
                    WebView2Abi.ICoreWebView2.ADD_WEB_RESOURCE_REQUESTED_FILTER, Com.HR_PTR_INT,
                    Native.allocWide(arena, VIRTUAL_ORIGIN + "/*"), RESOURCE_CONTEXT_ALL);
        }
        addEvent(WebView2Abi.ICoreWebView2.ADD_WEB_RESOURCE_REQUESTED, ComHandler.ptrPtr(
                WebView2Abi.ICoreWebView2WebResourceRequestedEventHandler.IID, this::onResourceRequested, app::deferFree));
        resourceInterceptionInstalled = true;
    }

    /**
     * Answers requests under the virtual origin: a loadHtml document from {@link #served}, the app's files and handler from
     * the {@link ResourceService} when loadResources is active, and anything else with a 404, never the network.
     */
    private void onResourceRequested(MemorySegment sender, MemorySegment args) {
        ResourceRequests.Incoming request = ResourceRequests.read(args,
                resourceService != null ? resourceService.maxBody() : ResourceService.MAX_BODY);
        String uri = request == null ? null : request.uri();
        String path = uri == null || !uri.startsWith(VIRTUAL_ORIGIN) ? "" : uri.substring(VIRTUAL_ORIGIN.length());
        int cut = indexOfAny(path, '?', '#');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        byte[] document = served.get(path);
        if (document != null) {
            ResourceRequests.respond(environment, args, 200,
                    Map.of("Content-Type", "text/html; charset=utf-8", "Cache-Control", "no-store"), document);
        } else if (resourceService != null && request != null && uri.startsWith(VIRTUAL_ORIGIN)) {
            serveResource(args, request);
        } else {
            ResourceRequests.respond(environment, args, 404, Map.of(), new byte[0]);
        }
    }

    /**
     * Hands a request to the app's {@link ResourceService} without blocking the UI thread: the event is held open with a
     * deferral, the service answers on a worker thread, and the response is put into the event back here.
     */
    private void serveResource(MemorySegment args, ResourceRequests.Incoming request) {
        ResourceService service = resourceService;
        int refused = request.body() == null ? 413 : service.admit(request.method(), request.headers(), VIRTUAL_ORIGIN);
        MemorySegment deferral = refused != 0 ? MemorySegment.NULL : ResourceRequests.deferral(args);
        if (refused != 0 || deferral.equals(MemorySegment.NULL)) {
            int status = refused != 0 ? refused : 500;
            ResourceRequests.respond(environment, args, status, ResourceService.baseHeaders(), new byte[0]);
            return;
        }
        Com.addRef(args); // the event must outlive this callback
        boolean head = request.method().equals("HEAD");
        service.serveAsync(request.method(), request.uri(), request.headers(), request.body())
                .thenAccept(response -> app.dispatch(() -> {
                    try {
                        if (!destroyed) {
                            ResourceRequests.respond(environment, args, response.status(), response.headers(),
                                    head ? new byte[0] : response.body());
                        }
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.ERROR, "answering a resource request failed", e);
                    } finally {
                        if (destroyed) {
                            Com.release(deferral);
                        } else {
                            ResourceRequests.complete(deferral); // always, or the page waits for this request forever
                        }
                        Com.release(args);
                    }
                }));
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }

    private void navigate(int slot, String text) {
        try (Arena arena = Arena.ofConfined()) {
            Com.callChecked("navigate", webView, slot, Com.HR_PTR, Native.allocWide(arena, text));
        }
    }

    private void addScriptOnDocumentCreated(String script) {
        addScriptOnDocumentCreated(script, () -> {});
    }

    private void addScriptOnDocumentCreated(String script, Runnable onRegistered) {
        ComHandler[] handler = new ComHandler[1];
        handler[0] = ComHandler.hrPtr(WebView2Abi.ICoreWebView2AddScriptToExecuteOnDocumentCreatedCompletedHandler.IID,
                (hr, id) -> {
                    try {
                        if (hr < 0) {
                            LOG.log(System.Logger.Level.ERROR, "AddScriptToExecuteOnDocumentCreated failed: " + Com.hex(hr));
                        }
                        onRegistered.run();
                    } finally {
                        handler[0].release();
                    }
                }, app::deferFree);
        try (Arena arena = Arena.ofConfined()) {
            int hr = Com.call(webView, WebView2Abi.ICoreWebView2.ADD_SCRIPT_TO_EXECUTE_ON_DOCUMENT_CREATED, Com.HR_PTR_PTR,
                    Native.allocWide(arena, script), handler[0].object);
            if (hr < 0) {
                handler[0].release();
                throw new JoltException("AddScriptToExecuteOnDocumentCreated failed: " + Com.hex(hr));
            }
        }
    }

    /**
     * Evaluates through the DevTools Protocol's {@code Runtime.evaluate}, not {@code ExecuteScript}: ExecuteScript
     * turns a script that throws or fails to compile into a successful {@code null}.
     */
    private void executeScript(String script, CompletableFuture<String> result) {
        ComHandler[] handler = new ComHandler[1];
        handler[0] = ComHandler.hrPtr(WebView2Abi.ICoreWebView2CallDevToolsProtocolMethodCompletedHandler.IID, (hr, json) -> {
            try {
                if (hr < 0) {
                    result.completeExceptionally(new JoltException("Script execution failed: " + Com.hex(hr)));
                } else {
                    try {
                        result.complete(DevToolsResult.valueOrThrow(Native.readWide(json)));
                    } catch (JoltException e) {
                        result.completeExceptionally(e);
                    }
                }
            } finally {
                handler[0].release();
            }
        }, app::deferFree);
        try (Arena arena = Arena.ofConfined()) {
            int hr = Com.call(webView, WebView2Abi.ICoreWebView2.CALL_DEV_TOOLS_PROTOCOL_METHOD, Com.HR_PTR_PTR_PTR,
                    Native.allocWide(arena, "Runtime.evaluate"), Native.allocWide(arena, DevToolsResult.evaluateParams(script)),
                    handler[0].object);
            if (hr < 0) {
                handler[0].release();
                result.completeExceptionally(new JoltException("CallDevToolsProtocolMethod failed: " + Com.hex(hr)));
            }
        }
    }

    private void onDestroyed() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        WindowClosedException closed = new WindowClosedException();
        for (CompletableFuture<?> future : new ArrayList<>(inflight)) {
            future.completeExceptionally(closed);
        }
        inflight.clear();
        pending.clear();
        ready.completeExceptionally(closed); // no-op if already completed

        if (!controller.equals(MemorySegment.NULL)) {
            Com.call(controller, WebView2Abi.ICoreWebView2Controller.CLOSE, Com.HR_VOID);
            Com.release(webView);
            Com.release(controller);
            webView = controller = MemorySegment.NULL;
        }
        for (ComHandler handler : handlers) {
            handler.release();
        }
        handlers.clear();
        menuBar = null; // Windows destroys a window's menu bar with the window
        destroyIcons();
        taskbarButtons.dispose();
        if (!backgroundBrush.equals(MemorySegment.NULL)) {
            Win32.deleteObject(backgroundBrush);
            backgroundBrush = MemorySegment.NULL;
        }
        app.windowDestroyed(hwnd);
        for (Runnable listener : closedListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "onClosed listener failed", e);
            }
        }
    }
}
