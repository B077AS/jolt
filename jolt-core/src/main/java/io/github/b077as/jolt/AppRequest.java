package io.github.b077as.jolt;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A request from the page to a {@link RequestHandler}. By the time it arrives Jolt has already checked that it came
 * from the app's own page (see {@link WebWindow#loadResources(AppResources, RequestHandler)}), but its contents are still
 * untrusted input.
 *
 * @param method  {@code GET}, {@code HEAD}, {@code POST}, {@code PUT}, {@code PATCH} or {@code DELETE}
 * @param path    the decoded path starting with {@code /}, never containing {@code .} or {@code ..} segments
 * @param query   the raw query string without the {@code ?}, or an empty string
 * @param headers header values by lower-case name (multiple values joined with {@code ", "})
 * @param body    the request body, empty if none
 * @param pathParams the values of the {@code {name}} parts of the {@link Router} route that matched, empty otherwise
 */
public record AppRequest(String method, String path, String query, Map<String, String> headers, byte[] body,
                         Map<String, String> pathParams) {

    public AppRequest {
        pathParams = pathParams == null ? Map.of() : Map.copyOf(pathParams);
    }

    public AppRequest(String method, String path, String query, Map<String, String> headers, byte[] body) {
        this(method, path, query, headers, body, Map.of());
    }

    /** The value of the route's {@code {name}} part, decoded, or {@code null} if the route has no such part. */
    public String param(String name) {
        return pathParams.get(name);
    }

    /** A copy carrying these route parameters. */
    public AppRequest withPathParams(Map<String, String> params) {
        return new AppRequest(method, path, query, headers, body, params);
    }

    /** The value of a header, or {@code null}. */
    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /** Whether htmx sent this request ({@code HX-Request: true}). */
    public boolean isHtmx() {
        return "true".equals(header("hx-request"));
    }

    /** The body decoded as UTF-8. */
    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** The query parameters, decoded; a repeated name keeps all its values in order. */
    public Map<String, List<String>> queryParams() {
        return parseForm(query);
    }

    /** The body parsed as {@code application/x-www-form-urlencoded}, which is what htmx and plain forms send. */
    public Map<String, List<String>> form() {
        return parseForm(bodyText());
    }

    /** The first value of the form field {@code name}, or {@code null}. */
    public String formValue(String name) {
        List<String> values = form().get(name);
        return values == null ? null : values.get(0);
    }

    private static Map<String, List<String>> parseForm(String text) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (text.isEmpty()) {
            return result;
        }
        for (String pair : text.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return result;
    }
}
