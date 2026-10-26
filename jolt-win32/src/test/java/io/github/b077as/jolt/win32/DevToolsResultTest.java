package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.JoltException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure parsing tests; they run on any operating system. */
class DevToolsResultTest {

    @Test
    void returnsTheValueAsRawJson() {
        assertEquals("42", DevToolsResult.valueOrThrow("{\"result\":{\"type\":\"number\",\"value\":42,\"description\":\"42\"}}"));
        assertEquals("\"text\"", DevToolsResult.valueOrThrow("{\"result\":{\"type\":\"string\",\"value\":\"text\"}}"));
        assertEquals("{\"a\":[1,2]}", DevToolsResult.valueOrThrow("{\"result\":{\"type\":\"object\",\"value\":{\"a\":[1,2]}}}"));
        assertEquals("true", DevToolsResult.valueOrThrow("{\"result\":{\"type\":\"boolean\",\"value\":true}}"));
    }

    @Test
    void nullValueAndMissingValueBothBecomeNull() {
        assertEquals("null", DevToolsResult.valueOrThrow("{\"result\":{\"type\":\"object\",\"subtype\":\"null\",\"value\":null}}"));
        assertEquals("null", DevToolsResult.valueOrThrow("{\"result\":{\"type\":\"undefined\"}}"));
        assertEquals("null", DevToolsResult.valueOrThrow("{\"result\":{\"type\":\"function\",\"className\":\"Function\",\"description\":\"function(){}\"}}"));
        assertEquals("null", DevToolsResult.valueOrThrow("{\"result\":{\"type\":\"number\",\"unserializableValue\":\"NaN\",\"description\":\"NaN\"}}"));
    }

    @Test
    void aThrownErrorFailsWithItsDescription() {
        String response = "{\"result\":{\"type\":\"object\",\"subtype\":\"error\",\"description\":\"Error: boom\\n    at <anonymous>:1:7\"},"
                + "\"exceptionDetails\":{\"exceptionId\":1,\"text\":\"Uncaught\",\"lineNumber\":0,\"columnNumber\":0,"
                + "\"exception\":{\"type\":\"object\",\"subtype\":\"error\",\"description\":\"Error: boom\\n    at <anonymous>:1:7\"}}}";
        JoltException e = assertThrows(JoltException.class, () -> DevToolsResult.valueOrThrow(response));
        assertTrue(e.getMessage().startsWith("Script error: Error: boom"), e.getMessage());
    }

    @Test
    void aThrownPrimitiveFailsWithItsValue() {
        String response = "{\"result\":{\"type\":\"string\",\"value\":\"oops\"},\"exceptionDetails\":{\"exceptionId\":2,"
                + "\"text\":\"Uncaught\",\"exception\":{\"type\":\"string\",\"value\":\"oops\"}}}";
        JoltException e = assertThrows(JoltException.class, () -> DevToolsResult.valueOrThrow(response));
        assertEquals("Script error: \"oops\"", e.getMessage());
    }

    @Test
    void aSyntaxErrorWithOnlyTextStillFails() {
        String response = "{\"exceptionDetails\":{\"exceptionId\":3,\"text\":\"Uncaught SyntaxError: Unexpected identifier 'is'\"}}";
        JoltException e = assertThrows(JoltException.class, () -> DevToolsResult.valueOrThrow(response));
        assertEquals("Script error: Uncaught SyntaxError: Unexpected identifier 'is'", e.getMessage());
    }

    @Test
    void garbageFailsCleanlyInsteadOfThrowingParserErrors() {
        for (String bad : new String[] {"", "not json", "[]", "{\"unexpected\":1}", "{\"result\":5}", null}) {
            assertThrows(JoltException.class, () -> DevToolsResult.valueOrThrow(bad), String.valueOf(bad));
        }
    }

    @Test
    void parametersEscapeTheScriptAsJson() {
        assertEquals("{\"expression\":\"a\\\"b\\n\\u003c/script>\",\"returnByValue\":true}",
                DevToolsResult.evaluateParams("a\"b\n</script>"));
    }
}
