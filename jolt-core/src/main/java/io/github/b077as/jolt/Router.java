package io.github.b077as.jolt;

import io.github.b077as.jolt.internal.TypeRouted;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Sends each request to the code registered for its method and path. It is a {@link RequestHandler}, so it goes straight into
 * {@link WebWindow#loadResources(AppResources, RequestHandler)}:
 *
 * <pre>{@code
 * Router router = Router.create()
 *     .get("/", request -> AppResponse.html(pages.home()))
 *     .get("/notes/{id}", request -> AppResponse.html(pages.note(request.param("id"))))
 *     .post("/notes", request -> {
 *         notes.add(request.formValue("text"));
 *         return AppResponse.redirect("/notes");
 *     });
 * window.loadResources(AppResources.classpath("ui"), router);
 * }</pre>
 *
 * <p><b>Patterns</b> start with {@code /} and are made of segments: a plain segment matches itself (case-sensitively),
 * {@code {name}} matches any one segment and is read with {@link AppRequest#param}, and {@code {*name}} as the last segment
 * matches the rest of the path (possibly nothing, joined with {@code /}). A trailing slash on the pattern or the request makes no
 * difference. Values are already URL-decoded, so a {@code %2F} in the path counts as a segment separator.
 *
 * <p><b>Which route wins.</b> When several routes match, a plain segment beats {@code {name}}, which beats {@code {*name}}, segment
 * by segment from the left; if that does not decide it, the route registered first wins. Two routes with the same method and shape
 * ({@code /a/{x}} and {@code /a/{y}}) are rejected when registered.
 *
 * <p><b>What it does not match falls through</b> to the app's files: a request no route matches gets {@code null}, so the files
 * (and then a 404) answer. A {@code HEAD} request uses the {@code GET} route unless a {@code HEAD} route exists. If the path
 * matches routes but none for the request's method, a {@code POST}, {@code PUT}, {@code PATCH} or {@code DELETE} gets 405 with an
 * {@code Allow} header; a {@code GET} falls through, so a route that only handles {@code POST} does not hide a file.
 *
 * <p>Register routes before {@code loadResources}. Handlers run on worker threads, possibly at the same time (see
 * {@link RequestHandler}); registering routes later is allowed but is not meant to be done while requests are running.
 */
public final class Router implements RequestHandler {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.router");

    /** Code that answers a request. May throw; see {@link #onException}. */
    @FunctionalInterface
    public interface Route {
        AppResponse handle(AppRequest request) throws Exception;
    }

    /**
     * Runs before a matching route. Return a response to answer the request there (a login redirect, a 403), or {@code null} to go
     * on to the next filter and then the route.
     */
    @FunctionalInterface
    public interface Filter {
        AppResponse before(AppRequest request) throws Exception;
    }

    /**
     * Says what is sent when a filter or route throws an exception of a given type; register it with {@link #onException}. The
     * counterpart of {@link RpcExceptionHandler}.
     *
     * @param <E> the exception type
     */
    @FunctionalInterface
    public interface ExceptionHandler<E extends Throwable> {
        /** @return the response to send, never {@code null}; for example an error page or a 400 */
        AppResponse handle(AppRequest request, E error);
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final int LITERAL = 0;
    private static final int PARAM = 1;
    private static final int REST = 2;

    private record Segment(int kind, String text) {}

    private record Entry(String method, List<Segment> segments, Route route, List<Filter> filters) {
        String shape() {
            StringBuilder shape = new StringBuilder(method);
            for (Segment segment : segments) {
                shape.append('/').append(segment.kind == LITERAL ? segment.text : segment.kind == PARAM ? "{}" : "{*}");
            }
            return shape.toString();
        }

        /** The route's parameters if it matches these path segments, otherwise {@code null}. */
        Map<String, String> match(List<String> path) {
            Map<String, String> params = new HashMap<>();
            for (int i = 0; i < segments.size(); i++) {
                Segment segment = segments.get(i);
                if (segment.kind == REST) {
                    params.put(segment.text, String.join("/", path.subList(Math.min(i, path.size()), path.size())));
                    return params;
                }
                if (i >= path.size()) {
                    return null;
                }
                if (segment.kind == LITERAL && !segment.text.equals(path.get(i))) {
                    return null;
                }
                if (segment.kind == PARAM) {
                    params.put(segment.text, path.get(i));
                }
            }
            return segments.size() == path.size() ? params : null;
        }

        /** Orders matching routes: -1 if this one is more specific than {@code other}, 1 if less, 0 if equal. */
        int compareSpecificity(Entry other) {
            for (int i = 0; i < Math.min(segments.size(), other.segments.size()); i++) {
                int difference = Integer.compare(segments.get(i).kind, other.segments.get(i).kind);
                if (difference != 0) {
                    return difference;
                }
            }
            return 0;
        }
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final List<Filter> filters = new CopyOnWriteArrayList<>();
    private final TypeRouted<ExceptionHandler<?>> exceptionHandlers = new TypeRouted<>();
    private volatile BiConsumer<AppRequest, Throwable> errorListener;

    private Router() {}

    public static Router create() {
        return new Router();
    }

    public Router get(String pattern, Route route) {
        return route("GET", pattern, route);
    }

    public Router post(String pattern, Route route) {
        return route("POST", pattern, route);
    }

    public Router put(String pattern, Route route) {
        return route("PUT", pattern, route);
    }

    public Router patch(String pattern, Route route) {
        return route("PATCH", pattern, route);
    }

    public Router delete(String pattern, Route route) {
        return route("DELETE", pattern, route);
    }

    /**
     * Registers a route for one HTTP method ({@code GET}, {@code HEAD}, {@code POST}, {@code PUT}, {@code PATCH} or
     * {@code DELETE}).
     *
     * @throws IllegalArgumentException for an unknown method, a malformed pattern, or a route that already exists
     */
    public Router route(String method, String pattern, Route route) {
        if (route == null) {
            throw new IllegalArgumentException("route is null");
        }
        String upper = method == null ? "" : method.toUpperCase(java.util.Locale.ROOT);
        if (!List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE").contains(upper)) {
            throw new IllegalArgumentException("not a supported method: " + method);
        }
        add(new Entry(upper, parse(pattern), route, List.of()));
        return this;
    }

    /**
     * Adds routes under a common prefix, which may itself have {@code {name}} parts. Filters registered inside the group apply to
     * its routes only:
     *
     * <pre>{@code
     * router.group("/admin", admin -> admin
     *     .before(requireLogin)
     *     .get("/users", ...));
     * }</pre>
     */
    public Router group(String prefix, Consumer<Router> routes) {
        List<Segment> head = parse(prefix);
        if (!head.isEmpty() && head.get(head.size() - 1).kind == REST) {
            throw new IllegalArgumentException("a group prefix cannot end in {*name}: " + prefix);
        }
        Router group = new Router();
        routes.accept(group);
        for (Entry entry : group.entries) {
            List<Segment> all = new ArrayList<>(head);
            all.addAll(entry.segments);
            List<Filter> scoped = new ArrayList<>(group.filters);
            scoped.addAll(entry.filters);
            add(new Entry(entry.method, validated(all, prefix), entry.route, List.copyOf(scoped)));
        }
        return this;
    }

    /**
     * Runs {@code filter} before every route of this router (before the routes of a group, for a filter on a group), in the order
     * filters were added. Filters run only for requests that matched a route, never for the app's files.
     */
    public Router before(Filter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("filter is null");
        }
        filters.add(filter);
        return this;
    }

    /**
     * Says what is sent when a filter or route throws an exception of {@code type} (or a subclass), replacing any earlier handler for
     * the same type. When several types match, the most specific one wins, as with {@code catch} clauses and
     * {@link Rpc#onException}; {@code Exception.class} catches everything the others do not.
     *
     * <pre>{@code
     * router.onException(NotFoundException.class, (request, e) -> AppResponse.html(pages.notFound()).withStatus(404))
     *       .onException(IllegalArgumentException.class, (request, e) -> AppResponse.text(e.getMessage()).withStatus(400));
     * }</pre>
     * An exception with a handler is an expected failure: nothing is logged and {@link #onError} is not told, unless the handler
     * answers with a 5xx status (a failure the app turned into an error page is still a bug). An exception without a handler goes on
     * and Jolt answers with a bare 500, logging the cause. If a handler throws or returns {@code null}, that is logged and the
     * exception is treated as unhandled.
     *
     * <p>Handlers can be added and replaced at any time.
     */
    public <E extends Throwable> Router onException(Class<E> type, ExceptionHandler<? super E> handler) {
        if (type == null || handler == null) {
            throw new IllegalArgumentException("type and handler are required");
        }
        exceptionHandlers.put(type, handler);
        return this;
    }

    /**
     * Called with every unexpected exception of a filter or route, on the request's thread, for example to report it: one that has no
     * {@link #onException} handler, or whose handler answers with a 5xx status. {@code null} removes the listener. The counterpart of
     * {@link Rpc#onError}.
     */
    public Router onError(BiConsumer<AppRequest, Throwable> listener) {
        this.errorListener = listener;
        return this;
    }

    @Override
    public AppResponse handle(AppRequest request) throws Exception {
        try {
            return dispatch(request);
        } catch (Exception e) {
            return failed(request, e);
        }
    }

    /** The answer the app's {@link #onException} handler gives for {@code error}, or {@code error} is rethrown. */
    @SuppressWarnings("unchecked")
    private AppResponse failed(AppRequest request, Exception error) throws Exception {
        AppResponse response = null;
        ExceptionHandler<Throwable> handler = (ExceptionHandler<Throwable>) exceptionHandlers.find(error);
        if (handler != null) {
            try {
                response = handler.handle(request, error);
                if (response == null) {
                    LOG.log(System.Logger.Level.ERROR, "the Router onException handler for " + error.getClass().getName() + " returned null for "
                            + request.method() + " " + request.path());
                }
            } catch (Exception inner) {
                LOG.log(System.Logger.Level.ERROR, "the Router onException handler for " + error.getClass().getName() + " failed for "
                        + request.method() + " " + request.path(), inner);
            }
        }
        if (response != null && response.status() < 500) {
            LOG.log(System.Logger.Level.DEBUG, request.method() + " " + request.path() + " failed with " + response.status(), error);
            return response;
        }
        if (response != null) {
            LOG.log(System.Logger.Level.WARNING, request.method() + " " + request.path() + " failed", error);
        }
        BiConsumer<AppRequest, Throwable> listener = errorListener;
        if (listener != null) {
            try {
                listener.accept(request, error);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "the Router onError listener failed", e);
            }
        }
        if (response != null) {
            return response;
        }
        throw error; // no handler: the caller logs it and answers 500
    }

    private AppResponse dispatch(AppRequest request) throws Exception {
        List<String> path = segmentsOf(request.path());
        String method = request.method();
        Entry best = null;
        Map<String, String> bestParams = null;
        TreeSet<String> allowed = new TreeSet<>();
        for (String wanted : method.equals("HEAD") ? List.of("HEAD", "GET") : List.of(method)) {
            for (Entry entry : entries) {
                Map<String, String> params = entry.match(path);
                if (params == null) {
                    continue;
                }
                if (entry.method.equals(wanted)) {
                    if (best == null || entry.compareSpecificity(best) < 0) {
                        best = entry;
                        bestParams = params;
                    }
                }
            }
            if (best != null) {
                break;
            }
        }
        if (best == null) {
            for (Entry entry : entries) {
                if (entry.match(path) != null) {
                    allowed.add(entry.method);
                    if (entry.method.equals("GET")) {
                        allowed.add("HEAD");
                    }
                }
            }
            boolean safe = method.equals("GET") || method.equals("HEAD");
            return allowed.isEmpty() || safe ? null : AppResponse.status(405).withHeader("Allow", String.join(", ", allowed));
        }
        AppRequest routed = request.withPathParams(bestParams);
        for (Filter filter : filters) {
            AppResponse answer = filter.before(routed);
            if (answer != null) {
                return answer;
            }
        }
        for (Filter filter : best.filters) {
            AppResponse answer = filter.before(routed);
            if (answer != null) {
                return answer;
            }
        }
        return best.route.handle(routed);
    }

    private void add(Entry entry) {
        String shape = entry.shape();
        for (Entry existing : entries) {
            if (existing.shape().equals(shape)) {
                throw new IllegalArgumentException("duplicate route: " + shape.replaceFirst("^[A-Z]+", "$0 "));
            }
        }
        entries.add(entry);
    }

    private static List<String> segmentsOf(String path) {
        List<String> segments = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                segments.add(part);
            }
        }
        return segments;
    }

    private static List<Segment> parse(String pattern) {
        if (pattern == null || !pattern.startsWith("/")) {
            throw new IllegalArgumentException("a pattern must start with '/': " + pattern);
        }
        List<Segment> segments = new ArrayList<>();
        String body = pattern.substring(1);
        if (body.endsWith("/")) {
            body = body.substring(0, body.length() - 1);
        }
        if (!body.isEmpty()) {
            for (String part : body.split("/", -1)) {
                if (part.isEmpty()) {
                    throw new IllegalArgumentException("empty segment in pattern: " + pattern);
                }
                if (part.startsWith("{") && part.endsWith("}")) {
                    boolean rest = part.startsWith("{*");
                    String name = part.substring(rest ? 2 : 1, part.length() - 1);
                    if (!NAME.matcher(name).matches()) {
                        throw new IllegalArgumentException("bad parameter name '" + name + "' in pattern: " + pattern);
                    }
                    segments.add(new Segment(rest ? REST : PARAM, name));
                } else if (part.contains("{") || part.contains("}")) {
                    throw new IllegalArgumentException("a parameter must be a whole segment, like {id}: " + pattern);
                } else {
                    segments.add(new Segment(LITERAL, part));
                }
            }
        }
        return validated(segments, pattern);
    }

    /** Checks that {@code {*name}} is last and that no parameter name is used twice. */
    private static List<Segment> validated(List<Segment> segments, String pattern) {
        Map<String, Boolean> names = new LinkedHashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (segment.kind == REST && i != segments.size() - 1) {
                throw new IllegalArgumentException("{*" + segment.text + "} must be the last segment: " + pattern);
            }
            if (segment.kind != LITERAL && names.put(segment.text, true) != null) {
                throw new IllegalArgumentException("parameter '" + segment.text + "' is used twice: " + pattern);
            }
        }
        return List.copyOf(segments);
    }
}
