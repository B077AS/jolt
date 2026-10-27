package io.github.b077as.jolt;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a {@link RequestHandler} answers with. Build one with {@link #html}, {@link #text}, {@link #status} or
 * {@link #redirect} and add headers such as htmx's {@code HX-Trigger} with {@link #withHeader}.
 *
 * <p>Escape anything user-supplied before you put it in HTML; Jolt cannot do that for you.
 *
 * @param status  the HTTP status code
 * @param headers response headers; names and values may not contain line breaks
 * @param body    the response body, empty if none
 */
public record AppResponse(int status, Map<String, String> headers, byte[] body) {

    public AppResponse {
        if (status < 200 || status > 599) {
            throw new IllegalArgumentException("not a usable status code: " + status);
        }
        Map<String, String> copy = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            if (name.isEmpty() || hasLineBreak(name) || hasLineBreak(value)) {
                throw new IllegalArgumentException("bad header: " + name);
            }
            copy.put(name, value);
        });
        headers = Map.copyOf(copy);
        body = body.clone();
    }

    /** 200 with an HTML body (UTF-8), for example a fragment htmx swaps into the page. */
    public static AppResponse html(String html) {
        return new AppResponse(200, Map.of("Content-Type", "text/html; charset=utf-8"), html.getBytes(StandardCharsets.UTF_8));
    }

    /** 200 with a plain-text body (UTF-8). */
    public static AppResponse text(String text) {
        return new AppResponse(200, Map.of("Content-Type", "text/plain; charset=utf-8"), text.getBytes(StandardCharsets.UTF_8));
    }

    /** An empty response with {@code status}. htmx swaps nothing for 204. */
    public static AppResponse status(int status) {
        return new AppResponse(status, Map.of(), new byte[0]);
    }

    /** 303 See Other to {@code location}, for the browser to follow with a {@code GET}. */
    public static AppResponse redirect(String location) {
        return new AppResponse(303, Map.of("Location", location), new byte[0]);
    }

    /** A copy with one more header, replacing any header of the same name. */
    public AppResponse withHeader(String name, String value) {
        Map<String, String> all = new LinkedHashMap<>(headers);
        all.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
        all.put(name, value);
        return new AppResponse(status, all, body);
    }

    /** A copy with a different status. */
    public AppResponse withStatus(int newStatus) {
        return new AppResponse(newStatus, headers, body);
    }

    private static boolean hasLineBreak(String s) {
        return s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\0') >= 0;
    }
}
