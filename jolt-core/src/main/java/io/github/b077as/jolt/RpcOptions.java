package io.github.b077as.jolt;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Settings for an {@link Rpc}. What the page is told about exceptions is not a setting: see {@link Rpc#onException}.
 *
 * @param codec              writes results and reads parameters; {@link JsonCodec#basic()} by default, or your JSON library
 * @param maxMessageChars    the longest request a page may send, in characters (parameters included); a longer one is answered with
 *                           {@link RpcException#TOO_LARGE}. For bulk data use a {@link BinaryChannel}
 * @param maxConcurrentCalls how many calls one window may have running at once (a stream counts until it ends); more are answered with
 *                           {@link RpcException#TOO_MANY_CALLS}
 * @param streamWindow       how many items of one stream may be on their way to the page at a time; the page asks for more as it
 *                           consumes them, so this is what keeps a fast producer from flooding a slow page. See {@link RpcStream}
 * @param executor           runs the handlers; {@code null} (the default) starts a virtual thread per call. Cancellation interrupts the
 *                           thread a handler runs on, so an executor must not mind that
 */
public record RpcOptions(JsonCodec codec, int maxMessageChars, int maxConcurrentCalls, int streamWindow, Executor executor) {

    /** 8 million characters. */
    public static final int DEFAULT_MAX_MESSAGE_CHARS = 8 * 1024 * 1024;
    public static final int DEFAULT_MAX_CONCURRENT_CALLS = 64;
    public static final int DEFAULT_STREAM_WINDOW = 64;
    public static final int MAX_STREAM_WINDOW = 65_536;

    public RpcOptions {
        Objects.requireNonNull(codec, "codec");
        if (maxMessageChars < 64) {
            throw new IllegalArgumentException("maxMessageChars must be at least 64: " + maxMessageChars);
        }
        if (maxConcurrentCalls < 1) {
            throw new IllegalArgumentException("maxConcurrentCalls must be at least 1: " + maxConcurrentCalls);
        }
        if (streamWindow < 1 || streamWindow > MAX_STREAM_WINDOW) {
            throw new IllegalArgumentException("streamWindow must be 1 to " + MAX_STREAM_WINDOW + ": " + streamWindow);
        }
    }

    public static RpcOptions defaults() {
        return new RpcOptions(JsonCodec.basic(), DEFAULT_MAX_MESSAGE_CHARS, DEFAULT_MAX_CONCURRENT_CALLS, DEFAULT_STREAM_WINDOW, null);
    }

    public RpcOptions withCodec(JsonCodec codec) {
        return new RpcOptions(codec, maxMessageChars, maxConcurrentCalls, streamWindow, executor);
    }

    public RpcOptions withMaxMessageChars(int chars) {
        return new RpcOptions(codec, chars, maxConcurrentCalls, streamWindow, executor);
    }

    public RpcOptions withMaxConcurrentCalls(int calls) {
        return new RpcOptions(codec, maxMessageChars, calls, streamWindow, executor);
    }

    public RpcOptions withStreamWindow(int items) {
        return new RpcOptions(codec, maxMessageChars, maxConcurrentCalls, items, executor);
    }

    public RpcOptions withExecutor(Executor executor) {
        return new RpcOptions(codec, maxMessageChars, maxConcurrentCalls, streamWindow, executor);
    }
}
