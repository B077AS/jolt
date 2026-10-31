package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.AppRequest;
import io.github.b077as.jolt.AppResources;
import io.github.b077as.jolt.AppResponse;
import io.github.b077as.jolt.ResourceOptions;
import io.github.b077as.jolt.RequestHandler;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * What a window serves with {@code loadResources}, independent of how requests reach it: the app's files plus the optional
 * {@link RequestHandler}. The native transports (a WebView2 request interceptor, a WebKitGTK URI scheme) and the loopback
 * server ({@link AssetServer}) all hand their requests to {@link #serve}, so every transport behaves the same.
 *
 * <p>Transports are responsible for what only they can know: whether the request really came from the app's own page
 * ({@link #admit} for the in-process ones; the loopback server adds a secret and more checks), and for reading the body
 * (at most {@link #maxBody()} bytes, answering 413 beyond that).
 */
public final class ResourceService {

    /** The default for the largest request body a transport hands over; see {@link #maxBody()}. */
    public static final int MAX_BODY = ResourceOptions.DEFAULT_MAX_REQUEST_BYTES;

    public static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD");
    public static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * Added to every response. {@code Referrer-Policy} is {@code same-origin} and not {@code no-referrer}: with the latter,
     * browsers send {@code Origin: null} on same-origin form posts, which would make them indistinguishable from forged ones.
     */
    private static final Map<String, String> SECURITY_HEADERS = Map.of(
            "X-Content-Type-Options", "nosniff",
            "Cross-Origin-Resource-Policy", "same-origin",
            "Referrer-Policy", "same-origin");

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"), Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("map", "application/json; charset=utf-8"), Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"), Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"), Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"), Map.entry("eot", "application/vnd.ms-fontobject"),
            Map.entry("wasm", "application/wasm"), Map.entry("webmanifest", "application/manifest+json"));

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt");

    private final AppResources resources;
    private final RequestHandler handler;
    private final int maxBody;
    private final String startPath;

    /** @param handler answers requests before the files do; may be {@code null} for a read-only file server */
    public ResourceService(AppResources resources, RequestHandler handler) {
        this(resources, handler, ResourceOptions.defaults());
    }

    public ResourceService(AppResources resources, RequestHandler handler, ResourceOptions options) {
        this.resources = resources;
        this.handler = handler;
        this.maxBody = options.maxRequestBytes();
        this.startPath = options.startPath();
    }

    /** The address inside the app the window opens on, always starting with one {@code /}. */
    public String startPath() {
        return startPath;
    }

    /** The largest request body a transport may read; a bigger one is answered with 413 without reaching the handler. */
    public int maxBody() {
        return maxBody;
    }

    public boolean hasHandler() {
        return handler != null;
    }

    /**
     * For transports that are in-process and unreachable by anything but the window's own web view: returns 0 if a request
     * with this method and these headers (lower-case names) may be served, otherwise the status to refuse it with. Methods
     * that change state are only accepted when there is a handler, and their {@code Origin}, if the browser sent one, must be
     * {@code origin}.
     */
    public int admit(String method, Map<String, String> headers, String origin) {
        boolean unsafe = UNSAFE_METHODS.contains(method);
        if (!SAFE_METHODS.contains(method) && !(unsafe && handler != null)) {
            return 405;
        }
        if (unsafe) {
            String requestOrigin = headers.get("origin");
            if (requestOrigin != null && !requestOrigin.equals(origin)) {
                return 403;
            }
        }
        return 0;
    }

    /**
     * Answers one request. Never throws: a bad URL gives 400, an unknown file 404, a failing handler or file 500 (the cause goes
     * to the log, never into the response). The response carries the security headers; for {@code HEAD} the caller drops the body.
     *
     * @param url     the request URL, absolute or just a path and query
     * @param headers request headers by lower-case name
     */
    public AppResponse serve(String method, String url, Map<String, String> headers, byte[] body) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return finish(AppResponse.status(400));
        }
        return serve(method, uri, headers, body);
    }

    public AppResponse serve(String method, URI uri, Map<String, String> headers, byte[] body) {
        String path = cleanPath(uri);
        if (path == null) {
            return finish(AppResponse.status(400));
        }
        if (handler != null) {
            try {
                String query = uri.getRawQuery();
                AppResponse response = handler.handle(new AppRequest(method, path, query == null ? "" : query, Map.copyOf(headers), body));
                if (response != null) {
                    return finish(response);
                }
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "request handler failed for " + method + " " + path, e);
                return finish(AppResponse.status(500));
            }
        }
        if (!SAFE_METHODS.contains(method)) {
            return finish(AppResponse.status(404));
        }
        String resource = resourcePath(path);
        byte[] file;
        try {
            file = resources.read(resource);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read app file " + resource, e);
            return finish(AppResponse.status(500));
        }
        if (file == null) {
            return finish(AppResponse.status(404));
        }
        return finish(new AppResponse(200, Map.of("Content-Type", contentType(resource)), file));
    }

    /** Runs {@link #serve} on a virtual thread, so a slow handler never blocks the caller (usually the UI thread). */
    public CompletableFuture<AppResponse> serveAsync(String method, String url, Map<String, String> headers, byte[] body) {
        CompletableFuture<AppResponse> result = new CompletableFuture<>();
        Thread.ofVirtual().name("jolt-resource-", 0).start(() -> {
            try {
                result.complete(serve(method, url, headers, body));
            } catch (Throwable t) { // serve() does not throw; this keeps a bug from leaving a request unanswered
                LOG.log(System.Logger.Level.ERROR, "serving a request failed", t);
                result.complete(finish(AppResponse.status(500)));
            }
        });
        return result;
    }

