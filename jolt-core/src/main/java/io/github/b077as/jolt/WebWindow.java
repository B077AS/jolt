package io.github.b077as.jolt;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A native window hosting a web view. All methods are safe to call from any thread; calls made before the web
 * view has finished initializing are queued and applied in order once {@link #ready()} completes.
 *
 * <p>Listeners and futures complete on the UI thread.
 */
public interface WebWindow {

    /** Completes when the underlying web view is initialized, or fails if the platform web view is unavailable. */
    CompletableFuture<Void> ready();

    /**
     * Navigates to {@code url}. With the default {@link SecurityPolicy} this only works for trusted origins; others are blocked
     * and reported to {@link #onBlocked}.
     */
    void loadUrl(String url);

    /**
     * Shows a web app made of several files. The page is served from {@code resources} and navigated to its {@code index.html},
     * so it can use relative links, {@code <link>}, ES modules and {@code fetch}, and the files stay separate. Browser storage
     * (localStorage, IndexedDB) lives under the page's origin and survives restarts.
     *
     * <p>The files are served inside the web view itself (WebView2 and WebKitGTK both let an application answer requests for an
     * address of its own), so nothing listens on the network. A platform or web view that cannot do that falls back to a server on
     * a loopback port, see {@link #loadResources(AppResources, int)}.
     */
    default void loadResources(AppResources resources) {
        loadResources(resources, (RequestHandler) null);
    }

    /**
     * Like {@link #loadResources(AppResources)}, and {@code handler} answers requests the page makes to its own app (htmx's
     * {@code hx-get} and {@code hx-post}, {@code fetch} calls to an API, plain forms) before the files do, so Java can return
     * HTML fragments or JSON. It works with any front end; htmx is not required.
     *
     * <p>With the in-process transport only this window's own web view can reach the handler, so there is no cross-site request
     * forgery to defend against; a request whose {@code Origin} is not the app's is still refused. Over the loopback fallback,
     * where any local program or web page could connect, requests are guarded instead: see
     * {@link #loadResources(AppResources, RequestHandler, int)}. {@code GET} requests must never change anything either way.
     * See {@link RequestHandler} for threading.
     */
    default void loadResources(AppResources resources, RequestHandler handler) {
        loadResources(resources, handler, ResourceOptions.defaults());
    }

    /**
     * Like {@link #loadResources(AppResources, RequestHandler)} with settings such as the largest request body, for uploads.
     */
    default void loadResources(AppResources resources, RequestHandler handler, ResourceOptions options) {
        io.github.b077as.jolt.internal.ResourceService service =
                new io.github.b077as.jolt.internal.ResourceService(resources, handler, options);
        if (this instanceof io.github.b077as.jolt.internal.NativeResourceHost host && host.loadResources(service)) {
            return;
        }
        loadResourcesOverLoopback(resources, handler, options, 0);
    }

    /**
     * Serves {@code resources} from a server on a loopback port instead of inside the web view, on a fixed {@code port} so the
     * origin, and the browser storage under it, stays the same between runs ({@code 0} picks a free one, and storage is then lost
     * on the next run). Use it when you want to open the same address in a normal browser to debug, or as a fallback.
     */
    default void loadResources(AppResources resources, int port) {
        loadResources(resources, null, port);
    }

    /**
     * Like {@link #loadResources(AppResources, int)} with a {@code handler}. Because any program on the machine, and any website
     * the user has open in a browser, can send requests to a loopback port, they are guarded: a {@code POST}, {@code PUT},
     * {@code PATCH} or {@code DELETE} is refused unless it carries this run's secret, which Jolt adds by itself to htmx requests
     * and to {@code fetch} calls from the app's page (and exposes as {@code window.joltCsrfToken} for anything else, such as
     * {@code XMLHttpRequest}). Plain HTML {@code <form method=post>} forms get it as a hidden field (urlencoded or
     * multipart; for {@code form.submit()}, use {@code requestSubmit()} or {@code hx-post}). Other websites and local programs
     * cannot learn the secret.
     */
    default void loadResources(AppResources resources, RequestHandler handler, int port) {
        loadResourcesOverLoopback(resources, handler, ResourceOptions.defaults(), port);
    }

    /** Like {@link #loadResources(AppResources, RequestHandler, int)} with {@link ResourceOptions}. */
    default void loadResourcesOverLoopback(AppResources resources, RequestHandler handler, ResourceOptions options, int port) {
        io.github.b077as.jolt.internal.AssetServer server;
        try {
            server = new io.github.b077as.jolt.internal.AssetServer(resources, handler, options, port);
        } catch (java.io.IOException e) {
            throw new JoltException("cannot serve the app's files on port " + port, e);
        }
        onClosed(server::close);
        if (handler != null) {
            addInitScript(server.initScript());
        }
        setSecurity(security().withTrustedOrigins(server.origin()));
        loadUrl(server.origin() + "/");
    }

    /** Navigates to a document built from {@code html}. The page gets the origin {@link SecurityPolicy#APP_ORIGIN}, which is always trusted. */
    void loadHtml(String html);

    /**
     * The operating system's file dialogs (open, save, choose a folder), owned by this window.
     *
     * @throws UnsupportedOperationException if this window's platform has none
     */
    default Dialogs dialogs() {
        if (this instanceof io.github.b077as.jolt.internal.NativeDialogHost host) {
            return host.dialogs();
        }
        throw new UnsupportedOperationException("this window cannot show native dialogs");
    }

    /**
     * Lets the pages of this window save files with {@code jolt.saveAs(data, options)}: the operating system's save dialog opens,
     * and the bytes the page hands over are written to the file the user chooses. Only pages whose origin the
     * {@link SecurityPolicy} trusts can use it, the user always picks the file, and the page learns only its name. Enable it before
     * the page loads.
     *
     * <pre>{@code
     * const saved = await jolt.saveAs(blobOrBytesOrText, { name: "report.pdf", title: "Export", filters: [{ name: "PDF", extensions: ["pdf"] }] });
     * if (saved) console.log(saved.name, saved.size); else console.log("cancelled");   // null when the user cancels
     * }</pre>
     */
    default void enableSaveAs() {
        enableSaveAs(null);
    }

    /** Like {@link #enableSaveAs()}, with the folder the save dialog starts in ({@code null}: where the system last had the user). */
    default void enableSaveAs(java.nio.file.Path initialDirectory) {
        io.github.b077as.jolt.internal.PageSaveAs.enable(this, initialDirectory);
    }

    /**
     * Lets the pages of this window show system notifications with {@code jolt.notify(title, body)}, sent through
     * {@code app} (see {@link Application#notify}). Only pages whose origin the {@link SecurityPolicy} trusts can use it,
     * and a window is limited to 5 notifications per 10 seconds. Enable it before the page loads.
     *
     * <pre>{@code
     * const shown = await jolt.notify("Export finished", "report.pdf is ready", {   // resolves false if the system did not show it
     *   onclick: () => console.log("opened"),
     *   buttons: [{ label: "Open", onclick: () => open() }, { label: "Later", onclick: () => {} }],   // up to 5
     *   reply: { label: "Send", placeholder: "Reply", onreply: (text) => send(text) },               // Windows only
     * });
     * }</pre>
     * The callbacks stay in the page and run when the user uses the notification, for as long as the page and the application
     * are alive; the page remembers the last 32 notifications.
     */
    default void enableNotifications(Application app) {
        io.github.b077as.jolt.internal.PageNotify.enable(this, app);
    }

    /** Opens a {@link BinaryChannel} with the default {@link ChannelOptions}. */
    default BinaryChannel openChannel(String name) {
        return openChannel(name, ChannelOptions.defaults());
    }

    /**
     * Opens a {@link BinaryChannel}: a named pipe for large or binary data between Java and the page, which reaches it as
     * {@code jolt.channel(name)}. Open it before the page loads. Each window has its own channels.
     *
     * @throws IllegalStateException if a channel of that name is already open on this window
     */
    default BinaryChannel openChannel(String name, ChannelOptions options) {
        if (name != null && name.startsWith("jolt.")) {
            throw new IllegalArgumentException("channel names starting with \"jolt.\" are reserved: " + name);
        }
        return io.github.b077as.jolt.internal.ChannelServers.of(this).open(name, options);
    }

    /**
     * Evaluates {@code script} in the page. The future completes with the JSON encoding of the script's
     * completion value (for example {@code "42"}, {@code "\"text\""}, or {@code "null"}).
     * Fails with {@link WindowClosedException} if the window is closed before the result arrives.
     */
    CompletableFuture<String> eval(String script);

    /** Registers a script that runs in every document before the page's own scripts. */
    void addInitScript(String script);

    /**
     * Registers a listener for strings the page sends with {@code window.jolt.postMessage(text)}.
     * Runs on the UI thread.
     */
    void onMessage(Consumer<String> listener);

    /** Delivers {@code message} to listeners registered with {@code window.jolt.onMessage(cb)}. */
    void postMessage(String message);

    void setTitle(String title);

    /**
     * Sets the window icon. {@code png} is PNG data (a square image of at least 256x256 pixels with a transparent
     * background) or the bytes of a Windows {@code .ico} file with several sizes, which looks best on Windows because no
     * image has to be scaled. Bad data is logged and ignored.
     *
     * <p>On Wayland, applications cannot set their own window icon: the compositor takes it from the application's
     * desktop entry, so this has no visible effect there. On X11 and Windows it does.
     */
    void setIcon(byte[] png);

    /**
     * Makes the operating system draw this window's title bar light or dark. The page cannot do this itself, but it can
     * ask Java to (see the guide: register a handler that calls this when the page's theme changes).
     *
     * <p>Windows 10 (2004 and later) and 11 honour it. On Linux it sets GTK's dark-theme preference for the whole
     * application, which a desktop title bar follows when the window manager draws it; some desktops ignore it.
     */
    void setTitleBarScheme(ColorScheme scheme);

    /**
     * Gives the title bar an exact background and text colour ({@code 0xRRGGBB}), so it can match the page. Needs
     * <b>Windows 11</b>; ignored on Windows 10 and on Linux, where the desktop owns the title bar.
     */
    void setTitleBarColors(int backgroundRgb, int textRgb);

    /**
     * Sets the colour ({@code 0xRRGGBB}) the web view shows before and behind the page. Use your page's background colour so
     * a dark page does not flash white while it loads.
     */
    void setBackgroundColor(int rgb);

    /** Sets the client-area size in logical (DPI-independent) pixels. */
    void setSize(int width, int height);

    /**
     * Shows the window and starts the web view painting. Works the same for a window created hidden
     * ({@link WindowOptions#visible()} {@code false}): the page then renders as soon as the window appears. A window that
     * was asked to start maximized is maximized now.
     */
    void show();

    void hide();

    /**
     * Maximizes the window. On a window that is still hidden it is remembered and takes effect when the window is first
     * shown. On Linux it needs a window manager or compositor that honours the request (a bare X server does not).
     */
    void maximize();

    /** Undoes {@link #maximize()}: the window goes back to its normal size and position. */
    void restore();

    /**
     * Whether the window is maximized, or will be when it is first shown. Can be called from any thread. A request to
     * maximize is carried out on the UI thread, so right after {@link #maximize()} this may still say {@code false}.
     */
    boolean isMaximized();

    /** Opens the browser developer tools, if enabled in {@link WindowOptions}. */
    void openDevTools();

    /** The security policy currently in force. */
    SecurityPolicy security();

    /**
     * Replaces the security policy. Takes effect for everything that happens from now on (navigations, messages,
     * permission requests); a page that is already showing is not reloaded. Only {@link SecurityPolicy#bridgeInSubframes()}
     * is fixed when the window is created.
     */
    void setSecurity(SecurityPolicy policy);

    /**
     * Registers a listener called when the policy stops something, for example to open a blocked link in the user's
     * browser. Runs on the UI thread.
     */
    void onBlocked(Consumer<BlockedEvent> listener);

    /** Registers a callback that runs once after the window has been destroyed. */
    void onClosed(Runnable listener);

    /** Closes the window. */
    void close();
}
