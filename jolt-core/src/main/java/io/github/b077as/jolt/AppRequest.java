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

    /**
     * The form fields of the body: for {@code application/x-www-form-urlencoded}, which is what htmx and plain forms send, the
     * decoded fields; for {@code multipart/form-data}, the text of the parts that are not files (see {@link #parts} for the files).
     * A multipart body is parsed on every call, so call {@link #parts} once when you need several fields.
     *
     * @throws IllegalArgumentException if a multipart body is malformed
     */
    public Map<String, List<String>> form() {
        if (!isMultipart()) {
            return parseForm(bodyText());
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Part part : parts()) {
            if (!part.isFile()) {
                result.computeIfAbsent(part.name(), k -> new ArrayList<>()).add(part.text());
            }
        }
        return result;
    }

    /** Whether the body is {@code multipart/form-data}, which is what a form with a file input, or {@code FormData}, sends. */
    public boolean isMultipart() {
        return io.github.b077as.jolt.internal.MultipartParser.boundary(header("content-type")) != null;
    }

    /**
     * The parts of a {@code multipart/form-data} body, in order: form fields and files. The whole body is already in memory (see
     * {@link ResourceOptions#maxRequestBytes()} for the size limit).
     *
     * @throws IllegalArgumentException if this is not a multipart request or its body is malformed; a handler can let it
     *                                  propagate to {@link Router#onError} to answer 400
     */
    public List<Part> parts() {
        String boundary = io.github.b077as.jolt.internal.MultipartParser.boundary(header("content-type"));
        if (boundary == null) {
            throw new IllegalArgumentException("not a multipart/form-data request");
        }
        return io.github.b077as.jolt.internal.MultipartParser.parts(body, boundary);
    }

    /** The first part called {@code name}, or {@code null}. */
    public Part part(String name) {
        for (Part part : parts()) {
            if (part.name().equals(name)) {
                return part;
            }
        }
        return null;
    }

    /**
     * One part of a {@code multipart/form-data} body.
     *
     * @param name        the field name
     * @param filename    for a file, the name the browser sent, <b>reduced to its last path segment with control characters
     *                    removed</b>; empty if no file was chosen; {@code null} for an ordinary field. Still untrusted: never use it
     *                    as a path without checking where it ends up
     * @param contentType the part's {@code Content-Type} (what the browser guessed for a file), or {@code null}
     * @param headers     the part's headers by lower-case name
     * @param content     the part's bytes
     */
    public record Part(String name, String filename, String contentType, Map<String, String> headers, byte[] content) {

        public Part {
            headers = Map.copyOf(headers);
        }

        /** Whether this part came from a file input (even if no file was chosen, when {@link #size} is 0). */
        public boolean isFile() {
            return filename != null;
        }

        /** The content decoded as UTF-8. */
        public String text() {
            return new String(content, StandardCharsets.UTF_8);
        }

        public int size() {
            return content.length;
        }
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
