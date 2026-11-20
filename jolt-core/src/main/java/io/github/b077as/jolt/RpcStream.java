package io.github.b077as.jolt;

/**
 * Where a handler puts the items of a stream for the page, from {@link RpcCall#stream()}. The page reads them with
 * {@code jolt.stream}, as an async iterator:
 *
 * <pre>{@code
 * rpc.on("tail", call -> {
 *     RpcStream<String> out = call.stream();
 *     try (BufferedReader log = Files.newBufferedReader(path)) {
 *         for (String line; (line = log.readLine()) != null; ) {
 *             out.send(line);                     // waits while the page is behind; throws if it gave up
 *         }
 *     }
 *     return null;                                // the stream ends when the handler returns
 * });
 * }</pre>
 * <pre>{@code
 * for await (const line of jolt.stream("tail", { file: "app.log" })) append(line);   // break stops it
 * }</pre>
 * The page asks for more items as it consumes them: at most {@link RpcOptions#streamWindow()} items are on their way at a time, so a
 * fast producer cannot flood a slow page. {@link #send} waits for room.
 *
 * <p>The stream ends when the handler returns (an exception it throws reaches the page as an error, as for any call), or, for a
 * handler that returns the stream itself so that another thread can go on filling it, when {@link #complete()} or {@link #fail} is
 * called. A handler that returns a {@link java.util.concurrent.Flow.Publisher} does not need this class: its items are forwarded, with
 * the page's demand as back pressure.
 *
 * @param <T> the type of the items; the {@link JsonCodec} of the {@link Rpc} writes them
 */
public interface RpcStream<T> {

    /**
     * Sends one item, waiting while the page has not asked for more.
     *
     * @throws java.util.concurrent.CancellationException if the page gave up on the call (also if this thread was interrupted)
     * @throws IllegalStateException                       if the stream already ended
     * @throws IllegalArgumentException                    if the codec cannot write the item
     */
    void send(T item);

    /** Ends the stream normally. Does nothing if it already ended. Any thread. */
    void complete();

    /**
     * Ends the stream with an error, which the page gets as it would from a handler that threw {@code error} (so
     * {@link Rpc#onException} applies). Does nothing if it already ended. Any thread.
     */
    void fail(Throwable error);
}
