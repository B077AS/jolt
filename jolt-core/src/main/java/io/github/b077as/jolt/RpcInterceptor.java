package io.github.b077as.jolt;

/**
 * Code that runs around every {@link Rpc} call: authentication, logging, timing, metrics. Register it with {@link Rpc#around} (or
 * {@link Rpc#before}, for code that only checks something first).
 *
 * <pre>{@code
 * rpc.before(call -> {                                   // refuse before the handler runs
 *         if (!sessions.isSignedIn(call.window())) throw new RpcException("unauthorized", "Sign in first");
 *     })
 *    .around((call, next) -> {                           // time every call, whatever it answers
 *         long start = System.nanoTime();
 *         try {
 *             return next.handle(call);
 *         } finally {
 *             metrics.record(call.method(), System.nanoTime() - start);
 *         }
 *     });
 * }</pre>
 * Interceptors run on the call's thread, in the order they were registered (the first one is the outermost), only for methods that
 * exist, and wrap the handler as {@code next}. What an interceptor throws is handled like what a handler throws: an
 * {@link RpcException} reaches the page, anything else goes through {@link Rpc#onException}. A {@code CompletionStage} a handler
 * returns is already waited for when {@code next} returns, so an interceptor sees the value (or the failure), not the stage. A
 * streaming result (a {@link java.util.concurrent.Flow.Publisher} or an {@link RpcStream}) is passed through as it is.
 */
@FunctionalInterface
public interface RpcInterceptor {

    /**
     * @param call the call
     * @param next the rest of the chain: the following interceptors and then the handler. Call {@code next.handle(call)} to go on, or
     *             do not to answer without running the handler
     * @return what the page gets: usually what {@code next} returned
     */
    Object intercept(RpcCall call, RpcHandler next) throws Exception;

    /** An interceptor that only looks at the call first; throw (an {@link RpcException}, to tell the page) to stop it. */
    @FunctionalInterface
    interface Before {
        void before(RpcCall call) throws Exception;
    }
}
