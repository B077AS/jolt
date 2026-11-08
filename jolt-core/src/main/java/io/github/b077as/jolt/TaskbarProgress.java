package io.github.b077as.jolt;

import java.util.Objects;

/**
 * Progress drawn on a window's taskbar icon (Windows; best effort on Linux), see {@link WebWindow#setTaskbarProgress}: a green bar along the icon for a
 * download, an export or an upload, so the user can follow it without bringing the window up. Immutable.
 *
 * @param state    what the bar shows
 * @param fraction how much is done, from 0 to 1; ignored (and 0) for {@link State#NONE} and {@link State#INDETERMINATE}
 */
public record TaskbarProgress(State state, double fraction) {

    public enum State {
        /** No progress: the icon is plain. */
        NONE,
        /** Work of unknown length: a moving, green marquee. */
        INDETERMINATE,
        /** The usual green bar. */
        NORMAL,
        /** A red bar: something failed, and the work is stopped. */
        ERROR,
        /** A yellow bar: the work is paused or needs the user. */
        PAUSED
    }

    private static final TaskbarProgress CLEARED = new TaskbarProgress(State.NONE, 0);
    private static final TaskbarProgress BUSY = new TaskbarProgress(State.INDETERMINATE, 0);

    public TaskbarProgress {
        Objects.requireNonNull(state, "state");
        if (state == State.NONE || state == State.INDETERMINATE) {
            fraction = 0;
        } else if (Double.isNaN(fraction) || fraction < 0 || fraction > 1) {
            throw new IllegalArgumentException("fraction must be between 0 and 1");
        }
    }

    /** No progress shown. */
    public static TaskbarProgress none() {
        return CLEARED;
    }

    /** Unknown length. */
    public static TaskbarProgress indeterminate() {
        return BUSY;
    }

    /** The green bar, {@code fraction} done (0 to 1). */
    public static TaskbarProgress normal(double fraction) {
        return new TaskbarProgress(State.NORMAL, fraction);
    }

    /** The red bar, {@code fraction} done when it failed (0 to 1). */
    public static TaskbarProgress error(double fraction) {
        return new TaskbarProgress(State.ERROR, fraction);
    }

    /** The yellow bar, {@code fraction} done (0 to 1). */
    public static TaskbarProgress paused(double fraction) {
        return new TaskbarProgress(State.PAUSED, fraction);
    }
}
