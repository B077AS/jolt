package io.github.b077as.jolt;

/** What happens when the user closes a window with its close button (or Alt+F4), see {@link WebWindow#setCloseBehavior}. */
public enum CloseBehavior {
    /** The window is destroyed. This is the default. */
    CLOSE,
    /**
     * The window is only hidden and the application keeps running, typically with a {@link Tray} icon to bring it back
     * ({@link WebWindow#focus()} shows it again). Closing from code ({@link WebWindow#close()}, {@link Application#quit()}) and
     * the end of the user's session still really close it.
     */
    HIDE
}
