package io.github.b077as.jolt.internal;

/** Builds JavaScript source fragments from Java values. */
public final class JsLiteral {

    private JsLiteral() {}

    /**
     * Returns {@code s} as a double-quoted JavaScript string literal. Escapes everything that could end the
     * literal or the enclosing script: quotes, backslashes, control characters, and the line separators
     * U+2028/U+2029 (valid in JSON but historically not in JavaScript string literals).
     */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '<' -> sb.append("\\u003c"); // keeps "</script>" and "<!--" inert if ever embedded in HTML
                case ' ', ' ' -> sb.append(String.format("\\u%04x", (int) c));
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
