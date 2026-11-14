package io.github.b077as.jolt;

import java.lang.reflect.Type;

/**
 * One call from the page, as an {@link RpcHandler} sees it. A handler runs on a worker thread (a virtual thread by default), never on
 * the UI thread, so it may block on disk, network or a database.
 */
public interface RpcCall {

    /** The method the page called, for example {@code "notes.add"}. */
    String method();

    /** The window whose page made the call. */
    WebWindow window();

    /** The parameters exactly as the page sent them, as JSON text ({@code {}} when the page gave none). */
    String paramsJson();

    /**
     * The parameters read as {@code type} with the {@link Rpc}'s {@link JsonCodec}.
     *
     * @throws RpcException {@link RpcException#BAD_PARAMS} when they cannot be read as that type
     */
    <T> T params(Class<T> type);

    /** Like {@link #params(Class)} for a generic type such as {@code List<Item>} (get one from a field or a type token). */
    <T> T params(Type type);

    /**
     * Sends {@code data} to the page while the call is still running; the page receives it in the {@code onProgress} option of
     * {@code jolt.call}. Ignored once the call is over or cancelled. Any thread.
     *
     * <pre>{@code
     * for (int i = 0; i < files.size(); i++) { copy(files.get(i)); call.progress(Map.of("done", i + 1, "total", files.size())); }
     * }</pre>
     */
    void progress(Object data);

    /**
     * Whether the page gave up on this call: it aborted it (an {@code AbortSignal}), its timeout passed, the page reloaded or
     * navigated away, the window closed, or the window was detached. The handler's thread is also interrupted then, so blocking
     * calls end with {@link InterruptedException}; a handler in a long loop of its own should check this. Its result is thrown away.
     */
    boolean isCancelled();

    /** Throws {@link java.util.concurrent.CancellationException} if {@link #isCancelled()}. Handy inside loops. */
    default void checkCancelled() {
        if (isCancelled()) {
            throw new java.util.concurrent.CancellationException(method() + " was cancelled");
        }
    }
}
