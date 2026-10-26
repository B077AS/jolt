package io.github.b077as.jolt;

/** Completes pending {@link WebWindow#eval} calls, and fails new ones, once the window has been closed. */
public class WindowClosedException extends JoltException {

    public WindowClosedException() {
        super("window is closed");
    }
}
