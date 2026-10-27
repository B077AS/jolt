package io.github.b077as.jolt.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.b077as.jolt.AppResources;
import io.github.b077as.jolt.AppResponse;
import io.github.b077as.jolt.RequestHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Serves {@link AppResources} and answers {@link RequestHandler} requests over HTTP on the loopback interface, so a page
 * made of several files can use relative URLs, ES modules, {@code fetch} and htmx.
 *
 * <p>Any local program, and any web page the user has open in a browser, can send requests to a loopback port, so every
 * request is checked before it reaches the app:
 * <ul>
 *   <li>the {@code Host} header must name this server (stops DNS rebinding);
 *   <li>{@code Sec-Fetch-Site}, if the browser sends it, must say the request is same-origin or user-initiated, which
 *       also rejects pages served from another port on the same machine;
 *   <li>requests that change state ({@code POST}, {@code PUT}, {@code PATCH}, {@code DELETE}) must carry the per-run
 *       secret in {@value #TOKEN_HEADER}, which only the app's own page knows (see {@link #initScript}), and their
 *       {@code Origin}, if present, must be this server's;
 *   <li>no CORS headers are ever sent and {@code OPTIONS} is refused, so no other origin can read a response or get a
 *       preflight approved;
 *   <li>request bodies are capped at {@value #MAX_BODY} bytes.
 * </ul>
 */
public final class AssetServer implements AutoCloseable {

    public static final String TOKEN_HEADER = "X-Jolt-Token";
    /** The hidden form field Jolt adds to {@code <form method=post>} submissions, for requests that cannot set a header. */
    public static final String TOKEN_FIELD = "_jolt_token";
    static final int MAX_BODY = ResourceService.MAX_BODY;

    private static final Set<String> SAFE_METHODS = ResourceService.SAFE_METHODS;
    private static final Set<String> UNSAFE_METHODS = ResourceService.UNSAFE_METHODS;
    private static final Set<String> ALLOWED_FETCH_SITES = Set.of("same-origin", "none");

    private final HttpServer server;
    private final String origin;
    private final String token;
    private final ResourceService service;
    private final boolean hasHandler;
    private final List<String> hosts;

    /**
     * Starts serving on {@code port} ({@code 0} picks a free one).
     *
     * @param handler answers requests before the files do; may be {@code null} for a read-only file server
     */
    public AssetServer(AppResources resources, RequestHandler handler, int port) throws IOException {
        this.service = new ResourceService(resources, handler);
        this.hasHandler = handler != null;
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        int bound = server.getAddress().getPort();
        origin = "http://127.0.0.1:" + bound;
        hosts = List.of("127.0.0.1:" + bound, "localhost:" + bound);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jolt-assets-", 0).factory()));
        server.start();
    }

    /** {@code http://127.0.0.1:port}, without a trailing slash. */
    public String origin() {
        return origin;
    }

    /** The per-run secret that state-changing requests must send in {@value #TOKEN_HEADER}. */
    public String token() {
        return token;
    }

    /**
     * A script for the window to run in every document. In the app's own pages (and nowhere else) it exposes the secret as
     * {@code window.joltCsrfToken} and sends it automatically with htmx requests ({@code htmx:configRequest}) and with
     * {@code fetch} calls that change state, and adds it as a hidden field to {@code <form method=post>} submissions,
     * but only to this server, so it cannot leak to another origin.
     */
    public String initScript() {
        return """
                (() => {
                  if (location.origin !== %2$s || window.joltCsrfToken) return;
                  const T = %1$s, O = %2$s, H = %3$s, F = %4$s;
                  const sameServer = (url) => { try { return new URL(url, location.href).origin === O; } catch (e) { return false; } };
                  Object.defineProperty(window, 'joltCsrfToken', { value: T });
                  document.addEventListener('htmx:configRequest', (e) => {
                    if (sameServer(e.detail.path)) e.detail.headers[H] = T;
                  });
                  document.addEventListener('submit', (e) => {
                    const form = e.target, by = e.submitter;
                    const method = String((by && by.formMethod) || form.method || 'get').toUpperCase();
                    const enctype = String((by && by.formEnctype) || form.enctype || '').toLowerCase();
                    if (method === 'GET' || method === 'DIALOG' || enctype !== 'application/x-www-form-urlencoded') return;
                    if (!sameServer((by && by.formAction) || form.action)) return;
                    let field = form.querySelector('input[type=hidden][name=' + F + ']');
                    if (!field) { field = document.createElement('input'); field.type = 'hidden'; field.name = F; form.appendChild(field); }
                    field.value = T;
                  }, true);
                  const nativeFetch = window.fetch;
                  window.fetch = function (input, init) {
                    const request = new Request(input, init);
                    if (!['GET', 'HEAD'].includes(request.method.toUpperCase()) && sameServer(request.url)) request.headers.set(H, T);
                    return nativeFetch.call(this, request);
                  };
                })();
                """.formatted(JsLiteral.quote(token), JsLiteral.quote(origin), JsLiteral.quote(TOKEN_HEADER), JsLiteral.quote(TOKEN_FIELD));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            int rejected = check(exchange);
            if (rejected != 0) {
                send(exchange, rejected, Map.of(), null, false);
                return;
            }
            String method = exchange.getRequestMethod();
            boolean head = method.equals("HEAD");
            byte[] body = new byte[0];
            if (hasHandler) {
                boolean unsafe = UNSAFE_METHODS.contains(method);
                String sent = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
                boolean headerOk = unsafe && sent != null && tokenMatches(sent);
                String type = String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")).toLowerCase(Locale.ROOT);
                if (unsafe && !headerOk && !type.startsWith("application/x-www-form-urlencoded")) {
                    send(exchange, 403, Map.of(), null, false);
                    return;
                }
                body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
                if (body.length > MAX_BODY) {
                    send(exchange, 413, Map.of(), null, false);
                    return;
                }
                if (unsafe) {
                    // a plain HTML form cannot set headers, so its secret travels as a field; either way the handler never sees it
                    FormToken form = FormToken.split(body);
                    if (!headerOk && (form.value == null || !tokenMatches(form.value))) {
                        send(exchange, 403, Map.of(), null, false);
                        return;
                    }
                    body = form.rest;
                }
            }
            AppResponse response = service.serve(method, exchange.getRequestURI(), headersOf(exchange), body);
            send(exchange, response.status(), response.headers(), response.body(), head);
        }
    }

    /** Returns 0 if the request may proceed, otherwise the status to refuse it with. */
    private int check(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        boolean unsafe = UNSAFE_METHODS.contains(method);
        if (!SAFE_METHODS.contains(method) && !(unsafe && hasHandler)) {
            return 405;
        }
        if (!hosts.contains(String.valueOf(exchange.getRequestHeaders().getFirst("Host")).toLowerCase(Locale.ROOT))) {
            return 403;
        }
        String site = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if (site != null && !ALLOWED_FETCH_SITES.contains(site.toLowerCase(Locale.ROOT))) {
            return 403;
        }
        if (unsafe) {
            String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
            if (requestOrigin != null && !requestOrigin.equals(origin)) {
                return 403;
            }
        }
        return 0;
    }

    private boolean tokenMatches(String candidate) {
        return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    /** A urlencoded body split into the {@value #TOKEN_FIELD} field's value (or {@code null}) and everything else. */
    private record FormToken(String value, byte[] rest) {
        static FormToken split(byte[] body) {
            String text = new String(body, StandardCharsets.ISO_8859_1);
            String value = null;
            StringBuilder rest = new StringBuilder();
            for (String pair : text.split("&")) {
                if (pair.equals(TOKEN_FIELD) || pair.startsWith(TOKEN_FIELD + "=")) {
                    if (value == null) {
                        value = URLDecoder.decode(pair.length() > TOKEN_FIELD.length() ? pair.substring(TOKEN_FIELD.length() + 1) : "", StandardCharsets.UTF_8);
                    }
                } else if (!pair.isEmpty()) {
                    rest.append(rest.isEmpty() ? "" : "&").append(pair);
                }
            }
            return value == null && !text.contains(TOKEN_FIELD) ? new FormToken(null, body) : new FormToken(value, rest.toString().getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    /** Request headers by lower-case name, without the secret so a handler cannot log or echo it by accident. */
    private static Map<String, String> headersOf(HttpExchange exchange) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!name.equalsIgnoreCase(TOKEN_HEADER)) {
                headers.put(name.toLowerCase(Locale.ROOT), String.join(", ", values));
            }
        });
        return Map.copyOf(headers);
    }

    private static void send(HttpExchange exchange, int status, Map<String, String> headers, byte[] body, boolean head) throws IOException {
        ResourceService.baseHeaders().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        if (body == null || head || body.length == 0 || status == 204 || status == 304) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    /** The resource path a request URI asks for, or {@code null} if the request is not valid. */
    static String normalize(URI uri) {
        return ResourceService.normalize(uri);
    }

    static String contentType(String path) {
        return ResourceService.contentType(path);
    }
}
