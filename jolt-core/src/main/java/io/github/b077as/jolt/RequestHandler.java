package io.github.b077as.jolt;

/**
 * Answers the requests a page makes to its own app, such as htmx's {@code hx-get} and {@code hx-post}, with Java code
 * instead of a file. Pass it to {@link WebWindow#loadResources(AppResources, RequestHandler)}.
 *
 * <p>Handlers run on a worker thread, not the UI thread, and may run concurrently. To touch the window or the
 * application, use {@code Application.dispatch}. Treat everything in the request as untrusted input.
 */
@FunctionalInterface
public interface RequestHandler {

    /**
     * Returns the response, or {@code null} to let the app's files answer (for a {@code GET}/{@code HEAD}; any other
     * method then gets 404). An exception becomes a bare 500 response; its message never reaches the page.
     */
    AppResponse handle(AppRequest request) throws Exception;
}
