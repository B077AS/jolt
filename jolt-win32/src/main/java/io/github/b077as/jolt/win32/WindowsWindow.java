package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.BlockedEvent;
import io.github.b077as.jolt.ColorScheme;
import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.SecurityPolicy;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowClosedException;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.internal.BridgeScript;
import io.github.b077as.jolt.internal.Icons;
import io.github.b077as.jolt.internal.NativeDialogHost;
import io.github.b077as.jolt.internal.NativeResourceHost;
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
final class WindowsWindow implements WebWindow, NativeResourceHost, NativeDialogHost {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.win32");
    private static final int STYLE = Win32.WS_OVERLAPPEDWINDOW | Win32.WS_CLIPCHILDREN | Win32.WS_CLIPSIBLINGS;
    private static final int MOVE_FOCUS_PROGRAMMATIC = 0;
    /** Requests to this origin are answered from memory and never reach the network. */
    private static final String VIRTUAL_ORIGIN = SecurityPolicy.APP_ORIGIN;
    private static final int PERMISSION_STATE_DENY = 2; // COREWEBVIEW2_PERMISSION_STATE_DENY
    private static final int RESOURCE_CONTEXT_ALL = 0; // COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL

    private final WindowsApplication app;
    private final WindowOptions options;
    private final SecurityGuard guard;
    private final MemorySegment hwnd;
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
    /** {@link #destroyNow} has started; the window goes as soon as an open dialog has returned. */
    private volatile boolean closing;
    /** Fills the window behind the web view, so what shows before the web view has painted is the page's colour, not white. */
    private MemorySegment backgroundBrush = MemorySegment.NULL;
    /** The window was asked to be maximized while hidden (or at creation): it is shown maximized. Readable from any thread. */
    private volatile boolean maximizeOnFirstShow;

    /** Must be called on the UI thread. */
    WindowsWindow(WindowsApplication app, WindowOptions options) {
        this.app = app;
        this.options = options;
        this.guard = new SecurityGuard(options.security(), app::dispatch);

        int[] error = new int[1];
        try (Arena arena = Arena.ofConfined()) {
            this.hwnd = Win32.createWindow(0, app.className(), Native.allocWide(arena, options.title()), STYLE,
                    Win32.CW_USEDEFAULT, Win32.CW_USEDEFAULT, Win32.CW_USEDEFAULT, Win32.CW_USEDEFAULT,
                    MemorySegment.NULL, app.hInstance(), error);
        }
        if (hwnd.equals(MemorySegment.NULL)) {
            throw new JoltException("CreateWindowExW failed: Win32 error " + error[0]);
        }
        app.register(hwnd, this);
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
        if (options.centered() && !options.maximized()) {
            center();
        }
        maximizeOnFirstShow = options.maximized();
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
        navigate(WebView2Abi.ICoreWebView2.NAVIGATE, VIRTUAL_ORIGIN + "/");
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
    public void maximize() {
        app.dispatch(() -> {
            if (destroyed) {
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
        switch (msg) {
            case Win32.WM_SIZE -> {
                resizeWebView();
                return 0L;
            }
            case Win32.WM_GETMINMAXINFO -> {
                if (options.minWidth() == 0 && options.minHeight() == 0) {
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
            case Win32.WM_CLOSE -> {
                Win32.destroyWindow(hwnd);
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
            Win32.adjustWindowRectExForDpi(rect, STYLE, 0, dpi);
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
            Win32.adjustWindowRectExForDpi(rect, STYLE, 0, dpi);
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
            resizeWebView();
            Com.call(controller, WebView2Abi.ICoreWebView2Controller.MOVE_FOCUS, Com.HR_INT, MOVE_FOCUS_PROGRAMMATIC);

            // Operations queued by callers run only after the bridge script is registered, so every navigation sees it.
            addScriptOnDocumentCreated(BridgeScript.build("window.chrome.webview.postMessage(m)", options.security().bridgeInSubframes()), this::finishInit);
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
            } finally {
                Com.release(settings);
            }
        }
    }

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
        if (!guard.allowPermission(uri)) {
            Com.call(args, WebView2Abi.ICoreWebView2PermissionRequestedEventArgs.PUT_STATE, Com.HR_INT, PERMISSION_STATE_DENY);
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

    private void onWebMessage(MemorySegment sender, MemorySegment args) {
        if (!guard.allowMessageFrom(readUri(args, WebView2Abi.ICoreWebView2WebMessageReceivedEventArgs.GET_SOURCE))) {
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
        destroyIcons();
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
