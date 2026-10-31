package io.github.b077as.jolt;

/**
 * Decides what happens when a page calls {@code jolt.openWindow(path)}: creates the new window, or refuses. It is where the app
 * says which windows exist (a message pop-out, a settings window) and what they show.
 *
 * <pre>{@code
 * main.enableOpenWindow(app, request -> {
 *     if (!request.path().startsWith("/message/")) {
 *         return null;                                   // not a window this app has: refused
 *     }
 *     WebWindow popout = request.createWindow(app, request.options().withOwner(request.opener()));
 *     popout.loadResources(resources, router, ResourceOptions.defaults().withStartPath(request.path()));
 *     return popout;
 * });
 * }</pre>
 *
 * <p>It runs on the UI thread. Create the window with {@link OpenWindowRequest#createWindow}: it also gets
 * {@code jolt.openWindow} and {@code jolt.broadcast} (with this same opener) before it can load a page. A window made with
 * {@link Application#createWindow} instead gets them only after you return, so call {@code enableOpenWindow} on it yourself first.
 */
@FunctionalInterface
public interface WindowOpener {

    /**
     * @return the window that was created for {@code request}, or {@code null} to refuse; throwing also refuses. The page's
     *         {@code jolt.openWindow} promise then resolves with {@code false}
     */
    WebWindow open(OpenWindowRequest request);
}
