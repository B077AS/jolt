package io.github.b077as.jolt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code Rpc.register}: the annotation-based way to register handlers. */
class RpcRegisterTest {

    record Note(String text, List<String> tags) {}

    @JoltRpc("notes")
    static class Notes {
        @JoltRpc
        String hello() {
            return "hello";
        }

        @JoltRpc("add")
        Note add(Note note) {
            return new Note(note.text().toUpperCase(), note.tags());
        }

        @JoltRpc
        int count(List<Note> notes) {
            return notes.size();
        }

        @JoltRpc
        String withCall(RpcCall call, Map<String, Object> params, WebWindow window) {
            return call.method() + " " + params.get("x") + " " + (window == call.window());
        }

        @JoltRpc
        CompletionStage<String> later() {
            return CompletableFuture.completedFuture("later");
        }

        @JoltRpc
        void nothing() {
        }

        @JoltRpc
        private String hidden() {
            return "private is fine";
        }

        @JoltRpc
        void refuse() {
            throw new RpcException("not_allowed", "No.");
        }

        @JoltRpc
        void invalid() {
            throw new IllegalArgumentException("name is required");
        }

        @JoltRpc
        void broken() {
            throw new IllegalStateException("secret detail");
        }

        String notExposed() {
            return "no";
        }
    }

    static class Errors {
        @JoltRpcExceptionHandler
        RpcException invalid(IllegalArgumentException e, RpcCall call) {
            return new RpcException("invalid", call.method() + ": " + e.getMessage());
        }
    }

    private static Map<?, ?> error(Map<?, ?> message) {
        assertEquals("error", message.get("t"), String.valueOf(message));
        return (Map<?, ?>) message.get("e");
    }

    @Test
    void annotatedMethodsAnswerThePageWithParamsCallWindowFuturesAndErrors() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        Rpc.create().register(new Notes()).attach(page.window);

        page.send("s", 1, "notes.hello", "{}");
        assertEquals("hello", page.next().get("r"));
        page.send("s", 2, "notes.add", "{\"text\":\"hi\",\"tags\":[\"a\"]}");
        assertEquals(Map.of("text", "HI", "tags", List.of("a")), page.next().get("r"));
        page.send("s", 3, "notes.count", "[{\"text\":\"a\"},{\"text\":\"b\"}]");
        assertEquals(2, ((Number) page.next().get("r")).intValue(), "a generic list parameter");
        page.send("s", 4, "notes.withCall", "{\"x\":1}");
        assertEquals("notes.withCall 1 true", page.next().get("r"));
        page.send("s", 5, "notes.later", "{}");
        assertEquals("later", page.next().get("r"));
        page.send("s", 6, "notes.nothing", "{}");
        assertEquals(null, page.next().get("r"));
        page.send("s", 7, "notes.hidden", "{}");
        assertEquals("private is fine", page.next().get("r"));

