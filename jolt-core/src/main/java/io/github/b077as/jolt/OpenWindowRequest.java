package io.github.b077as.jolt;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A page's request to open another window, made with {@code jolt.openWindow(path, options)}. Nothing has been opened yet: the
 * {@link WindowOpener} you gave to {@link WebWindow#enableOpenWindow} decides whether to, and how.
 *
 * <p>Every field comes from the page, so treat it as untrusted input: {@code path} is checked to be an address inside the app
 * (it starts with one {@code /}), and the title and size are only suggestions.
 */
public final class OpenWindowRequest {

    private final WebWindow opener;
    private final String path;
    private final String key;
    private final String title;
    private final int width;
    private final int height;
    private final Consumer<WebWindow> prepare;

    /**
     * Made by Jolt when a page asks; public so that code which handles requests can be tested.
     *
     * @param prepare called on every window made with {@link #createWindow}, before it can load anything
     */
    public OpenWindowRequest(WebWindow opener, String path, String key, String title, int width, int height,
                             Consumer<WebWindow> prepare) {
        this.opener = Objects.requireNonNull(opener, "opener");
        this.path = Objects.requireNonNull(path, "path");
        this.key = key;
        this.title = title;
        this.width = width;
        this.height = height;
        this.prepare = Objects.requireNonNull(prepare, "prepare");
    }

    /** The window whose page made the request. */
    public WebWindow opener() {
        return opener;
    }

    /**
     * The app address the new window should show, such as {@code /message/42?folder=inbox}; always starts with a single
     * {@code /}. Hand it to the new window with {@link ResourceOptions#withStartPath}.
     */
    public String path() {
        return path;
    }

    /**
     * Names the window so that asking again for the same key brings the existing window to the front instead of opening a second
     * one (Jolt does that for you, the opener is not called again), or {@code null} for a window that is always new.
     */
    public String key() {
        return key;
    }

    /** The title the page suggests, or {@code null}. */
    public String title() {
        return title;
    }

    /** The client-area width the page suggests, in logical pixels, or {@code 0} for none; limited to 200..4000 if given. */
    public int width() {
        return width;
    }

    /** The client-area height the page suggests, in logical pixels, or {@code 0} for none; limited to 200..4000 if given. */
    public int height() {
        return height;
    }

    /**
     * Options for the new window made from the page's suggestions: its title and size when it gave them, the defaults otherwise,
     * and no owner. Change what you want and pass it to {@link #createWindow(Application, WindowOptions)}; for example
     * {@code request.options().withOwner(request.opener())} keeps the new window in front of the one that asked.
     */
    public WindowOptions options() {
        WindowOptions options = WindowOptions.defaults();
        if (title != null && !title.isBlank()) {
            options = options.withTitle(title);
        }
        if (width > 0 && height > 0) {
            options = options.withSize(width, height);
        }
        return options;
    }

    /** Creates the window with {@link #options()}; see {@link #createWindow(Application, WindowOptions)}. */
    public WebWindow createWindow(Application app) {
        return createWindow(app, options());
    }

    /**
     * Creates the new window like {@link Application#createWindow} and lets its pages use {@code jolt.openWindow} and
     * {@code jolt.broadcast} straight away. Use this rather than {@code app.createWindow}: a window enabled only after it has
     * started loading can run its page before those functions exist. Call it on the UI thread, which is where the opener runs.
     */
    public WebWindow createWindow(Application app, WindowOptions options) {
        WebWindow window = app.createWindow(options);
        prepare.accept(window);
        return window;
    }

    @Override
    public String toString() {
        return "OpenWindowRequest[path=" + path + ", key=" + key + ", title=" + title + ", width=" + width + ", height=" + height + "]";
    }
}
