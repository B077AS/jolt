package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.SecurityPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlPolicyTest {

    private static final String META = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'self'\">";

    private static String inject(String html) {
        return HtmlPolicy.inject(html, "default-src 'self'");
    }

    @Test
    void thePolicyComesRightAfterTheDoctype() {
        assertEquals("<!doctype html>" + META + "<html><head></head></html>", inject("<!doctype html><html><head></head></html>"));
        assertEquals("﻿ \n<!-- a > b --><!DOCTYPE html>" + META + "<p>",
                inject("﻿ \n<!-- a > b --><!DOCTYPE html><p>"));
    }

    @Test
    void withoutADoctypeItComesFirst() {
        assertEquals(META + "<script>x()</script>", inject("<script>x()</script>"));
        assertEquals("﻿" + META + "<p>", inject("﻿<p>"));
        // a doctype that only appears later, inside a script, is not the document's
        assertEquals(META + "<script>'<!doctype html>'</script>", inject("<script>'<!doctype html>'</script>"));
        assertEquals(META + "<!-- never closed <script></script>", inject("<!-- never closed <script></script>"));
    }

    @Test
    void noPolicyLeavesTheDocumentAlone() {
        assertEquals("<p>", HtmlPolicy.inject("<p>", null));
    }

    @Test
    void directivesAMetaElementCannotHoldAreLeftOutAndQuotesAreEscaped() {
        String html = HtmlPolicy.inject("", SecurityPolicy.DEFAULT_HTML_CSP);
        assertFalse(html.contains("frame-ancestors"), html);
        assertTrue(html.contains("script-src 'self' 'unsafe-inline'"), html);
        assertTrue(HtmlPolicy.inject("", "img-src \"x\" & y").contains("img-src &quot;x&quot; &amp; y"));
    }
}
