package io.github.b077as.jolt.internal;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** A window that can start an operating-system drag of files out of itself, for {@link PageFileDrag}. */
public interface NativeFileDragHost {

    /**
     * Starts dragging {@code files} out of the window, with the mouse button that is down right now, and calls {@code finished}
     * when it ends: true if a target accepted a copy. Both on the UI thread. Returns {@code false} (and never calls
     * {@code finished}) if no drag could be started.
     */
    boolean beginFileDrag(List<Path> files, Consumer<Boolean> finished);
}
