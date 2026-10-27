package io.github.b077as.jolt;

import java.util.Objects;

/**
 * Settings for a new window.
 *
 * @param title          initial window title
 * @param width          initial client-area width in logical pixels
 * @param height         initial client-area height in logical pixels
 * @param devTools       whether the browser developer tools and context menu are available
 * @param visible        whether the window is shown as soon as it is created. Start hidden and call
 *                       {@link WebWindow#show()} once the page has loaded to avoid showing an empty window while the web
 *                       view starts
 * @param icon           the window icon, or {@code null} for the platform default. Either PNG data (a square image of at
 *                       least 256x256 pixels with a transparent background) or the bytes of a Windows {@code .ico} file
 *                       holding the icon pre-drawn at several sizes. Prefer the {@code .ico}: Windows then uses the image
 *                       that matches the title bar or taskbar size instead of shrinking one big image, which looks
 *                       grainy. On Linux the largest PNG image inside the {@code .ico} is used
 * @param titleBarScheme whether the operating system draws the title bar light or dark, or {@code null} to follow
 *                       the system. Setting it here means the window never flashes the wrong colour at startup
 * @param backgroundRgb  colour ({@code 0xRRGGBB}) shown in the window and in the web view before the page has painted, or
 *                       {@code null} for the platform default (white). Match your page's background to avoid a flash at
 *                       startup
 * @param security       what pages in this window may do; {@code null} means {@link SecurityPolicy#standard()}
 * @param maximized      whether the window starts maximized. A window created hidden is maximized when it is first shown.
 *                       Linux needs a window manager (or compositor) that honours it
 * @param centered       whether the window is placed in the middle of the screen's usable area when it is created, instead
 *                       of wherever the operating system puts a new window (Windows cascades them). The default is
 *                       {@code true}. Ignored while {@code maximized}. On Wayland an application cannot choose its
 *                       position, so the compositor decides
 */
public record WindowOptions(String title, int width, int height, boolean devTools, boolean visible,
                            byte[] icon, ColorScheme titleBarScheme, Integer backgroundRgb,
                            SecurityPolicy security, boolean maximized, boolean centered) {

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
        return new WindowOptions("Jolt", 1024, 720, false, true, null, null, null, null, false, true);
    }

    public WindowOptions withTitle(String title) {
        return new WindowOptions(title, width, height, devTools, visible, icon, titleBarScheme, backgroundRgb, security, maximized, centered);
    }

    public WindowOptions withSize(int width, int height) {
        return new WindowOptions(title, width, height, devTools, visible, icon, titleBarScheme, backgroundRgb, security, maximized, centered);
    }

    public WindowOptions withDevTools(boolean devTools) {
        return new WindowOptions(title, width, height, devTools, visible, icon, titleBarScheme, backgroundRgb, security, maximized, centered);
    }

    public WindowOptions withVisible(boolean visible) {
        return new WindowOptions(title, width, height, devTools, visible, icon, titleBarScheme, backgroundRgb, security, maximized, centered);
    }

    /** @param png the icon as PNG or {@code .ico} data, see {@link #icon()} */
    public WindowOptions withIcon(byte[] png) {
        return new WindowOptions(title, width, height, devTools, visible, png, titleBarScheme, backgroundRgb, security, maximized, centered);
    }

    public WindowOptions withTitleBarScheme(ColorScheme scheme) {
        return new WindowOptions(title, width, height, devTools, visible, icon, scheme, backgroundRgb, security, maximized, centered);
    }

    /** @param rgb {@code 0xRRGGBB} */
    public WindowOptions withBackgroundColor(int rgb) {
        return new WindowOptions(title, width, height, devTools, visible, icon, titleBarScheme, rgb, security, maximized, centered);
    }

    /** What pages in this window may do; can also be changed later with {@link WebWindow#setSecurity}. */
    public WindowOptions withSecurity(SecurityPolicy policy) {
        return new WindowOptions(title, width, height, devTools, visible, icon, titleBarScheme, backgroundRgb, policy, maximized, centered);
    }

    /** Whether the window starts maximized, see {@link #maximized()}. */
    public WindowOptions withMaximized(boolean maximized) {
        return new WindowOptions(title, width, height, devTools, visible, icon, titleBarScheme, backgroundRgb, security, maximized, centered);
    }

    /** Whether the window is centered on the screen when created, see {@link #centered()}. */
    public WindowOptions withCentered(boolean centered) {
        return new WindowOptions(title, width, height, devTools, visible, icon, titleBarScheme, backgroundRgb, security, maximized, centered);
    }
}
