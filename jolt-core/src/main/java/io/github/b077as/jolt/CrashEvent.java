package io.github.b077as.jolt;

/**
 * Something went wrong with the process that runs the window's page. The Java application is not affected. Use
 * {@link WebWindow#onCrash} to react, and {@link WebWindow#setReloadOnCrash} to have the page reloaded automatically.
 *
 * @param reason    what happened
 * @param reloading whether Jolt is reloading the page because of this event. Always false unless
 *                  {@link WebWindow#setReloadOnCrash} is on, and false when the page has crashed too often to try again
 */
public record CrashEvent(Reason reason, boolean reloading) {

    public enum Reason {
        /** The page's process ended. The window is blank until the page is loaded again ({@link WebWindow#reload}). */
        PROCESS_EXITED,
        /** The page's process was ended because it used too much memory (Linux). The window is blank as above. */
        OUT_OF_MEMORY,
        /**
         * The page has stopped responding, for example in an endless loop (Windows). It may recover by itself; this is
         * reported again for as long as it lasts, and never reloads automatically.
         */
        UNRESPONSIVE,
        /**
         * The whole web view runtime ended, taking every window's page with it (Windows). Reloading cannot help: close
         * the window and create a new one.
         */
        WEB_VIEW_LOST
    }
}
