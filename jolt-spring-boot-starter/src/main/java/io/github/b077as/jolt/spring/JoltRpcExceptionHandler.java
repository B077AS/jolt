package io.github.b077as.jolt.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Says what the page is told when a {@link JoltRpc} method throws a given exception: Spring's {@code @ExceptionHandler} for
 * {@code jolt.call}. The method takes the exception (its type is the one handled, subclasses included) and may also take the
 * {@link io.github.b077as.jolt.RpcCall}, and returns the {@link io.github.b077as.jolt.RpcException} to send.
 *
 * <pre>{@code
 * @Component
 * class RpcErrors {
 *     @JoltRpcExceptionHandler
 *     RpcException sql(SQLException e) {                       // the page shows the database's complaint
 *         return new RpcException("sql", e.getMessage());
 *     }
 *
 *     @JoltRpcExceptionHandler
 *     RpcException invalid(ValidationException e, RpcCall call) {
 *         return new RpcException("invalid", e.getMessage(), e.fields());
 *     }
 * }
 * }</pre>
 *
 * <p>This is {@link io.github.b077as.jolt.Rpc#onException} for beans, with the same rules: the most specific type wins, a handled
 * exception is an expected failure (not logged as a warning, not given to {@code onError}) unless the answer's code is
 * {@code internal}, and anything unhandled reaches the page only as {@code internal}. Two handlers for the same exception type, a
 * method that is static, has no exception parameter or does not return an {@code RpcException} are errors at startup. A handler
 * for {@code Throwable} replaces the one {@code jolt.rpc.expose-error-messages=true} sets up.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JoltRpcExceptionHandler {
}
