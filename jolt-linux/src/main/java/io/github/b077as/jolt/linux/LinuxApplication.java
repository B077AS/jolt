package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.AppOptions;
import io.github.b077as.jolt.Autostart;
import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.Tray;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOptions;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the GTK main loop on the thread that calls {@link #run(Runnable)}.
 *
 * <p>Cross-thread work goes through {@link #dispatch}: tasks are queued and one {@code g_idle_add} source is
 * armed (it is thread-safe and wakes the main context). GDK picks X11 or Wayland at startup; nothing here is
 * backend-specific.
 *
 * <p>Native callbacks are the process-lifetime stubs in {@link Callbacks}; they reach this object by id through
 * {@link Registry}, never by pointer, so a callback GLib delivers after this application has finished is harmless.
 */
final class LinuxApplication implements Application {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.linux");

    private final AppOptions options;
    private final long id = Registry.add(this);
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean wakePending = new AtomicBoolean();

    // UI thread only
    private final Map<Long, LinuxWindow> windows = new java.util.concurrent.ConcurrentHashMap<>();
    /** Tray icons; UI thread only. */
    private final List<GtkTray> trays = new ArrayList<>();
    private MemorySegment webContext = MemorySegment.NULL;
    private boolean resourceSchemeRegistered;
    private boolean mainLoopRunning;

    private volatile Thread uiThread;
    private volatile boolean loopReady;
    private boolean started;

    LinuxApplication(AppOptions options) {
        this.options = options;
    }

    record PendingEval(CompletableFuture<String> future) {}

    // ---- Application ----

    /** Only one application loop can exist per process: GTK has a single main loop. */
    private static final AtomicBoolean LOOP_IN_USE = new AtomicBoolean();

    /**
     * Runs the application on the process-wide GTK thread (see {@link GtkThread}) and blocks the caller until it
     * ends. The caller is therefore <em>not</em> the UI thread on Linux; {@code onStart} runs on it.
     */
    @Override
    public void run(Runnable onStart) {
        if (started) {
            throw new IllegalStateException("run() can only be called once per Application");
        }
        started = true;
        if (!LOOP_IN_USE.compareAndSet(false, true)) {
            throw new IllegalStateException("Another Application is already running in this process; "
                    + "GTK supports one main loop at a time");
        }
        Future<?> job = GtkThread.submit(() -> {
            try {
                runLoop(onStart);
            } finally {
                LOOP_IN_USE.set(false);
            }
        });
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    job.get();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true; // stop the application, keep waiting for it to end, then re-assert the interrupt
                    quit();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException r) {
                        throw r;
                    }
                    if (cause instanceof Error err) {
                        throw err;
                    }
                    throw new JoltException("application failed", cause);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runLoop(Runnable onStart) {
        uiThread = Thread.currentThread();
        Throwable startFailure = null;
        try {
            try {
                Gtk.load();
            } catch (LinkageError | IllegalArgumentException e) {
                throw new JoltException("GTK 3 / WebKitGTK 4.1 are not installed: " + LinuxPlatform.INSTALL_HINT, e);
            }
            Gtk.setProgramName(options.appId());
            if (options.quietNativeLogs()) {
                Gtk.hideKnownNoise();
            }
            if (!Gtk.initCheck()) {
                throw new JoltException("GTK could not open a display (is DISPLAY or WAYLAND_DISPLAY set?)");
            }
            loopReady = true;
            webContext = createWebContext();
            try {
                onStart.run();
            } catch (Throwable t) {
                startFailure = t;
            }
            if (startFailure == null) {
                // Tasks queued so far (including a close of the last window) must run inside the loop, where
                // gtk_main_quit works; outside a loop it is a no-op and the loop would then run forever.
                armWakeUp();
                mainLoopRunning = true;
                try {
                    Gtk.main();
                } finally {
                    mainLoopRunning = false;
                }
            }
        } finally {
            shutdown();
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
            for (LinuxWindow window : new ArrayList<>(windows.values())) {
                window.destroyNow();
            }
            quitLoop();
        });
    }

    /** {@code gtk_main_quit}, but only while the main loop is actually running. */
    private void quitLoop() {
        if (mainLoopRunning) {
            Gtk.mainQuit();
        }
    }

    @Override
    public void dispatch(Runnable task) {
        queue.add(task);
        if (loopReady) {
            armWakeUp();
        }
    }

    private void armWakeUp() {
        if (wakePending.compareAndSet(false, true)) {
            Gtk.idleAdd(Callbacks.IDLE, MemorySegment.ofAddress(id));
        }
    }

    @Override
    public boolean isUiThread() {
        return Thread.currentThread() == uiThread;
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> openExternal(java.net.URI uri) {
        String target = io.github.b077as.jolt.internal.ExternalUri.check(uri);
        return submit(() -> Gtk.launchDefaultForUri(target));
    }

    @Override
    public CompletableFuture<Boolean> notify(io.github.b077as.jolt.Notification notification) {
        java.util.Objects.requireNonNull(notification, "notification");
        // D-Bus call that can wait for the notification service: keep it off the UI thread
        return CompletableFuture.supplyAsync(() -> notifications().send(options.appId(), notification),
                Thread::startVirtualThread);
    }

    private GtkNotifications notifications;

    private synchronized GtkNotifications notifications() {
        if (notifications == null) {
            notifications = new GtkNotifications(this, id);
        }
        return notifications;
    }

    /** A signal from the notification service (button pressed, notification closed); GLib main loop thread. */
    void onNotificationSignal(String signal, MemorySegment parameters) {
        GtkNotifications n;
        synchronized (this) {
            n = notifications;
        }
        if (n != null) {
            n.onSignal(signal, parameters);
        }
    }

    @Override
    public boolean isTraySupported() {
        return GtkTray.hostAvailable();
    }

    @Override
    public Tray createTray(byte[] icon, String tooltip) {
        if (!isUiThread()) {
            throw new IllegalStateException("createTray must be called on the UI thread (inside run's onStart)");
        }
        GtkTray tray = new GtkTray(this, options.appId(), icon, tooltip);
        trays.add(tray);
        return tray;
    }

    void trayClosed(GtkTray tray) {
        trays.remove(tray);
    }

    @Override
    public Autostart autostart() {
        return new XdgAutostart(options.appId(), XdgAutostart.defaultDirectory());
    }

    @Override
    public WebWindow createWindow(WindowOptions windowOptions) {
        if (!isUiThread()) {
            throw new IllegalStateException("createWindow must be called on the UI thread (inside run's onStart)");
        }
        return new LinuxWindow(this, windowOptions);
    }

    @Override
    public List<WebWindow> windows() {
        return List.copyOf(windows.values());
    }

    @Override
    public void close() {
        queue.clear(); // tasks dispatched after the loop ended are discarded, not run on the closing thread
        Registry.remove(id);
    }

    // ---- used by windows and callbacks (UI thread) ----

    AppOptions options() {
        return options;
    }

    MemorySegment webContext() {
        return webContext;
    }

    /** Registers a window and returns the id that native code carries as {@code user_data}. */
    long register(LinuxWindow window) {
        long windowId = Registry.add(window);
        windows.put(windowId, window);
        return windowId;
    }

    void windowDestroyed(long windowId) {
        windows.remove(windowId);
        Registry.remove(windowId);
        if (windows.isEmpty() && options.quitWhenLastWindowClosed()) {
            quitLoop();
        }
    }

    /** Registers the app's own URI scheme on the web context, once. */
    void ensureResourceScheme() {
        if (!resourceSchemeRegistered) {
            GtkResources.registerScheme(webContext, Callbacks.URI_SCHEME_REQUEST, MemorySegment.ofAddress(id));
            resourceSchemeRegistered = true;
        }
    }

    /** A request for the app's own scheme: handed to the window whose web view made it. */
    void onSchemeRequest(MemorySegment request) {
        long webView = GtkResources.webViewOf(request);
        for (LinuxWindow window : windows.values()) {
            if (window.nativeWebView().address() == webView) {
                window.onSchemeRequest(request);
                return;
            }
        }
        GtkResources.respond(request, 404, io.github.b077as.jolt.internal.ResourceService.baseHeaders(), new byte[0]);
    }

    long registerEval(CompletableFuture<String> future) {
        return Registry.add(new PendingEval(future));
    }

    /** Runs queued tasks; called from the idle callback. */
    void drain() {
        wakePending.set(false);
        Runnable task;
        while ((task = queue.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.ERROR, "dispatched task failed", t);
            }
        }
    }

    // ---- internals ----

    private MemorySegment createWebContext() {
        try {
            Path data = options.dataDirectory().resolve("webkit");
            Path cache = options.dataDirectory().resolve("webkit-cache");
            Files.createDirectories(data);
            Files.createDirectories(cache);
            return Gtk.newWebContext(data.toString(), cache.toString());
        } catch (IOException e) {
            throw new JoltException("Cannot create data directory " + options.dataDirectory(), e);
        }
    }

    private void shutdown() {
        try {
            synchronized (this) {
                if (notifications != null) {
                    notifications.close();
                    notifications = null;
                }
                for (GtkTray tray : new ArrayList<>(trays)) {
                    tray.closeNow(); // leaves the bus name and the exported objects before the callbacks go away
                }
            }
            for (LinuxWindow window : new ArrayList<>(windows.values())) {
                window.destroyNow();
            }
            drain();
        } finally {
            loopReady = false;
            if (!webContext.equals(MemorySegment.NULL)) {
                Gtk.unref(webContext);
                webContext = MemorySegment.NULL;
            }
            uiThread = null; // the calling thread is no longer the UI thread once the loop has ended
        }
    }
}
