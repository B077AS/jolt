package io.github.b077as.jolt.spring;

import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.WebWindow;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The desktop side of an application started with {@link JoltSpringApplication}: its Jolt {@link Application} and main
 * window. A bean in the application context (also {@code Application} and {@code WebWindow} themselves can be injected).
 *
 * <p>The window is created while Spring is still starting, so asking for it from a bean waits until it exists, which is
 * normally already the case. It is not in the context of a plain {@code SpringApplication} (tests with {@code @SpringBootTest},
 * for example), where no window is opened.
 */
public final class JoltRuntime {

    /** The bean name. */
    public static final String BEAN_NAME = "joltRuntime";

    private static final long WAIT_SECONDS = 60;

    final CompletableFuture<Application> application = new CompletableFuture<>();
    final CompletableFuture<WebWindow> window = new CompletableFuture<>();
    final CompletableFuture<Void> shown = new CompletableFuture<>();
    volatile JoltProperties.Mode mode;

    JoltRuntime() {
    }

    /** The Jolt application: the UI thread, other windows, tray, notifications. */
    public Application application() {
        return await(application, "application");
    }

    /** The main window. */
    public WebWindow window() {
        return await(window, "window");
    }

    /**
     * Completes once the main window is on screen. The moment to tell a launcher that it can close, for example with jpack:
     * {@code runtime.whenShown().thenRun(() -> jpack.notifyShown())}.
     */
    public CompletableFuture<Void> whenShown() {
        return shown.copy();
    }

    /** How the page reaches the application: {@code RPC} or {@code WEB}. Null until the page is being loaded. */
    public JoltProperties.Mode mode() {
        return mode;
    }

    private static <T> T await(CompletableFuture<T> future, String what) {
        try {
            return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("The Jolt " + what + " could not be created", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("The Jolt " + what + " was not created within " + WAIT_SECONDS + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the Jolt " + what, e);
        }
    }
}
