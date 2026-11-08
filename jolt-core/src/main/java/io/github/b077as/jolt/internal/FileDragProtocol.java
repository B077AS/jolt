package io.github.b077as.jolt.internal;

import java.util.Map;

/**
 * The message a page sends when the user starts dragging an element marked {@code data-jolt-drag-file}: {@link #PREFIX} and a
 * JSON object {@code {"token":string}}. It never reaches the application's message listeners.
 */
public final class FileDragProtocol {

    private FileDragProtocol() {}

    /** Starts every file-drag message. */
    public static final String PREFIX = "\u0001jolt-dragout:";

    /** Longest token accepted. */
    static final int MAX_TOKEN = 1024;

    /** Whether {@code message} is a file-drag message. */
    public static boolean isDragMessage(String message) {
        return message.startsWith(PREFIX);
    }

    /** The token in {@code message}, or {@code null} if it is malformed, empty or too long. */
    public static String token(String message) {
        if (!isDragMessage(message)) {
            return null;
        }
        try {
            String raw = MiniJson.members(message.substring(PREFIX.length())).get("token");
            if (raw == null || !raw.startsWith("\"")) {
                return null;
            }
            String token = MiniJson.decodeString(raw);
            return token.isEmpty() || token.length() > MAX_TOKEN ? null : token;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
