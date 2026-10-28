package io.github.b077as.jolt;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * A named two-way pipe for binary data between Java and the page, for payloads {@link WebWindow#postMessage} is too slow
 * or too text-bound for: video frames, audio, images, files, sensor data.
 *
 * <p>Open it on the Java side with {@link WebWindow#openChannel(String)} <em>before the page loads</em>, then use the same
 * name in the page:
 * <pre>{@code
 * const channel = jolt.channel("frames");
 * channel.onMessage((data) => { ... });        // data is an ArrayBuffer
 * channel.send(new Uint8Array([1, 2, 3]));     // ArrayBuffer, typed array or string
 * }</pre>
 *
 * <p>The page connects by itself and reconnects if the connection drops, for example when it reloads. Only pages whose
 * origin the window's {@link SecurityPolicy} trusts can connect. Callbacks run on a thread of their own, one message at a
 * time and in order; use {@link Application#dispatch} to get to the UI thread. All methods may be called from any thread.
 */
public interface BinaryChannel extends AutoCloseable {

    /** The name the page uses in {@code jolt.channel(name)}. */
    String name();

    /** Whether a page is connected right now. */
    boolean isOpen();

    /**
     * Sends the bytes between {@code data}'s position and limit to the page. They are copied before this returns, so the caller
     * may reuse its buffer straight away; the buffer's position is left as it was.
     *
     * @return {@code true} if the message was accepted for sending; {@code false} if no page is connected, the channel is
     *         closed, or (with {@link ChannelOptions#latestOnly()} off) the page is too far behind to take more
     */
    boolean send(ByteBuffer data);

    default boolean send(byte[] data) {
        return send(ByteBuffer.wrap(data));
    }

    /** Called with each message the page sends. The buffer is the listener's to keep. */
    void onMessage(Consumer<ByteBuffer> listener);

    /** Called when a page connects, including each time it reconnects. */
    void onOpen(Runnable listener);

    /** Called when the connected page goes away or the channel is closed. */
    void onClose(Runnable listener);

    /** Disconnects the page and frees the name. The window and its other channels are not affected. */
    @Override
    void close();
}
