package io.github.b077as.jolt.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Makes a bean method callable from the page with {@code jolt.call(name, params)}.
 *
 * <pre>{@code
 * @Service
 * @JoltRpc("notes")                       // optional: a prefix for every method of the bean
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
 * }</pre>
 *
 * <p>On a method, {@link #value()} is the name the page calls (the method's own name when empty). On a class, it is a prefix
 * joined to the method names with a dot.
 *
 * <p>A method takes at most one parameter for what the page sends (decoded from JSON into the parameter's type, generics
 * included, by the application's {@link io.github.b077as.jolt.JsonCodec}), and may also take an
 * {@link io.github.b077as.jolt.RpcCall} and the calling {@link io.github.b077as.jolt.WebWindow}, in any order. What it returns
 * is sent back as JSON; a {@link java.util.concurrent.CompletionStage} is waited for. An
 * {@link io.github.b077as.jolt.RpcException} reaches the page with its code, message and data; any other exception reaches it
 * only as {@code internal}, and is logged.
 *
 * <p>Methods run on their own virtual thread, never on the UI thread, so they can block. Names starting with {@code jolt.} are
 * reserved, and two methods with the same name are an error at startup.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface JoltRpc {

    /** The method's name as the page calls it, or, on a class, the prefix for its methods. */
    String value() default "";
}
