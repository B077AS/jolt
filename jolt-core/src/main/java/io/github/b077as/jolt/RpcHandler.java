package io.github.b077as.jolt;

/**
 * Answers one method the page calls with {@code jolt.call(method, params)}. See {@link Rpc}.
 */
@FunctionalInterface
public interface RpcHandler {

    /**
     * @return the value for the page, written with the {@link Rpc}'s {@link JsonCodec} ({@code null} for none). A
     *         {@link java.util.concurrent.CompletionStage} is waited for, and its value sent.
     * @throws RpcException to give the page an error it can act on; any other exception reaches it as {@link RpcException#INTERNAL}
     */
    Object handle(RpcCall call) throws Exception;

    /** A handler that takes the parameters, read as a type of its choosing. */
    @FunctionalInterface
    interface WithParams<P> {
        Object handle(P params) throws Exception;
    }

    /** A handler that takes the parameters and the {@link RpcCall} (for progress, cancellation or the window). */
    @FunctionalInterface
    interface WithParamsAndCall<P> {
        Object handle(P params, RpcCall call) throws Exception;
    }
}
