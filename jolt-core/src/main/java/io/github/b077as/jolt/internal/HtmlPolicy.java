package io.github.b077as.jolt.internal;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Puts a Content-Security-Policy into an HTML document as a {@code <meta>} element, for a web view that is handed the
 * document as a string and so has no response headers (WebKitGTK's {@code load_html}).
 */
public final class HtmlPolicy {

    private HtmlPolicy() {}

    /**
     * {@code html} with the policy as its first element: right after the doctype if there is one (a tag before it would put
     * the page into quirks mode), otherwise at the start. The parser puts it into the head, where it covers everything that
     * follows. {@code null} policy returns the document unchanged.
     */
    public static String inject(String html, String policy) {
        String usable = policy == null ? "" : forMeta(policy);
        if (usable.isEmpty()) {
            return html;
        }
        String meta = "<meta http-equiv=\"Content-Security-Policy\" content=\""
                + usable.replace("&", "&amp;").replace("\"", "&quot;") + "\">";
        int at = afterDoctype(html);
        return html.substring(0, at) + meta + html.substring(at);
    }

    /** A {@code <meta>} policy cannot hold these directives; the web view only warns about them. */
    private static String forMeta(String policy) {
        return Arrays.stream(policy.split(";"))
                .map(String::strip)
                .filter(d -> !d.isEmpty() && !d.startsWith("frame-ancestors") && !d.startsWith("sandbox") && !d.startsWith("report-uri"))
                .collect(Collectors.joining("; "));
    }

    /** The index right after the document's doctype, or where the document's content starts if it has none. */
    private static int afterDoctype(String html) {
        int start = html.startsWith("﻿") ? 1 : 0;
        int i = start;
        while (i < html.length()) {
            if (Character.isWhitespace(html.charAt(i))) {
                i++;
            } else if (html.startsWith("<!--", i)) {
                int end = html.indexOf("-->", i + 4);
                if (end < 0) {
                    return start;
                }
                i = end + 3;
            } else if (html.regionMatches(true, i, "<!doctype", 0, 9)) {
                int end = html.indexOf('>', i);
                return end < 0 ? start : end + 1;
            } else {
                break;
            }
        }
        return start;
    }
}
