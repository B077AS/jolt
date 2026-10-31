package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.AppOptions;
import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.internal.ffi.Native;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Owns the Win32 message loop on the thread that calls {@link #run(Runnable)}.
 *
 * <p>Cross-thread work goes through {@link #dispatch}: tasks are queued and a single {@code WM_APP} message is
 * posted to a hidden message-only window. Using a window (instead of a thread message) keeps the queue draining
 * while Windows runs its own modal loops, such as dragging or resizing a window.
 */
final class WindowsApplication implements Application {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.win32");
    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

    /** Unique per application: a window class is process-global and its procedure points into this instance. */
    private final String windowClass = "JoltWindow." + CLASS_COUNTER.incrementAndGet();
    private final AppOptions options;
    private final Arena arena = Arena.ofShared();
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Runnable> frees = new ConcurrentLinkedQueue<>();
    private volatile boolean loopRunning;
    private final AtomicBoolean wakePending = new AtomicBoolean();
    /** Live windows by HWND; changed on the UI thread only, readable from any thread. */
    private final Map<Long, WindowsWindow> windows = new java.util.concurrent.ConcurrentHashMap<>();

    private volatile Thread uiThread;
    private volatile MemorySegment dispatchWindow;
    private MemorySegment hInstance = MemorySegment.NULL;
    private MemorySegment className = MemorySegment.NULL;
    private boolean started;
    private CompletableFuture<MemorySegment> environment;

    WindowsApplication(AppOptions options) {
        this.options = options;
    }

    AppOptions options() {
        return options;
    }

    MemorySegment hInstance() {
        return hInstance;
    }

    MemorySegment className() {
        return className;
    }

    // ---- Application ----

    @Override
    public void run(Runnable onStart) {
        if (started) {
            throw new IllegalStateException("run() can only be called once per Application");
        }
        started = true;
        uiThread = Thread.currentThread();
        boolean comInitialized = false;
        Throwable startFailure = null;
        try {
            Win32.setPerMonitorDpiAwareness(); // fails harmlessly if the process already chose a mode
            int hr = Win32.coInitializeEx(Win32.COINIT_APARTMENTTHREADED);
            if (hr < 0) {
                throw new JoltException("CoInitializeEx failed: " + Com.hex(hr)
                        + " (the UI thread must be a single-threaded apartment)");
            }
            comInitialized = true;
            registerWindowClass();
            createDispatchWindow();
            try (Arena local = Arena.ofConfined()) {
                // A quit left on this thread by something earlier must not end this loop. Done before onStart so a
                // quit that onStart itself requests is kept.
                Win32.discardPendingQuit(local.allocate(Win32.MSG_SIZE, 8));
            }
            loopRunning = true; // from here on quit requests are honoured, even ones made before the pump starts

            try {
                onStart.run();
            } catch (Throwable t) {
                startFailure = t;
            }
            if (startFailure == null) {
                drain();
                pump();
            }
        } finally {
            shutdown(comInitialized);
            uiThread = null; // the calling thread is no longer the UI thread once the loop has ended
        }
        if (startFailure instanceof RuntimeException r) {
            throw r;
        }
        if (startFailure instanceof Error e) {
            throw e;
        }
        if (startFailure != null) {
            throw new JoltException("onStart failed", startFailure);
        }
    }

    @Override
    public void quit() {
        dispatch(() -> {
            for (WindowsWindow window : new ArrayList<>(windows.values())) {
                window.destroyNow();
            }
            postQuit();
        });
    }

    /**
     * Posts WM_QUIT, but only while the loop runs. {@code PostQuitMessage} sets a flag on the <em>thread</em>; one
     * posted after the loop ended would make the next Application on this thread exit immediately.
     */
    private void postQuit() {
        if (loopRunning) {
            Win32.postQuitMessage(0);
        }
    }

    @Override
    public void dispatch(Runnable task) {
        queue.add(task);
        wake();
    }

    /** Queues internal cleanup (releasing COM callbacks). Unlike user tasks it still runs when the loop is over. */
    void deferFree(Runnable cleanup) {
        frees.add(cleanup);
        wake();
    }

    private void wake() {
        MemorySegment target = dispatchWindow;
        if (target != null && wakePending.compareAndSet(false, true) && !Win32.postMessage(target, Win32.WM_APP, 0, 0)) {
            wakePending.set(false); // posted-message queue full; the next wake retries and the loop drains everything
        }
    }

    @Override
    public boolean isUiThread() {
        return Thread.currentThread() == uiThread;
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> openExternal(java.net.URI uri) {
        String target = io.github.b077as.jolt.internal.ExternalUri.check(uri);
        return submit(() -> Win32.shellOpen(target));
    }

    private WindowsToasts toasts;

    @Override
    public CompletableFuture<Boolean> notify(io.github.b077as.jolt.Notification notification) {
        java.util.Objects.requireNonNull(notification, "notification");
        return submit(() -> {
            if (toasts == null) {
                toasts = new WindowsToasts(options.appId(), this);
            }
            return toasts.show(notification);
        });
    }

    @Override
    public WebWindow createWindow(WindowOptions windowOptions) {
        if (!isUiThread()) {
            throw new IllegalStateException("createWindow must be called on the UI thread (inside run's onStart)");
        }
        return new WindowsWindow(this, windowOptions);
    }

    @Override
    public List<WebWindow> windows() {
        return List.copyOf(windows.values());
    }

    @Override
    public void close() {
        runFrees();
        queue.clear(); // user tasks dispatched after the loop ended are discarded, not run on the closing thread
        arena.close();
    }

    // ---- used by windows (UI thread) ----

    void register(MemorySegment hwnd, WindowsWindow window) {
        windows.put(hwnd.address(), window);
    }

    void windowDestroyed(MemorySegment hwnd) {
        windows.remove(hwnd.address());
        if (windows.isEmpty() && options.quitWhenLastWindowClosed()) {
            postQuit();
        }
    }

    /** The shared WebView2 environment; created on first use. */
    CompletableFuture<MemorySegment> environment() {
        if (environment != null) {
            return environment;
        }
        CompletableFuture<MemorySegment> result = environment = new CompletableFuture<>();
        try {
            Path userData = options.dataDirectory().resolve("WebView2");
            Files.createDirectories(userData);
            ComHandler[] handler = new ComHandler[1];
            handler[0] = ComHandler.hrPtr(WebView2Abi.ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler.IID,
                    (hr, env) -> {
                        if (hr < 0 || env.equals(MemorySegment.NULL)) {
                            result.completeExceptionally(new JoltException(
                                    "Creating the WebView2 environment failed: " + Com.hex(hr)));
                        } else {
                            Com.addRef(env); // the callback's reference ends when it returns
                            result.complete(env);
                        }
                        handler[0].release();
                    }, this::deferFree);
            int hr = WebView2Loader.get().createEnvironment(userData, handler[0].object);
            if (hr < 0) {
                handler[0].release();
                result.completeExceptionally(new JoltException(
                        "CreateCoreWebView2EnvironmentWithOptions failed: " + Com.hex(hr)));
            }
        } catch (IOException e) {
            result.completeExceptionally(new JoltException("Cannot create data directory " + options.dataDirectory(), e));
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    // ---- internals ----

    private void registerWindowClass() {
        hInstance = Win32.getModuleHandle();
        className = Native.allocWide(arena, windowClass);
        MethodHandle target;
        try {
            target = MethodHandles.lookup().findVirtual(WindowsApplication.class, "windowProc",
                    MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class)).bindTo(this);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        MemorySegment proc = Native.LINKER.upcallStub(Native.guarded(target, 0),
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG), arena);

        MemorySegment wc = arena.allocate(Win32.WNDCLASSEXW);
        wc.set(JAVA_INT, Win32.WNDCLASSEXW.byteOffset(PathElement.groupElement("cbSize")), (int) Win32.WNDCLASSEXW.byteSize());
        wc.set(ADDRESS, Win32.WNDCLASSEXW.byteOffset(PathElement.groupElement("lpfnWndProc")), proc);
        wc.set(ADDRESS, Win32.WNDCLASSEXW.byteOffset(PathElement.groupElement("hInstance")), hInstance);
        wc.set(ADDRESS, Win32.WNDCLASSEXW.byteOffset(PathElement.groupElement("hCursor")), Win32.loadCursor(Win32.IDC_ARROW));
        wc.set(ADDRESS, Win32.WNDCLASSEXW.byteOffset(PathElement.groupElement("lpszClassName")), className);

        int[] error = new int[1];
        if (Win32.registerClass(wc, error) == 0) {
            throw new JoltException("RegisterClassExW failed: Win32 error " + error[0]);
        }
    }

    private void createDispatchWindow() {
        int[] error = new int[1];
        MemorySegment hwnd = Win32.createWindow(0, className, className, 0, 0, 0, 0, 0,
                Win32.pointer(Win32.HWND_MESSAGE), hInstance, error);
        if (hwnd.equals(MemorySegment.NULL)) {
            throw new JoltException("Creating the dispatch window failed: Win32 error " + error[0]);
        }
        dispatchWindow = hwnd;
    }

    private void pump() {
        try (Arena local = Arena.ofConfined()) {
            MemorySegment msg = local.allocate(Win32.MSG_SIZE, 8);
            while (true) {
                int result = Win32.getMessage(msg);
                if (result == 0) {
                    return; // WM_QUIT
                }
                if (result < 0) {
                    throw new JoltException("GetMessageW failed");
                }
                Win32.translateAndDispatch(msg);
            }
        }
    }

    /** Runs internal cleanup, then user tasks, until both queues are empty. */
    private void drain() {
        wakePending.set(false);
        do {
            runFrees();
            Runnable task;
            while ((task = queue.poll()) != null) {
                try {
                    task.run();
                } catch (Throwable t) {
                    LOG.log(System.Logger.Level.ERROR, "dispatched task failed", t);
                }
            }
        } while (!frees.isEmpty());
    }

    private void runFrees() {
        Runnable cleanup;
        while ((cleanup = frees.poll()) != null) {
            try {
                cleanup.run();
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.ERROR, "cleanup failed", t);
            }
        }
    }

    private void shutdown(boolean comInitialized) {
        loopRunning = false; // windows destroyed below must not leave a quit flag on this thread
        try {
            if (toasts != null) {
                toasts.close(); // a click after this point must not reach callbacks that are about to be freed
            }
            for (WindowsWindow window : new ArrayList<>(windows.values())) {
                window.destroyNow();
            }
            runFrees();
            queue.clear(); // tasks still queued when the loop ended are dropped
            MemorySegment target = dispatchWindow;
            dispatchWindow = null;
            if (target != null) {
                Win32.destroyWindow(target);
            }
            if (environment != null && environment.isDone() && !environment.isCompletedExceptionally()) {
                Com.release(environment.join());
            }
            if (!className.equals(MemorySegment.NULL)) {
                Win32.unregisterClass(className, hInstance);
            }
        } finally {
            if (comInitialized) {
                Win32.coUninitialize();
            }
        }
    }

    /** Window procedure for every Jolt window class instance; called by Windows on the UI thread. */
    private long windowProc(MemorySegment hwnd, int msg, long wParam, long lParam) {
        if (msg == Win32.WM_APP) {
            MemorySegment target = dispatchWindow;
            if (target != null && target.address() == hwnd.address()) {
                drain();
                return 0;
            }
        }
        WindowsWindow window = windows.get(hwnd.address());
        if (window != null) {
            Long handled = window.handleMessage(msg, wParam, lParam);
            if (handled != null) {
                return handled;
            }
        }
        return Win32.defWindowProc(hwnd, msg, wParam, lParam);
    }
}
