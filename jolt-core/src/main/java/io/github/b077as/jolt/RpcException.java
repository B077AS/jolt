package io.github.b077as.jolt;

import java.util.regex.Pattern;

/**
 * An error an {@link Rpc} handler means the page to see. Throw it from a handler and the page's {@code jolt.call} rejects with a
 * {@code JoltRpcError} that has the same {@code code}, {@code message} and {@code data}:
 *
 * <pre>{@code
 * rpc.on("notes.get", NoteId.class, id -> store.find(id.value())
 *         .orElseThrow(() -> new RpcException("not_found", "There is no note " + id.value())));
 * }</pre>
 * <pre>{@code
 * try { await jolt.call("notes.get", { value: 7 }); }
 * catch (e) { if (e.code === "not_found") showEmpty(); else throw e; }
 * }</pre>
 * Any other exception a handler throws reaches the page only as {@link #INTERNAL} with a generic message (its own message could
 * reveal paths, SQL or other details; see {@link RpcOptions#exposeErrorMessages()}).
 *
 * <p>Jolt itself uses these codes: {@link #UNKNOWN_METHOD}, {@link #BAD_PARAMS}, {@link #INTERNAL}, {@link #TOO_MANY_CALLS},
 * {@link #TOO_LARGE}, {@link #UNAVAILABLE}; and in the page only, {@code "timeout"}, {@code "not_enabled"} and
 * {@code "bad_request"}.
 */
public class RpcException extends RuntimeException {

    /** No handler for the method. */
    public static final String UNKNOWN_METHOD = "unknown_method";
    /** The parameters could not be read as the type the handler wants, or the handler says they are wrong. */
    public static final String BAD_PARAMS = "bad_params";
    /** The handler failed with an exception that was not an {@code RpcException}. */
    public static final String INTERNAL = "internal";
    /** The window already has {@link RpcOptions#maxConcurrentCalls()} calls running. */
    public static final String TOO_MANY_CALLS = "too_many_calls";
    /** The request was longer than {@link RpcOptions#maxMessageChars()}. */
    public static final String TOO_LARGE = "too_large";
    /** The window is no longer attached to an {@link Rpc} (see {@link Rpc#detach}), or the call was stopped because it was. */
    public static final String UNAVAILABLE = "unavailable";

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

    private final String code;
    private final transient Object data;

    /**
     * @param code    a short identifier the page can test, such as {@code "not_found"}: letters, digits, {@code _ . -}, at most 64
     * @param message text for the page (it is shown to the page as is)
     */
    public RpcException(String code, String message) {
        this(code, message, null, null);
    }

    /** With {@code data}, any value the {@link JsonCodec} can write, given to the page as {@code error.data}. */
    public RpcException(String code, String message, Object data) {
        this(code, message, data, null);
    }

    public RpcException(String code, String message, Object data, Throwable cause) {
        super(message, cause);
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("an RpcException code is 1 to 64 letters, digits, '_', '.' or '-': " + code);
        }
        this.code = code;
        this.data = data;
    }

    /** {@link #BAD_PARAMS} with {@code message}, for a handler that checks its parameters itself. */
    public static RpcException badParams(String message) {
        return new RpcException(BAD_PARAMS, message);
    }

    public String code() {
        return code;
    }

    /** The extra value for the page, or {@code null}. */
    public Object data() {
        return data;
    }
}
