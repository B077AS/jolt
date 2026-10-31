package io.github.b077as.jolt.internal;

/**
 * The messages a frameless window's page sends to move, resize, minimize, maximize or close its window. They travel over the ordinary
 * message bridge, with a prefix that application code never sees: a window drops them before its own listeners, and only acts on
 * them when it was created {@code frameless} (and the page's origin passed the security policy, as for every message).
 */
public final class FrameProtocol {

    private FrameProtocol() {}

    /** Starts every frame message. A control character, so a page's own messages are unlikely to start with it. */
    public static final String PREFIX = "\u0001jolt-frame:";

    /** What a window must be able to do for its page. Called on the UI thread. */
    public interface Host {
        void minimize();

        void toggleMaximize();

        void close();

        /** Starts moving the window with the pointer, which is down. */
        void beginMove();

        /** Starts resizing from {@code edge}: 1 left, 2 right, 3 top, 4 top-left, 5 top-right, 6 bottom, 7 bottom-left, 8 bottom-right. */
        void beginResize(int edge);
    }

    /** Whether {@code message} is a frame message (and so must not reach the application's listeners). */
    public static boolean isFrameMessage(String message) {
        return message.startsWith(PREFIX);
    }

    /**
     * Carries out a frame message. Unknown or malformed ones are ignored.
     *
     * @return whether {@code message} was a frame message
     */
    public static boolean handle(String message, Host host) {
        if (!isFrameMessage(message)) {
            return false;
        }
        String command = message.substring(PREFIX.length());
        switch (command) {
            case "minimize" -> host.minimize();
            case "maximize" -> host.toggleMaximize();
            case "close" -> host.close();
            case "move" -> host.beginMove();
            default -> {
                if (command.startsWith("resize:") && command.length() == "resize:".length() + 1) {
                    int edge = command.charAt(command.length() - 1) - '0';
                    if (edge >= 1 && edge <= 8) {
                        host.beginResize(edge);
                    }
                }
            }
        }
        return true;
    }
}
