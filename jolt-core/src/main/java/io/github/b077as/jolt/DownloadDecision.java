package io.github.b077as.jolt;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * What to do with a {@link DownloadRequest}: refuse it, or save it to a file of the application's choosing, now or once
 * the user has picked one.
 *
 * <pre>{@code
 * window.onDownload(request -> DownloadDecision.saveTo(downloads.resolve(request.fileName())));
 *
 * window.onDownload(request -> DownloadDecision.saveWhen(
 *         window.dialogs().saveFile(FileDialogOptions.defaults().withSuggestedName(request.fileName()))));
 * }</pre>
 */
public final class DownloadDecision {

    private final CompletionStage<Optional<Path>> destination;

    private DownloadDecision(CompletionStage<Optional<Path>> destination) {
        this.destination = destination;
    }

    /** Refuse the download. */
    public static DownloadDecision deny() {
        return new DownloadDecision(CompletableFuture.completedFuture(Optional.empty()));
    }

    /**
     * Save to {@code file}, replacing it if it exists. The folder must exist.
     *
     * @throws IllegalArgumentException if {@code file} is not absolute
     */
    public static DownloadDecision saveTo(Path file) {
        Objects.requireNonNull(file, "file");
        if (!file.isAbsolute()) {
            throw new IllegalArgumentException("the download destination must be an absolute path: " + file);
        }
        return new DownloadDecision(CompletableFuture.completedFuture(Optional.of(file)));
    }

    /**
     * Decide later: the download waits until {@code destination} completes. An empty result, a failure or a path that is
     * not absolute refuses it. The shape of {@link Dialogs#saveFile}'s result.
     */
    public static DownloadDecision saveWhen(CompletionStage<Optional<Path>> destination) {
        return new DownloadDecision(Objects.requireNonNull(destination, "destination"));
    }

    /** Where to save; empty means refused. */
    public CompletionStage<Optional<Path>> destination() {
        return destination;
    }
}
