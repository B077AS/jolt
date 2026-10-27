package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.AppRequest;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a {@code multipart/form-data} body (RFC 7578) into parts. The body is already in memory, so this is one pass over a byte
 * array. It is strict about the structure (a missing closing delimiter is an error, not a guess) and lenient about what browsers
 * do differently: a preamble, an epilogue, transport padding after a delimiter, and {@code %22}, {@code %0D} and {@code %0A} in
 * names, which is how browsers write a quote or line break in a field or file name.
 */
public final class MultipartParser {

    /** More parts than any form has; a hostile body of tiny parts would otherwise cost a lot of objects. */
    public static final int MAX_PARTS = 2000;
    /** RFC 2046 allows 70 characters; some clients use a few more. */
    private static final int MAX_BOUNDARY = 200;
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] HEADER_END = {'\r', '\n', '\r', '\n'};

    private MultipartParser() {}

    /** A part and where its bytes lie in the body, from its delimiter up to the next delimiter. */
    public record Span(AppRequest.Part part, int start, int end) {}

    /** Result of {@link #stripField}. */
    public record Stripped(String value, byte[] rest) {}

    /** The boundary of a {@code multipart/form-data} content type, or {@code null} if it is another type or has no usable boundary. */
    public static String boundary(String contentType) {
        if (contentType == null) {
            return null;
        }
        List<String> pieces = splitParameters(contentType);
        if (pieces.isEmpty() || !pieces.get(0).trim().equalsIgnoreCase("multipart/form-data")) {
            return null;
        }
        for (int i = 1; i < pieces.size(); i++) {
            String[] pair = keyValue(pieces.get(i));
            if (pair != null && pair[0].equalsIgnoreCase("boundary")) {
                String boundary = pair[1];
                boolean usable = !boundary.isEmpty() && boundary.length() <= MAX_BOUNDARY
                        && boundary.chars().noneMatch(c -> c < 0x20 || c > 0x7e);
                return usable ? boundary : null;
            }
        }
        return null;
    }

    /** The parts of {@code body}. */
    public static List<AppRequest.Part> parts(byte[] body, String boundary) {
        List<AppRequest.Part> parts = new ArrayList<>();
        for (Span span : parse(body, boundary)) {
            parts.add(span.part());
        }
        return parts;
    }

    /** Every part with its position in the body. Throws {@link IllegalArgumentException} if the body is not well formed. */
    public static List<Span> parse(byte[] body, String boundary) {
        byte[] dash = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] nextDelimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        List<Span> spans = new ArrayList<>();
        int delimiter = firstDelimiter(body, dash);
        if (delimiter < 0) {
            throw malformed("no opening boundary");
        }
        while (true) {
            int after = delimiter + dash.length;
            if (startsWith(body, after, new byte[] {'-', '-'})) {
                return spans; // the closing delimiter; anything after it is the epilogue
            }
            while (after < body.length && (body[after] == ' ' || body[after] == '\t')) {
                after++; // transport padding
            }
            if (!startsWith(body, after, CRLF)) {
                throw malformed("a boundary is not followed by a line break");
            }
            int headersStart = after + 2;
            int contentStart;
            Map<String, String> headers;
            if (startsWith(body, headersStart, CRLF)) {
                headers = Map.of();
                contentStart = headersStart + 2;
            } else {
                int headersEnd = indexOf(body, HEADER_END, headersStart);
                if (headersEnd < 0) {
                    throw malformed("a part has no end of headers");
                }
                headers = headers(new String(body, headersStart, headersEnd - headersStart, StandardCharsets.UTF_8));
                contentStart = headersEnd + 4;
            }
            int contentEnd = indexOf(body, nextDelimiter, contentStart);
            if (contentEnd < 0) {
                throw malformed("the closing boundary is missing");
            }
            int next = contentEnd + 2;
            if (spans.size() >= MAX_PARTS) {
                throw malformed("more than " + MAX_PARTS + " parts");
            }
            spans.add(new Span(part(headers, java.util.Arrays.copyOfRange(body, contentStart, contentEnd)), delimiter, next));
            delimiter = next;
        }
    }

    /**
     * Removes every part named {@code field} from {@code body}, for a form field Jolt added itself. Returns {@code null} if there is
     * no such part, otherwise its text and the body without it.
     */
    public static Stripped stripField(byte[] body, String boundary, String field) {
        String value = null;
        ByteArrayOutputStream rest = new ByteArrayOutputStream(body.length);
        int copied = 0;
        boolean found = false;
        for (Span span : parse(body, boundary)) {
            if (field.equals(span.part().name()) && !span.part().isFile()) {
                found = true;
                if (value == null) {
                    value = span.part().text();
                }
                rest.write(body, copied, span.start() - copied);
                copied = span.end();
            }
        }
        if (!found) {
            return null;
        }
        rest.write(body, copied, body.length - copied);
        return new Stripped(value, rest.toByteArray());
    }

    private static AppRequest.Part part(Map<String, String> headers, byte[] content) {
        String disposition = headers.get("content-disposition");
        String name = null;
        String filename = null;
        if (disposition != null) {
            List<String> pieces = splitParameters(disposition);
            for (int i = 1; i < pieces.size(); i++) {
                String[] pair = keyValue(pieces.get(i));
                if (pair == null) {
                    continue;
                }
                if (pair[0].equalsIgnoreCase("name")) {
                    name = unescape(pair[1]);
                } else if (pair[0].equalsIgnoreCase("filename")) {
                    filename = safeFilename(unescape(pair[1]));
                }
            }
        }
        if (name == null) {
            throw malformed("a part has no name");
        }
        return new AppRequest.Part(name, filename, headers.get("content-type"), headers, content);
    }

    private static Map<String, String> headers(String text) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : text.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw malformed("a part header has no name");
            }
            headers.merge(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim(), (a, b) -> a + ", " + b);
        }
        return headers;
    }

    /** The name part of a path, without control characters, and never {@code .} or {@code ..}. May be empty ("no file chosen"). */
    static String safeFilename(String raw) {
        String name = raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 0x20 && c != 0x7f) {
                clean.append(c);
            }
        }
        String result = clean.toString().strip();
        return result.equals(".") || result.equals("..") ? "" : result;
    }

    /** What browsers write for a quote or a line break inside a quoted name. */
    private static String unescape(String value) {
        return value.replace("%22", "\"").replace("%0D", "\r").replace("%0d", "\r").replace("%0A", "\n").replace("%0a", "\n");
    }

    /** Splits {@code a; b="c;d"; e=f} at the semicolons that are outside quotes. */
    private static List<String> splitParameters(String header) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (quoted && c == '\\' && i + 1 < header.length()) {
                current.append(c).append(header.charAt(++i));
            } else if (c == '"') {
                quoted = !quoted;
                current.append(c);
            } else if (c == ';' && !quoted) {
                pieces.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        pieces.add(current.toString());
        return pieces;
    }

    /** {@code key=value} or {@code key="quoted value"} with the quotes and backslash escapes removed; {@code null} if not a pair. */
    private static String[] keyValue(String piece) {
        int eq = piece.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        String key = piece.substring(0, eq).trim();
        String value = piece.substring(eq + 1).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            StringBuilder unquoted = new StringBuilder();
            for (int i = 1; i < value.length() - 1; i++) {
                char c = value.charAt(i);
                unquoted.append(c == '\\' && i + 1 < value.length() - 1 ? value.charAt(++i) : c);
            }
            value = unquoted.toString();
        }
        return new String[] {key, value};
    }

    /** The first {@code --boundary} that starts the body or a line (anything before it is the preamble). */
    private static int firstDelimiter(byte[] body, byte[] dash) {
        int from = 0;
        while (true) {
            int found = indexOf(body, dash, from);
            if (found < 0 || found == 0 || (found >= 2 && body[found - 2] == '\r' && body[found - 1] == '\n')) {
                return found;
            }
            from = found + 1;
        }
    }

    private static boolean startsWith(byte[] body, int at, byte[] prefix) {
        if (at < 0 || at + prefix.length > body.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (body[at + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] body, byte[] needle, int from) {
        int last = body.length - needle.length;
        outer:
        for (int i = Math.max(from, 0); i <= last; i++) {
            if (body[i] != needle[0]) {
                continue;
            }
            for (int j = 1; j < needle.length; j++) {
                if (body[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static IllegalArgumentException malformed(String why) {
        return new IllegalArgumentException("malformed multipart body: " + why);
    }
}
