package io.github.b077as.jolt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code Rpc.before} and {@code Rpc.around}. */
class RpcInterceptorTest {

    private static Map<?, ?> error(Map<?, ?> message) {
        assertEquals("error", message.get("t"), String.valueOf(message));
        return (Map<?, ?>) message.get("e");
    }

    @Test
    void interceptorsRunInTheOrderTheyWereRegisteredTheFirstOutermost() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        List<String> seen = new CopyOnWriteArrayList<>();
        Rpc.create()
                .around((call, next) -> {
                    seen.add("a in");
                    Object result = next.handle(call);
                    seen.add("a out");
                    return result;
                })
                .before(call -> seen.add("b"))
                .around((call, next) -> {
                    seen.add("c in");
                    Object result = next.handle(call);
                    seen.add("c out");
                    return result;
                })
                .on("m", call -> {
                    seen.add("handler");
                    return "done";
                })
                .attach(page.window);
        page.send("s", 1, "m", "{}");
        assertEquals("done", page.next().get("r"));
        assertEquals(List.of("a in", "b", "c in", "handler", "c out", "a out"), seen);
    }

    @Test
    void aBeforeThatThrowsAnRpcExceptionAnswersWithoutRunningTheHandler() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        List<String> ran = new CopyOnWriteArrayList<>();
        Rpc.create()
                .before(call -> {
                    if (call.method().startsWith("admin.")) {
                        throw new RpcException("unauthorized", "Sign in first");
                    }
                })
                .on("admin.users", call -> {
                    ran.add("admin");
                    return "secret";
                })
                .on("notes.list", call -> "public")
                .attach(page.window);
        page.send("s", 1, "admin.users", "{}");
        Map<?, ?> refused = error(page.next());
        assertEquals("unauthorized", refused.get("code"));
        assertEquals("Sign in first", refused.get("message"));
        page.send("s", 2, "notes.list", "{}");
        assertEquals("public", page.next().get("r"));
        assertTrue(ran.isEmpty(), "the handler did not run");
    }

    @Test
    void anAroundSeesTheValueOfAStageAndCanReplaceIt() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        CompletableFuture<String> later = new CompletableFuture<>();
        List<Object> seen = new CopyOnWriteArrayList<>();
        Rpc.create()
                .around((call, next) -> {
                    Object result = next.handle(call);
                    seen.add(result);
                    return result + "!";
                })
                .on("later", call -> later)
                .attach(page.window);
        page.send("s", 1, "later", "{}");
        page.assertNothingSent(100);
        later.complete("value");
        assertEquals("value!", page.next().get("r"));
        assertEquals(List.of("value"), seen, "the interceptor got the value, not the CompletionStage");
    }

    @Test
    void anAroundSeesTheCauseOfAFailedStage() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        CompletableFuture<String> failing = new CompletableFuture<>();
        List<Class<?>> seen = new CopyOnWriteArrayList<>();
        Rpc.create()
                .around((call, next) -> {
                    try {
                        return next.handle(call);
                    } catch (Exception e) {
                        seen.add(e.getClass());
                        throw e;
                    }
                })
                .on("failing", call -> failing)
                .attach(page.window);
        page.send("s", 1, "failing", "{}");
        failing.completeExceptionally(new IllegalStateException("db is down"));
        assertEquals(RpcException.INTERNAL, error(page.next()).get("code"));
        assertEquals(List.of(IllegalStateException.class), seen);
    }

    @Test
    void whatAnInterceptorThrowsGoesThroughTheExceptionHandlers() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        Rpc.create()
                .onException(SecurityException.class, (call, e) -> new RpcException("forbidden", e.getMessage()))
                .before(call -> {
                    throw new SecurityException("not for you");
                })
                .on("m", call -> "never")
                .attach(page.window);
        page.send("s", 1, "m", "{}");
        Map<?, ?> e = error(page.next());
        assertEquals("forbidden", e.get("code"));
        assertEquals("not for you", e.get("message"));
    }

    @Test
    void anUnknownMethodNeverReachesAnInterceptor() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        List<String> seen = new CopyOnWriteArrayList<>();
        Rpc.create().before(call -> seen.add(call.method())).attach(page.window);
        page.send("s", 1, "nope", "{}");
        assertEquals(RpcException.UNKNOWN_METHOD, error(page.next()).get("code"));
        assertTrue(seen.isEmpty());
    }

    @Test
    void anInterceptorCanAnswerWithoutTheHandler() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        Rpc.create()
                .around((call, next) -> call.method().equals("cached") ? "from the cache" : next.handle(call))
                .on("cached", call -> {
                    throw new IllegalStateException("never runs");
                })
                .attach(page.window);
        page.send("s", 1, "cached", "{}");
        assertEquals("from the cache", page.next().get("r"));
    }
}
