package io.github.b077as.jolt;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Makes a method of an object callable from the page with {@code jolt.call(name, params)}, for {@link Rpc#register(Object)} (and, in a
 * Spring Boot application, for beans, found automatically by {@code jolt-spring-boot-starter}).
 *
 * <pre>{@code
 * @JoltRpc("notes")                       // optional: a prefix for every method of the class
 * class NotesService {
 *     @JoltRpc                            // called as "notes.list"
 *     List<Note> list() { ... }
 *
 *     @JoltRpc("add")                     // called as "notes.add" with { text: "..." }
 *     Note add(NewNote note) { ... }
 *
 *     @JoltRpc
 *     String export(ExportRequest request, RpcCall call) {   // RpcCall: progress, cancellation, the calling window
 *         call.progress(50);
 *         call.checkCancelled();
 *         return "done";
 *     }
 * }
 * rpc.register(new NotesService());
 * }</pre>
 *
 * <p>On a method, {@link #value()} is the name the page calls (the method's own name when empty). On a class, it is a prefix
 * joined to the method names with a dot.
 *
 * <p>A method takes at most one parameter for what the page sends (decoded from JSON into the parameter's type, generics included,
 * by the {@link JsonCodec} of the {@link Rpc}), and may also take an {@link RpcCall} and the calling {@link WebWindow}, in any order.
 * What it returns is sent back as JSON; a {@link java.util.concurrent.CompletionStage} is waited for. An {@link RpcException}
 * reaches the page with its code, message and data; any other exception only as {@code internal}, unless a
 * {@link JoltRpcExceptionHandler} says otherwise.
 *
 * <p>Methods run like any other {@link Rpc} handler: on their own thread, never on the UI thread, so they can block. Names starting
 * with {@code jolt.} are reserved, and two methods with the same name are an error.
 *
 * <p><b>This is not the preferred way.</b> {@link Rpc#on(String, RpcHandler)} is checked by the compiler and needs no reflection;
 * annotations are found at run time, so a mistake shows up when the app starts, and reflection needs extra configuration for native
 * images and for {@code jlink}-trimmed modules. It earns its place when there are very many methods to register, where one class
 * per area is easier to read than a long list of {@code on} calls.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface JoltRpc {

    /** The method's name as the page calls it, or, on a class, the prefix for its methods. */
    String value() default "";
}
