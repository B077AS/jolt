package io.github.b077as.jolt;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The operating system's own file dialogs, owned by a window (see {@link WebWindow#dialogs()}). They give your Java code the
 * real path the user chose, which a web page cannot: open a file you then read or watch, or pick where a download is saved and
 * write it yourself.
 *
 * <p>A dialog blocks the window it belongs to until it is closed, as the platform does for any modal dialog. Every method may be
 * called from any thread and returns at once; the future completes when the user has chosen or cancelled. A user who cancels is
 * not an error: the future completes with an empty result. It fails with {@link JoltException} when the dialog cannot be shown
 * and with {@link WindowClosedException} when the window is closed first. Opening a second dialog while one is showing waits for
 * the first.
 */
public interface Dialogs {

    /** Lets the user pick one existing file. */
    CompletableFuture<Optional<Path>> openFile(FileDialogOptions options);

    /** Lets the user pick any number of existing files. Empty when the user cancels. */
    CompletableFuture<List<Path>> openFiles(FileDialogOptions options);

    /**
     * Lets the user pick where to save a file. The file is not created, and the system asks about overwriting an existing one,
     * so write it only if the result is present.
     */
    CompletableFuture<Optional<Path>> saveFile(FileDialogOptions options);

    /** Lets the user pick an existing folder. */
    CompletableFuture<Optional<Path>> chooseFolder(FileDialogOptions options);
}
