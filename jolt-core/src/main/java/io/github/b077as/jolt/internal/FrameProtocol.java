package io.github.b077as.jolt.internal;

/**
 * The messages a frameless window's page sends to move, resize, minimize, maximize or close its window, and the script a window runs
 * to tell the page what state it is in. They travel over the ordinary message bridge, with a prefix that application code never
 * sees: a window drops them before its own listeners, and only acts on them when it was created {@code frameless} (and the page's
 * origin passed the security policy, as for every message).
 */
public final class FrameProtocol {

    private FrameProtocol() {}

    /** Starts every frame message. A control character, so a page's own messages are unlikely to start with it. */
    public static final String PREFIX = "\u0001jolt-frame:";

    /** Largest coordinate or size accepted from a page for the maximize button; anything above is nonsense and ignored. */
    static final int MAX_COORDINATE = 100_000;

    /** What a window must be able to do for its page. Called on the UI thread. */
    public interface Host {
        void minimize();

        void toggleMaximize();

        void close();

        /** Starts moving the window with the pointer, which is down. */
        void beginMove();

        /** Starts resizing from {@code edge}: 1 left, 2 right, 3 top, 4 top-left, 5 top-right, 6 bottom, 7 bottom-left, 8 bottom-right. */
        void beginResize(int edge);

        /** The page loaded (or asks again): tell it whether the window is maximized and focused, with {@link #stateScript}. */
        default void reportState() {}

        /**
         * The page's maximize button sits at this rectangle, in device pixels from the top-left of the window's client area. A
         * platform that can show the snap-layout flyout over it does; others ignore it.
         */
        default void setMaximizeButton(int x, int y, int width, int height) {}

        /** The page has no maximize button (any more). */
        default void clearMaximizeButton() {}

        /** The page asks for the window menu (restore, move, size, minimize, maximize, close) at the pointer. */
        default void showWindowMenu() {}
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
            case "state" -> host.reportState();
            case "menu" -> host.showWindowMenu();
            case "maxbtn:none" -> host.clearMaximizeButton();
            default -> {
                if (command.startsWith("resize:") && command.length() == "resize:".length() + 1) {
                    int edge = command.charAt(command.length() - 1) - '0';
                    if (edge >= 1 && edge <= 8) {
                        host.beginResize(edge);
                    }
                } else if (command.startsWith("maxbtn:")) {
                    int[] rect = parseRect(command.substring("maxbtn:".length()));
                    if (rect != null) {
                        host.setMaximizeButton(rect[0], rect[1], rect[2], rect[3]);
                    }
                }
            }
        }
        return true;
    }

    /** {@code x,y,width,height} as four integers, or {@code null} if it is anything else or out of range. */
    static int[] parseRect(String text) {
        String[] parts = text.split(",", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] values = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 7) {
                return null;
            }
            try {
                values[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (values[i] < -MAX_COORDINATE || values[i] > MAX_COORDINATE) {
                return null;
            }
        }
        return values[2] > 0 && values[3] > 0 ? values : null;
    }

    /** The script that tells the page (a frameless window's frame script) the window's state. Safe before the page has a frame script. */
    public static String stateScript(boolean maximized, boolean focused) {
        return "window.__joltFrameApply && window.__joltFrameApply(" + maximized + "," + focused + ");";
    }

    /** The script that tells the page which of its buttons the pointer is over or pressing: {@code "max"} or {@code null}. */
    public static String hoverScript(String button, boolean pressed) {
        return "window.__joltFrameHover && window.__joltFrameHover("
                + (button == null ? "null" : "'" + button + "'") + "," + pressed + ");";
    }
}
