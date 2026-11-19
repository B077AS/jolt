package io.github.b077as.jolt.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcProtocolTest {

    private static final String P = RpcProtocol.PREFIX;

    @Test
    void readsCallsCancelsAndHellos() {
        RpcProtocol.Request call = RpcProtocol.parse(P + "{\"t\":\"call\",\"s\":\"abc\",\"id\":3,\"m\":\"notes.add\",\"p\":{\"text\":\"x\"}}");
        assertEquals(new RpcProtocol.Request("call", "abc", 3, "notes.add", "{\"text\":\"x\"}"), call);
        assertEquals(new RpcProtocol.Request("cancel", "abc", 3, null, null), RpcProtocol.parse(P + "{\"t\":\"cancel\",\"s\":\"abc\",\"id\":3}"));
        assertEquals(new RpcProtocol.Request("hello", "abc", 0, null, null), RpcProtocol.parse(P + "{\"t\":\"hello\",\"s\":\"abc\"}"));
        assertEquals("{}", RpcProtocol.parse(P + "{\"t\":\"call\",\"s\":\"a\",\"id\":1,\"m\":\"m\"}").params());
    }

    @Test
    void refusesAnythingMalformed() {
        for (String bad : List.of(
                "{\"t\":\"call\",\"s\":\"a\",\"id\":1,\"m\":\"m\"}",                 // no prefix
                P + "not json",
                P + "{\"t\":\"call\",\"s\":\"a\",\"id\":0,\"m\":\"m\"}",             // id 0
                P + "{\"t\":\"call\",\"s\":\"a\",\"id\":-1,\"m\":\"m\"}",
                P + "{\"t\":\"call\",\"s\":\"a\",\"id\":1.5,\"m\":\"m\"}",
                P + "{\"t\":\"call\",\"s\":\"a\",\"id\":\"1\",\"m\":\"m\"}",
                P + "{\"t\":\"call\",\"s\":\"a\",\"id\":9007199254740992,\"m\":\"m\"}",
                P + "{\"t\":\"call\",\"s\":\"a\",\"id\":1,\"m\":\"\"}",
                P + "{\"t\":\"call\",\"s\":\"a\",\"id\":1,\"m\":5}",
                P + "{\"t\":\"call\",\"s\":\"\",\"id\":1,\"m\":\"m\"}",
                P + "{\"t\":\"call\",\"id\":1,\"m\":\"m\"}",
                P + "{\"t\":\"call\",\"s\":\"" + "x".repeat(129) + "\",\"id\":1,\"m\":\"m\"}",
                P + "{\"t\":\"call\",\"s\":\"a\",\"id\":1,\"m\":\"" + "m".repeat(257) + "\"}",
                P + "{\"t\":\"other\",\"s\":\"a\"}",
                P + "[]")) {
            assertNull(RpcProtocol.parse(bad), bad);
        }
        assertEquals(9_007_199_254_740_991L, RpcProtocol.parse(P + "{\"t\":\"cancel\",\"s\":\"a\",\"id\":9007199254740991}").id());
    }

    @Test
    void peeksAtTheHeadOfAHugeCall() {
        String huge = P + "{\"t\":\"call\",\"s\":\"sess\",\"id\":12,\"m\":\"upload\",\"p\":\"" + "x".repeat(10_000) + "\"}";
        RpcProtocol.Request head = RpcProtocol.peekCall(huge);
        assertEquals("sess", head.session());
        assertEquals(12, head.id());
        assertNull(RpcProtocol.peekCall(P + "{\"t\":\"cancel\",\"s\":\"sess\",\"id\":12}"));
        assertNull(RpcProtocol.peekCall(P + "garbage,\"m\":"));
    }

    @Test
    void answersAreValidJsonWithEverythingEscaped() {
        Map<?, ?> result = (Map<?, ?>) BasicJson.parse(RpcProtocol.result("s\"1", 4, "{\"a\":[1]}"));
        assertEquals("result", result.get("t"));
        assertEquals("s\"1", result.get("s"));
        assertEquals(Map.of("a", List.of(new java.math.BigDecimal(1))), result.get("r"));

        Map<?, ?> error = (Map<?, ?>) BasicJson.parse(RpcProtocol.error("s", 4, "not_found", "No \"note\"\n</script>", null));
        Map<?, ?> e = (Map<?, ?>) error.get("e");
        assertEquals("not_found", e.get("code"));
        assertEquals("No \"note\"\n</script>", e.get("message"));
        assertTrue(e.containsKey("data"));

        Map<?, ?> event = (Map<?, ?>) BasicJson.parse(RpcProtocol.event("n ", "null"));
        assertEquals("n ", event.get("n"));
    }

    @Test
    void theDeliveryScriptCannotBreakOutOfItsStringLiteral() {
        String script = RpcProtocol.deliverScript("{\"t\":\"event\",\"n\":\"x\",\"d\":\"\\\");alert(1);//</script>\"}");
        assertTrue(script.startsWith("window.__joltRpc&&window.__joltRpc.receive(\""));
        assertTrue(script.endsWith("\");"));
        String literal = script.substring("window.__joltRpc&&window.__joltRpc.receive(".length(), script.length() - 2);
        assertEquals("{\"t\":\"event\",\"n\":\"x\",\"d\":\"\\\");alert(1);//</script>\"}", MiniJson.decodeString(literal));
        assertFalse(script.contains("</script>"));
    }

    @Test
    void thePageScriptUsesTheSamePrefix() {
        String script = RpcProtocol.script();
        assertTrue(script.contains("'\\u0001jolt-rpc:'"));
        assertTrue(script.contains("window.top !== window"));
    }
}
