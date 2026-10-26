package io.github.b077as.jolt.linux;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The one thread that ever runs GTK and WebKitGTK in this process.
 *
 * <p>Both libraries bind themselves to the first thread that initializes them. Using them from a different thread
 * later does not fail politely: WebKit's main-thread assertions {@code abort()} the whole JVM with no message
 * (verified: thread A then thread B, or the main thread then another thread, both kill the process, while any
 * number of applications on one thread work). So every application loop is run here, whichever thread calls
 * {@code Application.run}, and that call simply blocks until the loop ends.
 */
final class GtkThread {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jolt-gtk");
        thread.setDaemon(true); // callers of run() block meanwhile, which keeps the JVM alive when it matters
        return thread;
    });

    private GtkThread() {}

    static Future<?> submit(Runnable job) {
        return EXECUTOR.submit(job);
    }
}
