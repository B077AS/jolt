package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.SessionEndReason;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Tells an application's listeners that it is about to be ended from outside, once.
 *
 * <p>Termination (a signal, a closing console) is seen through a JVM shutdown hook, which exists only while the
 * application's loop runs and has listeners: it hands the listeners to the UI thread, asks the application to quit, and
 * holds the JVM until the loop has ended or {@link #TIMEOUT_MILLIS} have passed. A backend that learns of a sign-out from
 * the system calls {@link #fire} itself.
 */
public final class SessionEnds {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt");

    /** How long the shutdown hook waits for the listeners and the application's own cleanup. */
    static final long TIMEOUT_MILLIS = 5_000;

    private final Consumer<Runnable> dispatch;
    private final Runnable quit;
    private final CopyOnWriteArrayList<Consumer<SessionEndReason>> listeners = new CopyOnWriteArrayList<>();
    private boolean fired;
    private Thread uiThread;
    private Thread hook;
    private CountDownLatch ended;

    public SessionEnds(Consumer<Runnable> dispatch, Runnable quit) {
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.quit = Objects.requireNonNull(quit, "quit");
    }

    public synchronized void add(Consumer<SessionEndReason> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        installHook();
    }

    /** The application's loop is running on {@code thread}. */
    public synchronized void started(Thread thread) {
        uiThread = thread;
        ended = new CountDownLatch(1);
        installHook();
    }

    /** The loop has ended and the application has cleaned up. */
    public synchronized void stopped() {
        if (ended != null) {
            ended.countDown();
        }
        uiThread = null;
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // the JVM is shutting down, so the hook is running (or about to) and finds the loop ended
            }
            hook = null;
        }
    }

    /** Calls the listeners, if they have not been called yet. UI thread. */
    public void fire(SessionEndReason reason) {
        synchronized (this) {
            if (fired) {
                return;
            }
            fired = true;
        }
        for (Consumer<SessionEndReason> listener : listeners) {
            try {
                listener.accept(reason);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "onSessionEnd listener failed", e);
            }
        }
    }

    private void installHook() {
        if (hook != null || uiThread == null || listeners.isEmpty()) {
            return;
        }
        Thread thread = new Thread(this::terminating, "jolt-session-end");
        try {
            Runtime.getRuntime().addShutdownHook(thread);
            hook = thread;
        } catch (IllegalStateException e) {
            LOG.log(System.Logger.Level.DEBUG, "already shutting down; no session-end hook");
        }
    }

    /** The shutdown hook. */
    void terminating() {
        Thread ui;
        CountDownLatch done;
        synchronized (this) {
            ui = uiThread;
            done = ended;
        }
        if (ui == null) {
            return;
        }
        // System.exit called on the UI thread: it is blocked until this hook returns, so there is nobody to hand work to
        for (StackTraceElement frame : ui.getStackTrace()) {
            if (frame.getClassName().equals("java.lang.Shutdown")) {
                LOG.log(System.Logger.Level.WARNING, "onSessionEnd is not called when System.exit is called on the UI thread");
                return;
            }
        }
        dispatch.accept(() -> {
            try {
                fire(SessionEndReason.TERMINATION);
            } finally {
                quit.run();
            }
        });
        try {
            if (!done.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "the application did not end within " + TIMEOUT_MILLIS + " ms; the process ends anyway");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
