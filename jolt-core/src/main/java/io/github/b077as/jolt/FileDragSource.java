package io.github.b077as.jolt;

import java.nio.file.Path;
import java.util.List;

/**
 * Decides which files the user can drag out of a window into the file manager, the desktop or another application, see
 * {@link WebWindow#setFileDragSource}.
 *
 * <p>The page never names a file. It marks an element {@code <li draggable="true" data-jolt-drag-file="attachment:42">}, and when
 * the user drags it, only that token (a string the application made up) reaches {@link #filesFor}. The application answers with
 * the files, or refuses by returning {@code null} or an empty list.
 */
@FunctionalInterface
public interface FileDragSource {

    /**
     * The files to offer for {@code token}: absolute local paths of files or folders that exist (at most 4096; anything else
     * cancels the drag). The target can only <em>copy</em> them, so the originals are never moved or deleted by the drop. Called
     * on the UI thread the moment the drag starts, so it should be quick; create a file that does not exist yet before the user
     * can start dragging it, or write it here when it is small.
     */
    List<Path> filesFor(String token);

    /**
     * The drag ended. {@code dropped} is true when a target accepted the files (a copy was made), false when the user cancelled or
     * let go over something that does not take files. Called on the UI thread; use it to delete a temporary file.
     */
    default void finished(String token, boolean dropped) {}
}