        page.send("s", 8, "notes.refuse", "{}");
        assertEquals("not_allowed", error(page.next()).get("code"));
        page.send("s", 9, "notes.broken", "{}");
        Map<?, ?> broken = error(page.next());
        assertEquals(RpcException.INTERNAL, broken.get("code"));
        assertTrue(!String.valueOf(broken.get("message")).contains("secret"), "the message stays in Java: " + broken);
        page.send("s", 10, "notes.notExposed", "{}");
        assertEquals(RpcException.UNKNOWN_METHOD, error(page.next()).get("code"));
        page.send("s", 11, "notes.add", "{\"text\":[1]}");
        assertEquals(RpcException.BAD_PARAMS, error(page.next()).get("code"));
    }

    @Test
    void exceptionHandlerMethodsApplyToTheWholeRpcIncludingLambdas() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        Rpc.create()
                .register(new Notes())
                .register(new Errors())
                .on("lambda", call -> {
                    throw new IllegalArgumentException("from a lambda");
                })
                .attach(page.window);
        page.send("s", 1, "notes.invalid", "{}");
        Map<?, ?> invalid = error(page.next());
        assertEquals("invalid", invalid.get("code"));
        assertEquals("notes.invalid: name is required", invalid.get("message"), "the handler got the call too");
        page.send("s", 2, "lambda", "{}");
        assertEquals("lambda: from a lambda", error(page.next()).get("message"));
        page.send("s", 3, "notes.broken", "{}");
        assertEquals(RpcException.INTERNAL, error(page.next()).get("code"), "no handler for this type: still hidden");
    }

    static class Base {
        @JoltRpc
        String inherited() {
            return "base";
        }

        @JoltRpc
        String overridden() {
            return "base";
        }

        @JoltRpc
        String optedOut() {
            return "base";
        }
    }

    static class Derived extends Base {
        @Override
        @JoltRpc
        String overridden() {
            return "derived";
        }

        @Override
        String optedOut() {
            return "derived without the annotation: still the base's method, calling the override";
        }
    }

    @Test
    void methodsOfSuperclassesAreRegisteredOnceAndCallTheOverride() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        Rpc.create().register(new Derived()).attach(page.window);
        page.send("s", 1, "inherited", "{}");
        assertEquals("base", page.next().get("r"));
        page.send("s", 2, "overridden", "{}");
        assertEquals("derived", page.next().get("r"));
        page.send("s", 3, "optedOut", "{}");
        assertTrue(((String) page.next().get("r")).startsWith("derived"));
    }

    static class Nothing {
        String plain() {
            return "";
        }
    }

    static class Static {
        @JoltRpc
        static String shared() {
            return "";
        }
    }

    static class Reserved {
        @JoltRpc("jolt.window.show")
        void show() {
        }
    }

    static class Twice {
        @JoltRpc("same")
        String one() {
            return "1";
        }

        @JoltRpc("same")
        String two() {
            return "2";
        }
    }

    static class TwoParams {
        @JoltRpc
        void both(Note one, Note two) {
        }
    }

    static class TwoHandlers {
        @JoltRpcExceptionHandler
        RpcException one(IllegalStateException e) {
            return new RpcException("a", "a");
        }

        @JoltRpcExceptionHandler
        RpcException two(IllegalStateException e) {
            return new RpcException("b", "b");
        }
    }

    static class WrongReturn {
        @JoltRpcExceptionHandler
        String wrong(IllegalStateException e) {
            return "";
        }
    }

    static class NoException {
        @JoltRpcExceptionHandler
        RpcException none(RpcCall call) {
            return null;
        }
    }

    static class ForRpcException {
        @JoltRpcExceptionHandler
        RpcException rpc(RpcException e) {
            return e;
        }
    }

    @Test
    void badControllersAreRefusedWithAMessageThatSaysWhy() {
        Map<Object, String> bad = new java.util.LinkedHashMap<>();
        bad.put(new Nothing(), "has no @JoltRpc");
        bad.put(new Static(), "is static");
        bad.put(new Reserved(), "reserved");
        bad.put(new Twice(), "Two @JoltRpc methods are called 'same'");
        bad.put(new TwoParams(), "more than one parameter");
        bad.put(new TwoHandlers(), "Two @JoltRpcExceptionHandler methods handle java.lang.IllegalStateException");
        bad.put(new WrongReturn(), "must return an RpcException");
        bad.put(new NoException(), "takes no exception");
        bad.put(new ForRpcException(), "handles an RpcException");
        bad.forEach((controller, expected) -> {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> Rpc.create().register(controller),
                    controller.getClass().getSimpleName());
            assertNotNull(e.getMessage());
            assertTrue(e.getMessage().contains(expected), controller.getClass().getSimpleName() + ": " + e.getMessage());
        });
    }

    @Test
    void aNameThatIsTakenIsRefusedAndNothingOfTheControllerIsRegistered() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        Rpc rpc = Rpc.create().on("notes.hello", call -> "mine").attach(page.window);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> rpc.register(new Notes()));
        assertTrue(e.getMessage().contains("already has a handler for 'notes.hello'"), e.getMessage());
        page.send("s", 1, "notes.hello", "{}");
        assertEquals("mine", page.next().get("r"));
        page.send("s", 2, "notes.add", "{}");
        assertEquals(RpcException.UNKNOWN_METHOD, error(page.next()).get("code"), "the rest of the controller was not registered either");
    }

    @Test
    void anExceptionHandlerMethodReplacesAnEarlierHandlerForTheSameType() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        Rpc.create()
                .onException(IllegalArgumentException.class, (call, e) -> new RpcException("earlier", "x"))
                .register(new Notes())
                .register(new Errors())
                .attach(page.window);
        page.send("s", 1, "notes.invalid", "{}");
        assertEquals("invalid", error(page.next()).get("code"));
    }
}
