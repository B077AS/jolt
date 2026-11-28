package io.github.b077as.jolt;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The answer to a close request, see {@link WebWindow#onCloseRequest}: let the window close, keep it, or decide later.
 */
public final class CloseDecision {

    private final CompletionStage<Boolean> allowed;

    private CloseDecision(CompletionStage<Boolean> allowed) {
        this.allowed = allowed;
    }

    /** Close the window. */
    public static CloseDecision allow() {
        return new CloseDecision(CompletableFuture.completedFuture(true));
    }

    /** Keep the window open. */
    public static CloseDecision cancel() {
        return new CloseDecision(CompletableFuture.completedFuture(false));
    }

    /**
     * Decide later, for example after asking the page or the user. The window stays open until {@code allowed} completes
     * with {@code true}; {@code false}, {@code null} or a failure keeps it open. Further close requests are ignored while
     * one is waiting.
     */
    public static CloseDecision when(CompletionStage<Boolean> allowed) {
        return new CloseDecision(Objects.requireNonNull(allowed, "allowed"));
    }

    /** Whether the window may close. */
    public CompletionStage<Boolean> allowed() {
        return allowed;
    }
}
