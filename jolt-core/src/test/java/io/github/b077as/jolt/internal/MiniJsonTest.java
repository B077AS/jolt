package io.github.b077as.jolt.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniJsonTest {

    @Test
    void splitsMembersKeepingRawValueText() {
        Map<String, String> m = MiniJson.members("{\"a\":1,\"b\":{\"c\":[2,3]},\"d\":\"x\",\"e\":null,\"f\":true,\"g\":-1.5e3}");
        assertEquals(List.of("a", "b", "d", "e", "f", "g"), List.copyOf(m.keySet()));
        assertEquals("1", m.get("a"));
        assertEquals("{\"c\":[2,3]}", m.get("b"));
        assertEquals("\"x\"", m.get("d"));
        assertEquals("null", m.get("e"));
        assertEquals("true", m.get("f"));
        assertEquals("-1.5e3", m.get("g"));
    }

    @Test
    void emptyObjectHasNoMembers() {
        assertTrue(MiniJson.members("{}").isEmpty());
        assertTrue(MiniJson.members("  {  }  ").isEmpty());
    }

    @Test
    void toleratesWhitespaceEverywhere() {
        Map<String, String> m = MiniJson.members(" {\n \"a\" :\t[ 1 , 2 ] ,\r\n \"b\" : \"v\" \n} ");
        assertEquals("[ 1 , 2 ]", m.get("a"));
        assertEquals("\"v\"", m.get("b"));
    }

    @Test
    void bracesQuotesAndEscapesInsideStringsDoNotConfuseBoundaries() {
        String json = "{\"s\":\"} ] , \\\" { [ \\\\\",\"t\":[\"}\",{\"k\":\"\\\"\"}],\"u\":1}";
        Map<String, String> m = MiniJson.members(json);
        assertEquals("\"} ] , \\\" { [ \\\\\"", m.get("s"));
        assertEquals("[\"}\",{\"k\":\"\\\"\"}]", m.get("t"));
        assertEquals("1", m.get("u"));
    }

    @Test
    void deeplyNestedValuesAreSkipped() {
        String deep = "[".repeat(200) + "]".repeat(200);
        assertEquals(deep, MiniJson.members("{\"x\":" + deep + ",\"y\":0}").get("x"));
    }

    @Test
    void decodesStringEscapes() {
        assertEquals("a\"b\\c/d\b\f\n\r\t", MiniJson.decodeString("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\""));
        assertEquals("\u00e9\u4e2d\uD83D\uDE00", MiniJson.decodeString("\"\\u00e9\\u4e2d\\uD83D\\uDE00\""));
        assertEquals("plain \uD83D\uDE00", MiniJson.decodeString("\"plain \uD83D\uDE00\""));
    }

    @Test
    void decodedKeysUseEscapes() {
        assertTrue(MiniJson.members("{\"a\\nb\":1}").containsKey("a\nb"));
    }

    @Test
    void rejectsMalformedInput() {
        for (String bad : new String[] {"", "[]", "{", "{\"a\"}", "{\"a\":}", "{\"a\":1", "{\"a\":1,}", "{\"a\":1} x",
                "{\"a\":\"unterminated}", "{a:1}", "{\"a\":\"\\q\"}", "{\"a\":\"\\u12\"}"}) {
            assertThrows(IllegalArgumentException.class, () -> MiniJson.members(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> MiniJson.decodeString("\"a\" b"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.decodeString("a"));
    }

    @Test
    void parsesARealDevToolsProtocolResponse() {
        String exception = "{\"result\":{\"type\":\"object\",\"subtype\":\"error\",\"className\":\"Error\","
                + "\"description\":\"Error: boom\\n    at <anonymous>:1:7\"},\"exceptionDetails\":{\"exceptionId\":1,"
                + "\"text\":\"Uncaught\",\"exception\":{\"type\":\"object\",\"description\":\"Error: boom\\n    at <anonymous>:1:7\"}}}";
        Map<String, String> top = MiniJson.members(exception);
        Map<String, String> details = MiniJson.members(top.get("exceptionDetails"));
        Map<String, String> thrown = MiniJson.members(details.get("exception"));
        assertEquals("Error: boom\n    at <anonymous>:1:7", MiniJson.decodeString(thrown.get("description")));

        Map<String, String> ok = MiniJson.members("{\"result\":{\"type\":\"number\",\"value\":42,\"description\":\"42\"}}");
        assertEquals("42", MiniJson.members(ok.get("result")).get("value"));
    }
}
