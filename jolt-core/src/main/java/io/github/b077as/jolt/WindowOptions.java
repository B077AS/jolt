package io.github.b077as.jolt;

import java.util.Objects;

/**
 * Settings for a new window.
 *
 * @param title          initial window title
 * @param width          initial client-area width in logical pixels
 * @param height         initial client-area height in logical pixels
 * @param devTools       whether the browser developer tools and context menu are available
 * @param visible        whether the window is shown as soon as it is created
 * @param icon           the window icon, or {@code null} for the platform default. Either PNG data (a square image of at
 *                       least 256x256 pixels with a transparent background) or the bytes of a Windows {@code .ico} file
 *                       holding the icon pre-drawn at several sizes. Prefer the {@code .ico}: Windows then uses the image
 *                       that matches the title bar or taskbar size instead of shrinking one big image, which looks
 *                       grainy. On Linux the largest PNG image inside the {@code .ico} is used
 * @param titleBarScheme whether the operating system draws the title bar light or dark, or {@code null} to follow
 *                       the system. Setting it here means the window never flashes the wrong colour at startup
 * @param backgroundRgb  colour ({@code 0xRRGGBB}) shown in the web view before the page has painted, or {@code null}
 *                       for the platform default (white). Match your page's background to avoid a flash at startup
 * @param security       what pages in this window may do; {@code null} means {@link SecurityPolicy#standard()}
 */
public record WindowOptions(String title, int width, int height, boolean devTools, boolean visible,
                            byte[] icon, ColorScheme titleBarScheme, Integer backgroundRgb,
                            SecurityPolicy security) {

    public WindowOptions {
        Objects.requireNonNull(title, "title");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("size must be positive: " + width + "x" + height);
        }
        icon = icon == null ? null : icon.clone();
        security = security == null ? SecurityPolicy.standard() : security;
        if (backgroundRgb != null && (backgroundRgb < 0 || backgroundRgb > 0xFFFFFF)) {
            throw new IllegalArgumentException("backgroundRgb must be 0xRRGGBB: " + backgroundRgb);
        }
    }

    public static WindowOptions defaults() {
        return new WindowOptions("Jolt", 1024, 720, false, true, null, null, null, null);
    }

    public WindowOptions withTitle(String title) {
        return new WindowOptions(title, width, height, devTools, visible, icon, titleBarScheme, backgroundRgb, security);
    }

    public WindowOptions withSize(int width, int height) {
        return new WindowOptions(title, width, height, devTools, visible, icon, titleBarScheme, backgroundRgb, security);
    }

    public WindowOptions withDevTools(boolean devTools) {
        return new WindowOptions(title, width, height, devTools, visible, icon, titleBarScheme, backgroundRgb, security);
    }

    public WindowOptions withVisible(boolean visible) {
        return new WindowOptions(title, width, height, devTools, visible, icon, titleBarScheme, backgroundRgb, security);
    }

    /** @param png the icon as PNG or {@code .ico} data, see {@link #icon()} */
    public WindowOptions withIcon(byte[] png) {
        return new WindowOptions(title, width, height, devTools, visible, png, titleBarScheme, backgroundRgb, security);
    }

    public WindowOptions withTitleBarScheme(ColorScheme scheme) {
        return new WindowOptions(title, width, height, devTools, visible, icon, scheme, backgroundRgb, security);
    }

    /** @param rgb {@code 0xRRGGBB} */
    public WindowOptions withBackgroundColor(int rgb) {
        return new WindowOptions(title, width, height, devTools, visible, icon, titleBarScheme, rgb, security);
    }

    /** What pages in this window may do; can also be changed later with {@link WebWindow#setSecurity}. */
    public WindowOptions withSecurity(SecurityPolicy policy) {
        return new WindowOptions(title, width, height, devTools, visible, icon, titleBarScheme, backgroundRgb, policy);
    }
}
