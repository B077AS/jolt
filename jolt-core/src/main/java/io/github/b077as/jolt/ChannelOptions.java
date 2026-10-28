package io.github.b077as.jolt;

/**
 * Settings for {@link WebWindow#openChannel(String, ChannelOptions)}.
 *
 * @param latestOnly      what a slow page costs. When {@code true} a message that has not been sent yet is replaced by the
 *                        next one, so the page always receives the newest data and never a backlog: right for video frames,
 *                        sensor readings or a live waveform. When {@code false} every message is kept in order, up to
 *                        {@code maxQueuedBytes}, and {@link BinaryChannel#send} returns {@code false} for one that does not fit
 * @param maxQueuedBytes  how many bytes may wait to be sent to the page when {@code latestOnly} is {@code false}. A single
 *                        message larger than this is still accepted when nothing else is waiting
 * @param maxMessageBytes the largest message the page may send to Java; a bigger one closes the connection
 */
public record ChannelOptions(boolean latestOnly, int maxQueuedBytes, int maxMessageBytes) {

    /** 16 MB. */
    public static final int DEFAULT_MAX_QUEUED_BYTES = 16 * 1024 * 1024;
    /** 64 MB. */
    public static final int DEFAULT_MAX_MESSAGE_BYTES = 64 * 1024 * 1024;

    public ChannelOptions {
        if (maxQueuedBytes <= 0 || maxMessageBytes <= 0) {
            throw new IllegalArgumentException("limits must be positive: " + maxQueuedBytes + ", " + maxMessageBytes);
        }
    }

    /** Every message is delivered in order, with up to {@value #DEFAULT_MAX_QUEUED_BYTES} bytes waiting. */
    public static ChannelOptions defaults() {
        return new ChannelOptions(false, DEFAULT_MAX_QUEUED_BYTES, DEFAULT_MAX_MESSAGE_BYTES);
    }

    /** A slow page gets only the newest message, see {@link #latestOnly()}. */
    public static ChannelOptions newestOnly() {
        return new ChannelOptions(true, DEFAULT_MAX_QUEUED_BYTES, DEFAULT_MAX_MESSAGE_BYTES);
    }

    public ChannelOptions withMaxQueuedBytes(int bytes) {
        return new ChannelOptions(latestOnly, bytes, maxMessageBytes);
    }

    public ChannelOptions withMaxMessageBytes(int bytes) {
        return new ChannelOptions(latestOnly, maxQueuedBytes, bytes);
    }
}
