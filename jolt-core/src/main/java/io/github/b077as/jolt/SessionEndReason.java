package io.github.b077as.jolt;

/** Why the application is about to be ended from outside, see {@link Application#onSessionEnd}. */
public enum SessionEndReason {
    /** The user is signing out, or the computer is shutting down or restarting (Windows). */
    SIGN_OUT_OR_SHUTDOWN,
    /**
     * The process is being terminated: {@code SIGTERM}, {@code SIGINT} (Ctrl+C) or {@code SIGHUP} on Linux, a console
     * closing, or {@code System.exit} called from a thread other than the UI thread.
     */
    TERMINATION
}