/**
 * Like {@link #serveAsync}, for web views that do not follow redirects from an in-process scheme (WebKitGTK hands a 303 to
 * the page as it is, and a navigation never completes). A redirect to the same origin is followed here instead:
 * <ul>
 *   <li>for a page navigation ({@code Accept} starts with {@code text/html}) the answer is a tiny page that moves to the new
 *       address, so the address bar and relative URLs stay right (a form post answered with
 *       {@code AppResponse.redirect} works as Post/Redirect/Get). It always continues with a {@code GET};
 *   <li>for everything else ({@code fetch}, {@code XMLHttpRequest}, htmx) the new address is served right away and its answer
 *       returned, with {@code GET} and no body after 301, 302 and 303 and the same method and body after 307 and 308.
 * </ul>
 * A redirect to another origin, or one that loops (more than 10 hops), is returned unchanged.
 */
public CompletableFuture<AppResponse> serveAsyncFollowingRedirects(String method, String url, Map<String, String> headers, byte[] body) {
    CompletableFuture<AppResponse> result = new CompletableFuture<>();
    Thread.ofVirtual().name("jolt-resource-", 0).start(() -> {
        try {
            result.complete(serveFollowingRedirects(method, url, headers, body));
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.ERROR, "serving a request failed", t);
            result.complete(finish(AppResponse.status(500)));
        }
    });
    return result;
}

AppResponse serveFollowingRedirects(String method, String url, Map<String, String> headers, byte[] body) {
    boolean navigation = String.valueOf(headers.get("accept")).startsWith("text/html");
    for (int hops = 0; ; hops++) {
        AppResponse response = serve(method, url, headers, body);
        String location = response.headers().entrySet().stream()
                .filter(h -> h.getKey().equalsIgnoreCase("Location")).map(Map.Entry::getValue).findFirst().orElse(null);
        int status = response.status();
        if (location == null || status < 300 || status > 399 || status == 304 || hops >= 10) {
            return response;
        }
        URI target;
        URI current;
        try {
            current = new URI(url);
            target = current.resolve(location);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return response;
        }
        if (!sameOrigin(current, target)) {
            return response;
        }
        if (navigation) {
            return finish(redirectPage(target.toString()));
        }
        url = target.toString();
        if (status != 307 && status != 308) {
            method = method.equals("HEAD") ? "HEAD" : "GET";
            body = new byte[0];
        }
    }
}

private static boolean sameOrigin(URI a, URI b) {
    return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
            && a.getRawAuthority() != null && a.getRawAuthority().equalsIgnoreCase(b.getRawAuthority());
}

private static AppResponse redirectPage(String target) {
    String attribute = target.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    return AppResponse.html("<!doctype html><meta charset=\"utf-8\"><meta http-equiv=\"refresh\" content=\"0;url=" + attribute
            + "\"><script>location.replace(" + JsLiteral.quote(target) + ")</script>");
}

/** {@code response} plus the security headers (and {@code Cache-Control: no-cache} unless it chose its own). */
    public static AppResponse finish(AppResponse response) {
        Map<String, String> headers = new LinkedHashMap<>(response.headers());
        SECURITY_HEADERS.forEach((name, value) -> {
            headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
            headers.put(name, value);
        });
        if (headers.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("Cache-Control"))) {
            headers.put("Cache-Control", "no-cache");
        }
        return new AppResponse(response.status(), headers, response.body());
    }

    /** {@link #SECURITY_HEADERS} plus {@code Cache-Control}, for responses a transport produces itself (refusals). */
    public static Map<String, String> baseHeaders() {
        Map<String, String> headers = new LinkedHashMap<>(SECURITY_HEADERS);
        headers.put("Cache-Control", "no-cache");
        return headers;
    }

    /**
     * The path a request URI asks for: decoded, starting with {@code /}, empty segments and {@code .} dropped, a trailing
     * slash kept. Returns {@code null} for a path that would leave the root ({@code ..}) or is not valid.
     */
    public static String cleanPath(URI uri) {
        String raw = uri.getRawPath();
        if (raw == null || raw.contains("\\") || raw.contains("%5c") || raw.contains("%5C")) {
            return null;
        }
        String decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        if (decoded.indexOf('\0') >= 0 || decoded.contains("\\")) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String part : decoded.split("/", -1)) {
            if (part.equals("..")) {
                return null;
            }
            if (!part.isEmpty() && !part.equals(".")) {
                parts.add(part);
            }
        }
        return "/" + String.join("/", parts) + (!parts.isEmpty() && decoded.endsWith("/") ? "/" : "");
    }

    /** The resource path for a cleaned request path: no leading slash, and {@code index.html} for a folder. */
    public static String resourcePath(String cleanPath) {
        String relative = cleanPath.substring(1);
        return relative.isEmpty() || relative.endsWith("/") ? relative + "index.html" : relative;
    }

    /** The resource path a request URI asks for, or {@code null} if the request is not valid. */
    public static String normalize(URI uri) {
        String clean = cleanPath(uri);
        return clean == null ? null : resourcePath(clean);
    }

    public static String contentType(String path) {
        int dot = path.lastIndexOf('.');
        String type = dot < 0 ? null : TYPES.get(path.substring(dot + 1).toLowerCase(Locale.ROOT));
        return type != null ? type : "application/octet-stream";
    }

    /** The standard reason phrase for an HTTP status code. */
    public static String reasonPhrase(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 206 -> "Partial Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 303 -> "See Other";
            case 304 -> "Not Modified";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 413 -> "Payload Too Large";
            case 415 -> "Unsupported Media Type";
            case 422 -> "Unprocessable Content";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> status < 400 ? "OK" : "Error";
        };
    }
}
