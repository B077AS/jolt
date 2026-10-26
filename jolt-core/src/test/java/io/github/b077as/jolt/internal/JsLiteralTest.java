package io.github.b077as.jolt.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JsLiteralTest {

    @Test
    void plainTextIsJustQuoted() {
        assertEquals("\"hello world\"", JsLiteral.quote("hello world"));
    }

    @Test
    void escapesQuotesBackslashesAndNewlines() {
        assertEquals("\"a\\\"b\\\\c\\nd\\re\\tf\"", JsLiteral.quote("a\"b\\c\nd\re\tf"));
    }

    @Test
    void escapesLineSeparatorsAndControlCharacters() {
        assertEquals("\"\\u2028\\u2029\\u0000\\u001f\\u007f\"", JsLiteral.quote("  \u0000\u001f\u007f"));
    }

    @Test
    void neutralizesScriptEndTags() {
        assertFalse(JsLiteral.quote("</script><!--").contains("<"));
    }

    @Test
    void nonAsciiPassesThrough() {
        assertEquals("\"héllo 😀\"", JsLiteral.quote("héllo 😀"));
    }

    @Test
    void bridgeDispatchQuotesPayload() {
        assertEquals("window.jolt && window.jolt.__dispatch(\"x\\\"y\");", BridgeScript.dispatch("x\"y"));
    }
}
