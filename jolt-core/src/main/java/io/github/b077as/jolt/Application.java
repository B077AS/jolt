package io.github.b077as.jolt;

import io.github.b077as.jolt.spi.Platforms;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A running desktop application: owns the native UI event loop and the windows on it.
 *
 * <p><b>Threading.</b> {@link #run(Runnable)} blocks until the application ends. Windows must be created on the UI
 * thread, which is where {@code onStart} runs. Which thread that is depends on the platform: on Windows it is the
 * thread that called {@code run}; on Linux, GTK and WebKit must stay on one thread for the life of the process, so
 * the backend runs the loop on its own thread and the caller just waits. Do not rely on either; use
 * {@link #isUiThread()}. Every other method on {@link WebWindow} and {@link Application} may be called from any
 * thread and is marshalled to the UI thread for you. Never block the UI thread waiting on a future that the UI
 * thread has to complete.
 *
 * <p>Only one application can run at a time in a process. Tasks passed to {@link #dispatch} after the loop has
 * ended are discarded or may never run.
 */
public interface Application extends AutoCloseable {

    /** Creates an application on the best supported platform backend found on the class path. */
    static Application create(AppOptions options) {
        return Platforms.select().createApplication(options);
    }

    /**
     * Initializes the platform on the calling thread, invokes {@code onStart} on it, then runs the event loop
     * until {@link #quit()} is called or the last window closes. Blocks.
     */
    void run(Runnable onStart);

    /** Equivalent to {@code run(() -> {})}. */
    default void run() {
        run(() -> {});
    }

    /** Asks the event loop to stop. Safe from any thread. */
    void quit();

    /** Runs {@code task} on the UI thread, in submission order. Safe from any thread. */
    void dispatch(Runnable task);

    /** Whether the calling thread is the UI thread. */
    boolean isUiThread();

    /** Runs {@code task} on the UI thread and completes the returned future with its result. */
    default <T> CompletableFuture<T> submit(Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        dispatch(() -> {
            try {
                result.complete(task.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    /**
     * Opens a web or mail address in the user's default browser or mail program. Only {@code http}, {@code https}
     * and {@code mailto} are accepted; anything else (for example {@code file:}) throws
     * {@link IllegalArgumentException}, because handing it to the shell could start a program. The future completes
     * with whether the system accepted the request, not whether the page loaded.
     */
    default CompletableFuture<Boolean> openExternal(java.net.URI uri) {
        throw new UnsupportedOperationException("this platform cannot open external addresses");
    }

    /**
     * Shows a system notification. The future completes with whether the system accepted it; it does not say whether
     * the user saw it (they may have notifications turned off, or be in a focus mode). Safe from any thread.
     *
     * <p>Windows: a WinRT toast. The first call registers {@code appId} under
     * {@code HKCU\Software\Classes\AppUserModelId} so Windows has a name to show; nothing is installed. Linux: the
     * desktop's notification service ({@code org.freedesktop.Notifications}); the future completes with {@code false}
     * when there is none.
     */
    default CompletableFuture<Boolean> notify(Notification notification) {
        throw new UnsupportedOperationException("this platform cannot show notifications");
    }

    /** Creates a window hosting a web view. Must be called on the UI thread. */
    WebWindow createWindow(WindowOptions options);

    /** Releases native resources. Call after {@link #run(Runnable)} has returned. */
    @Override
    void close();
}
