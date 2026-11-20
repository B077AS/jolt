package io.github.b077as.jolt;

/**
 * Decides what the page is told when an {@link Rpc} handler throws an exception of a given type; register it with
 * {@link Rpc#onException}.
 *
 * <pre>{@code
 * rpc.onException(SQLException.class, (call, e) -> new RpcException("sql", e.getMessage()))
 *    .onException(ValidationException.class, (call, e) -> new RpcException("invalid", e.getMessage(), e.fields()));
 * }</pre>
 * An exception that has a handler is an <em>expected</em> failure: it is not logged as a warning and is not given to
 * {@link Rpc#onError}, unless the handler answers with {@link RpcException#INTERNAL}.
 *
 * @param <E> the exception type
 */
@FunctionalInterface
public interface RpcExceptionHandler<E extends Throwable> {

    /**
     * @param call  the call that failed
     * @param error the exception, unwrapped from {@code ExecutionException} / {@code CompletionException}
     * @return what the page is told: the code, message and data of the app's choosing. Never {@code null}
     */
    RpcException handle(RpcCall call, E error);
}
