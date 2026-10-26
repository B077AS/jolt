package io.github.b077as.jolt.internal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Just enough JSON to take apart responses from the platform: split an object into its members while keeping each
 * value as raw JSON text, and decode a string literal. It validates only what it needs to find boundaries, and
 * throws {@link IllegalArgumentException} when the text is not shaped like a JSON object.
 */
public final class MiniJson {

    private final String text;
    private int pos;

    private MiniJson(String text) {
        this.text = text;
    }

    /**
     * The top-level members of a JSON object, in order, each value as the exact JSON text it had in the input
     * (strings stay quoted and escaped). For {@code {"a":1,"b":{"c":[2]}}} that is {@code a -> 1, b -> {"c":[2]}}.
     */
    public static Map<String, String> members(String json) {
        return new MiniJson(json).object();
    }

    /** Decodes a JSON string literal including its quotes, for example {@code "a\\n\\u00e9"}, to its value. */
    public static String decodeString(String literal) {
        MiniJson reader = new MiniJson(literal);
        reader.skipWhitespace();
        String value = reader.string();
        reader.skipWhitespace();
        if (reader.pos != literal.length()) {
            throw reader.error("trailing characters after string");
        }
        return value;
    }

    private Map<String, String> object() {
        Map<String, String> members = new LinkedHashMap<>();
        skipWhitespace();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return finish(members);
        }
        while (true) {
            skipWhitespace();
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            int start = pos;
            skipValue();
            members.put(key, text.substring(start, pos));
            skipWhitespace();
            char next = take();
            if (next == '}') {
                return finish(members);
            }
            if (next != ',') {
                throw error("expected ',' or '}' but found '" + next + "'");
            }
        }
    }

    private Map<String, String> finish(Map<String, String> members) {
        skipWhitespace();
        if (pos != text.length()) {
            throw error("trailing characters after object");
        }
        return members;
    }

    /** Advances past one JSON value of any kind. */
    private void skipValue() {
        char c = peek();
        if (c == '"') {
            string();
        } else if (c == '{' || c == '[') {
            int depth = 0;
            while (true) {
                char d = take();
                if (d == '"') {
                    pos--;
                    string();
                } else if (d == '{' || d == '[') {
                    depth++;
                } else if (d == '}' || d == ']') {
                    if (--depth == 0) {
                        return;
                    }
                }
            }
        } else {
            int start = pos;
            while (pos < text.length() && ",}] \t\r\n".indexOf(text.charAt(pos)) < 0) {
                pos++;
            }
            if (pos == start) {
                throw error("expected a value");
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = take();
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char escaped = take();
            switch (escaped) {
                case '"', '\\', '/' -> sb.append(escaped);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw error("truncated \\u escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException e) {
                        throw error("bad \\u escape");
                    }
                    pos += 4;
                }
                default -> throw error("bad escape \\" + escaped);
            }
        }
    }

    private void skipWhitespace() {
        while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= text.length()) {
            throw error("unexpected end of input");
        }
        return text.charAt(pos);
    }

    private char take() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        char actual = take();
        if (actual != c) {
            throw error("expected '" + c + "' but found '" + actual + "'");
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("malformed JSON at offset " + pos + ": " + message);
    }
}
