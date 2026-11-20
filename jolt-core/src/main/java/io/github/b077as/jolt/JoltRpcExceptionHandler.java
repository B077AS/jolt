package io.github.b077as.jolt;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Says what the page is told when a {@link JoltRpc} method throws a given exception: {@link Rpc#onException} for controller objects
 * ({@link Rpc#register(Object)}, or beans in a Spring Boot application). The method takes the exception (its type is the one handled,
 * subclasses included) and may also take the {@link RpcCall}, and returns the {@link RpcException} to send.
 *
 * <pre>{@code
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
 * <p>The rules are those of {@link Rpc#onException}: the most specific type wins, a handled exception is an expected failure (not
 * logged as a warning, not given to {@code onError}) unless the answer's code is {@code internal}, and anything unhandled reaches the
 * page only as {@code internal}. Two handlers for the same exception type, a method that is static, has no exception parameter or does
 * not return an {@code RpcException} are errors. The handlers apply to the whole {@link Rpc}, not only to the methods of the object
 * that declares them. Like {@link JoltRpc}, this is the reflection-based alternative to the explicit API.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JoltRpcExceptionHandler {
}
