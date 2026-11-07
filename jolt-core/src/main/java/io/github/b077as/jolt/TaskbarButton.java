package io.github.b077as.jolt;

import java.util.Objects;

/**
 * One button of the strip under a window's preview when the pointer rests on its taskbar icon (Windows only), see
 * {@link WebWindow#setTaskbarButtons}. Immutable: build the list again to change a button.
 *
 * @param tooltip         shown when the pointer is over the button, at most {@link #MAX_TOOLTIP} characters
 * @param icon            PNG data (or the bytes of an {@code .ico} file) of the picture on the button; it is drawn about 16 pixels
 *                        square, so use a simple, high-contrast shape on a transparent background
 * @param onClick         called on the UI thread when the user presses the button
 * @param enabled         a disabled button is shown greyed and cannot be pressed
 * @param dismissOnClick  close the preview after the press
 */
public record TaskbarButton(String tooltip, byte[] icon, Runnable onClick, boolean enabled, boolean dismissOnClick) {

    /** Windows shows at most this many buttons. */
    public static final int MAX_BUTTONS = 7;

    /** Longest tooltip Windows accepts. */
    public static final int MAX_TOOLTIP = 259;

    public TaskbarButton {
        Objects.requireNonNull(tooltip, "tooltip");
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(onClick, "onClick");
        if (tooltip.length() > MAX_TOOLTIP) {
            throw new IllegalArgumentException("a tooltip is limited to " + MAX_TOOLTIP + " characters");
        }
        icon = icon.clone();
    }

    /** An enabled button that leaves the preview open after a press. */
    public static TaskbarButton of(String tooltip, byte[] icon, Runnable onClick) {
        return new TaskbarButton(tooltip, icon, onClick, true, false);
    }

    public TaskbarButton withEnabled(boolean enabled) {
        return new TaskbarButton(tooltip, icon, onClick, enabled, dismissOnClick);
    }

    public TaskbarButton withDismissOnClick(boolean dismissOnClick) {
        return new TaskbarButton(tooltip, icon, onClick, enabled, dismissOnClick);
    }

    @Override
    public byte[] icon() {
        return icon.clone();
    }
}
