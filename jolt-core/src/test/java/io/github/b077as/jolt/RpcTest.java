package io.github.b077as.jolt;

import io.github.b077as.jolt.internal.BasicJson;
import io.github.b077as.jolt.internal.MiniJson;
import io.github.b077as.jolt.internal.PageRpc;
import io.github.b077as.jolt.internal.RpcProtocol;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Java half of RPC against a fake window that records what would be run in the page. */
class RpcTest {

    private static final String APP = SecurityPolicy.APP_ORIGIN + "/index.html";

    record NewNote(String text, List<String> tags) {}

    /** A WebWindow that keeps the scripts it is asked to run and the close listeners. */
    static final class FakeWindow {
        final BlockingQueue<String> scripts = new LinkedBlockingQueue<>();
        final List<String> initScripts = new CopyOnWriteArrayList<>();
        final List<Runnable> closeListeners = new CopyOnWriteArrayList<>();
        final WebWindow window = (WebWindow) Proxy.newProxyInstance(WebWindow.class.getClassLoader(), new Class<?>[]{WebWindow.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "eval" -> {
                        scripts.add((String) args[0]);
                        yield CompletableFuture.completedFuture("null");
                    }
                    case "addInitScript" -> {
                        initScripts.add((String) args[0]);
                        yield null;
                    }
                    case "onClosed" -> {
                        closeListeners.add((Runnable) args[0]);
                        yield null;
                    }
                    case "security" -> SecurityPolicy.standard();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakeWindow";
                    default -> null;
                });

        void send(String session, long id, String method, String paramsJson) {
            PageRpc.deliver(window, RpcProtocol.PREFIX + "{\"t\":\"call\",\"s\":\"" + session + "\",\"id\":" + id + ",\"m\":"
                    + BasicJson.quote(method) + ",\"p\":" + paramsJson + "}", APP);
        }

        void cancel(String session, long id) {
            PageRpc.deliver(window, RpcProtocol.PREFIX + "{\"t\":\"cancel\",\"s\":\"" + session + "\",\"id\":" + id + "}", APP);
        }

        void hello(String session) {
            PageRpc.deliver(window, RpcProtocol.PREFIX + "{\"t\":\"hello\",\"s\":\"" + session + "\"}", APP);
        }

        /** The next protocol message the page would have received; scripts that are not deliveries (the page script) are skipped. */
        Map<?, ?> next() throws InterruptedException {
            while (true) {
                String script = scripts.poll(5, TimeUnit.SECONDS);
                assertNotNull(script, "nothing was sent to the page");
                String head = "window.__joltRpc&&window.__joltRpc.receive(";
                if (script.startsWith(head)) {
                    return (Map<?, ?>) BasicJson.parse(MiniJson.decodeString(script.substring(head.length(), script.length() - 2)));
                }
            }
        }

        void assertNothingSent(long millis) throws InterruptedException {
            String script;
            while ((script = scripts.poll(millis, TimeUnit.MILLISECONDS)) != null) {
                assertFalse(script.startsWith("window.__joltRpc&&"), "unexpected: " + script);
            }
        }

        void close() {
            closeListeners.forEach(Runnable::run);
        }
    }

    private static Map<?, ?> error(Map<?, ?> message) {
        assertEquals("error", message.get("t"), String.valueOf(message));
        return (Map<?, ?>) message.get("e");
    }

    @Test
    void aTypedCallIsAnsweredWithItsResult() throws Exception {
        FakeWindow page = new FakeWindow();
        Rpc rpc = Rpc.create().on("notes.add", NewNote.class, note -> Map.of("saved", note.text(), "tags", note.tags().size()));
        rpc.attach(page.window);
        assertEquals(1, page.initScripts.size());
        page.send("s1", 1, "notes.add", "{\"text\":\"milk\",\"tags\":[\"a\",\"b\"]}");
        Map<?, ?> answer = page.next();
        assertEquals("result", answer.get("t"));
        assertEquals("s1", answer.get("s"));
        assertEquals(BigDecimal.ONE, answer.get("id"));
        assertEquals(Map.of("saved", "milk", "tags", new BigDecimal(2)), answer.get("r"));
        page.close();
    }

    @Test
    void handlersRunOffTheCallingThreadAndSeeTheirCall() throws Exception {
        FakeWindow page = new FakeWindow();
        AtomicReference<RpcCall> seen = new AtomicReference<>();
        AtomicReference<Thread> thread = new AtomicReference<>();
        Rpc.create().on("who", call -> {
            seen.set(call);
            thread.set(Thread.currentThread());
            return null;
        }).attach(page.window);
        page.send("s", 1, "who", "{\"x\":1}");
        Map<?, ?> answer = page.next();
        assertTrue(answer.containsKey("r"));
        assertNull(answer.get("r"));
        assertEquals("who", seen.get().method());
        assertEquals("{\"x\":1}", seen.get().paramsJson());
        assertSame(page.window, seen.get().window());
        assertTrue(thread.get().isVirtual());
        assertFalse(thread.get() == Thread.currentThread());
    }

    @Test
    void anUnknownMethodAndBadParamsGetTheirCodes() throws Exception {
        FakeWindow page = new FakeWindow();
        Rpc.create().on("notes.add", NewNote.class, note -> "ok").attach(page.window);
        page.send("s", 1, "nope", "{}");
        assertEquals(RpcException.UNKNOWN_METHOD, error(page.next()).get("code"));
        page.send("s", 2, "notes.add", "{\"text\":5}");
        Map<?, ?> e = error(page.next());
        assertEquals(RpcException.BAD_PARAMS, e.get("code"));
        assertEquals("Invalid parameters for notes.add", e.get("message"));
    }

    @Test
    void anRpcExceptionReachesThePageWithCodeMessageAndData() throws Exception {
        FakeWindow page = new FakeWindow();
        Rpc.create().on("get", call -> {
            throw new RpcException("not_found", "No note 7", Map.of("id", 7));
        }).attach(page.window);
        page.send("s", 1, "get", "{}");
        Map<?, ?> e = error(page.next());
        assertEquals("not_found", e.get("code"));
        assertEquals("No note 7", e.get("message"));
        assertEquals(Map.of("id", new BigDecimal(7)), e.get("data"));
    }

    @Test
    void anUnexpectedExceptionIsHiddenFromThePageButReported() throws Exception {
        FakeWindow page = new FakeWindow();
        CompletableFuture<Throwable> reported = new CompletableFuture<>();
        Rpc.create().on("boom", call -> {
            throw new IllegalStateException("password=hunter2 at C:\\secret");
        }).onError((call, error) -> reported.complete(error)).attach(page.window);
        page.send("s", 1, "boom", "{}");
        Map<?, ?> e = error(page.next());
        assertEquals(RpcException.INTERNAL, e.get("code"));
        assertEquals("The call to boom failed", e.get("message"));
        assertTrue(reported.get(5, TimeUnit.SECONDS) instanceof IllegalStateException);
    }

    @Test
    void errorMessagesCanBeExposedWhileDeveloping() throws Exception {
        FakeWindow page = new FakeWindow();
        Rpc.create(RpcOptions.defaults().withExposeErrorMessages(true)).on("boom", call -> {
            throw new IllegalStateException("details");
        }).on("typed", NewNote.class, n -> n).attach(page.window);
        page.send("s", 1, "boom", "{}");
        assertEquals("IllegalStateException: details", error(page.next()).get("message"));
        page.send("s", 2, "typed", "[]");
        assertTrue(((String) error(page.next()).get("message")).contains("expected an object"));
    }

    @Test
    void aLinkageErrorIsAnsweredInsteadOfLeavingThePageWaiting() throws Exception {
        FakeWindow page = new FakeWindow();
        Rpc.create().on("native", call -> {
            throw new UnsatisfiedLinkError("no libfoo");
        }).attach(page.window);
        page.send("s", 1, "native", "{}");
        assertEquals(RpcException.INTERNAL, error(page.next()).get("code"));
    }

    @Test
    void aCompletionStageIsWaitedFor() throws Exception {
        FakeWindow page = new FakeWindow();
        CompletableFuture<String> later = new CompletableFuture<>();
        CompletableFuture<String> failing = new CompletableFuture<>();
        Rpc.create().on("later", call -> later).on("failing", call -> failing).attach(page.window);
        page.send("s", 1, "later", "{}");
        page.send("s", 2, "failing", "{}");
        page.assertNothingSent(100);
        later.complete("done");
        assertEquals("done", page.next().get("r"));
        failing.completeExceptionally(new RpcException("nope", "async refusal"));
        assertEquals("nope", error(page.next()).get("code"));
    }

    @Test
    void progressArrivesBeforeTheResult() throws Exception {
        FakeWindow page = new FakeWindow();
        Rpc.create().on("work", call -> {
            for (int i = 1; i <= 3; i++) {
                call.progress(i);
            }
            return "finished";
        }).attach(page.window);
        page.send("s", 1, "work", "{}");
        for (int i = 1; i <= 3; i++) {
            Map<?, ?> progress = page.next();
            assertEquals("progress", progress.get("t"));
            assertEquals(new BigDecimal(i), progress.get("d"));
        }
        assertEquals("finished", page.next().get("r"));
    }

    @Test
    void cancellingInterruptsTheHandlerAndNothingIsAnswered() throws Exception {
        FakeWindow page = new FakeWindow();
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
        AtomicReference<RpcCall> call = new AtomicReference<>();
        Rpc.create().on("slow", c -> {
            call.set(c);
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.complete(true);
                throw e;
            }
            return "too late";
        }).attach(page.window);
        page.send("s", 1, "slow", "{}");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        page.cancel("s", 1);
        assertTrue(interrupted.get(5, TimeUnit.SECONDS));
        assertTrue(call.get().isCancelled());
        assertThrows(java.util.concurrent.CancellationException.class, call.get()::checkCancelled);
        page.assertNothingSent(200);
    }

    @Test
    void aNewDocumentCancelsTheCallsOfThePreviousOne() throws Exception {
        FakeWindow page = new FakeWindow();
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
        Rpc.create().on("slow", c -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.complete(true);
            }
            return "stale";
        }).on("fast", c -> "fresh").attach(page.window);
        page.send("old", 1, "slow", "{}");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        page.hello("new");
        assertTrue(interrupted.get(5, TimeUnit.SECONDS));
        page.send("new", 1, "fast", "{}");
        Map<?, ?> answer = page.next();
        assertEquals("new", answer.get("s"));
        assertEquals("fresh", answer.get("r"));
        page.assertNothingSent(200);
    }

    @Test
    void aWindowCannotRunMoreThanTheLimitAtOnce() throws Exception {
        FakeWindow page = new FakeWindow();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        Rpc.create(RpcOptions.defaults().withMaxConcurrentCalls(2)).on("hold", c -> {
            started.countDown();
            release.await();
            return "ok";
        }).attach(page.window);
        page.send("s", 1, "hold", "{}");
        page.send("s", 2, "hold", "{}");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        page.send("s", 3, "hold", "{}");
        Map<?, ?> refused = page.next();
        assertEquals(new BigDecimal(3), refused.get("id"));
        assertEquals(RpcException.TOO_MANY_CALLS, error(refused).get("code"));
        release.countDown();
        assertEquals("ok", page.next().get("r"));
        assertEquals("ok", page.next().get("r"));
        page.send("s", 4, "hold", "{}");
        assertEquals("ok", page.next().get("r"));
    }

    @Test
    void aCancelledCallWhoseHandlerIgnoresTheInterruptStillCountsAgainstTheLimit() throws Exception {
        FakeWindow page = new FakeWindow();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        Rpc.create(RpcOptions.defaults().withMaxConcurrentCalls(1)).on("stubborn", c -> {
            started.countDown();
            while (true) {
                try {
                    release.await();
                    return "ok";
                } catch (InterruptedException ignored) {
                    // a handler that does not stop when asked
                }
            }
        }).attach(page.window);
        page.send("s", 1, "stubborn", "{}");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        page.cancel("s", 1);
        page.send("s", 2, "stubborn", "{}");
        assertEquals(RpcException.TOO_MANY_CALLS, error(page.next()).get("code"));
        release.countDown();
        page.assertNothingSent(200); // call 1 was cancelled: its late result is dropped
        page.send("s", 3, "stubborn", "{}");
        assertEquals("ok", page.next().get("r"));
    }

    @Test
    void anOversizedRequestIsRefusedWithoutRunningTheHandler() throws Exception {
        FakeWindow page = new FakeWindow();
        List<String> ran = new CopyOnWriteArrayList<>();
        Rpc.create(RpcOptions.defaults().withMaxMessageChars(1000)).on("upload", c -> {
            ran.add(c.method());
            return "ok";
        }).attach(page.window);
        page.send("s", 1, "upload", BasicJson.quote("x".repeat(2000)));
        Map<?, ?> refused = page.next();
        assertEquals(new BigDecimal(1), refused.get("id"));
        assertEquals(RpcException.TOO_LARGE, error(refused).get("code"));
        page.send("s", 2, "upload", BasicJson.quote("x".repeat(100)));
        assertEquals("ok", page.next().get("r"));
        assertEquals(1, ran.size());
    }

    @Test
    void untrustedPagesAndMalformedMessagesAreIgnored() throws Exception {
        FakeWindow page = new FakeWindow();
        List<String> ran = new CopyOnWriteArrayList<>();
        Rpc.create().on("m", c -> ran.add("ran")).attach(page.window);
        PageRpc.deliver(page.window, RpcProtocol.PREFIX + "{\"t\":\"call\",\"s\":\"s\",\"id\":1,\"m\":\"m\",\"p\":{}}", "https://evil.example/");
        PageRpc.deliver(page.window, RpcProtocol.PREFIX + "{\"t\":\"call\"", APP);
        PageRpc.deliver(page.window, RpcProtocol.PREFIX + "{\"t\":\"call\",\"s\":\"s\",\"id\":1,\"m\":\"m\",\"p\":{}}", null);
        page.assertNothingSent(200);
        assertTrue(ran.isEmpty());
    }

    @Test
    void eventsGoToEveryAttachedWindowOrToOne() throws Exception {
        FakeWindow a = new FakeWindow();
        FakeWindow b = new FakeWindow();
        Rpc rpc = Rpc.create().attach(a.window).attach(b.window);
        assertEquals(2, rpc.windows().size());
        rpc.emit("changed", List.of(1, 2));
        for (FakeWindow page : List.of(a, b)) {
            Map<?, ?> event = page.next();
            assertEquals("event", event.get("t"));
            assertEquals("changed", event.get("n"));
            assertEquals(List.of(BigDecimal.ONE, new BigDecimal(2)), event.get("d"));
        }
        rpc.emit(b.window, "only-b", null);
        assertEquals("only-b", b.next().get("n"));
        a.assertNothingSent(100);
        assertThrows(IllegalStateException.class, () -> rpc.emit(new FakeWindow().window, "x", null));
        assertThrows(IllegalArgumentException.class, () -> rpc.emit("x", new Object()));
        assertThrows(IllegalArgumentException.class, () -> rpc.emit("", null));
    }

    @Test
    void aWindowBelongsToOneRpcAtATime() {
        FakeWindow page = new FakeWindow();
        Rpc first = Rpc.create().attach(page.window);
        first.attach(page.window); // again: nothing happens
        assertEquals(1, page.initScripts.size());
        assertThrows(IllegalStateException.class, () -> Rpc.create().attach(page.window));
        first.detach(page.window);
        Rpc.create().attach(page.window);
    }

    @Test
    void detachingAnswersRunningAndLaterCallsWithUnavailable() throws Exception {
        FakeWindow page = new FakeWindow();
        CountDownLatch started = new CountDownLatch(1);
        Rpc rpc = Rpc.create().on("slow", c -> {
            started.countDown();
            Thread.sleep(30_000);
            return "late";
        });
        rpc.attach(page.window);
        page.send("s", 1, "slow", "{}");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        rpc.detach(page.window);
        assertEquals(RpcException.UNAVAILABLE, error(page.next()).get("code"));
        page.send("s", 2, "slow", "{}");
        Map<?, ?> later = page.next();
        assertEquals(new BigDecimal(2), later.get("id"));
        assertEquals(RpcException.UNAVAILABLE, error(later).get("code"));
        page.assertNothingSent(200);
        assertTrue(rpc.windows().isEmpty());
    }

    @Test
    void closingTheWindowCancelsItsCallsAndForgetsIt() throws Exception {
        FakeWindow page = new FakeWindow();
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
        Rpc rpc = Rpc.create().on("slow", c -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.complete(true);
            }
            return null;
        }).attach(page.window);
        page.send("s", 1, "slow", "{}");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        page.close();
        assertTrue(interrupted.get(5, TimeUnit.SECONDS));
        assertTrue(rpc.windows().isEmpty());
        Rpc.create().attach(page.window); // free again
    }

    @Test
    void aCustomCodecWritesAndReads() throws Exception {
        FakeWindow page = new FakeWindow();
        List<String> decoded = new ArrayList<>();
        JsonCodec codec = JsonCodec.of(value -> "\"custom:" + value + "\"", (json, type) -> {
            decoded.add(json + " as " + type.getTypeName());
            return "params";
        });
        Rpc.create(RpcOptions.defaults().withCodec(codec)).on("m", String.class, p -> p + "!").attach(page.window);
        page.send("s", 1, "m", "{\"a\":1}");
        assertEquals("custom:params!", page.next().get("r"));
        assertEquals(List.of("{\"a\":1} as java.lang.String"), decoded);
    }

    @Test
    void aResultTheCodecCannotWriteIsAnInternalError() throws Exception {
        FakeWindow page = new FakeWindow();
        Rpc.create().on("m", c -> new Object()).attach(page.window);
        page.send("s", 1, "m", "{}");
        assertEquals(RpcException.INTERNAL, error(page.next()).get("code"));
    }

    @Test
    void handlersCanBeReplacedAndRemoved() throws Exception {
        FakeWindow page = new FakeWindow();
        Rpc rpc = Rpc.create().on("m", c -> 1).attach(page.window);
        rpc.on("m", c -> 2);
        page.send("s", 1, "m", "{}");
        assertEquals(new BigDecimal(2), page.next().get("r"));
        rpc.off("m");
        page.send("s", 2, "m", "{}");
        assertEquals(RpcException.UNKNOWN_METHOD, error(page.next()).get("code"));
    }

    @Test
    void namesAndCodesAreValidated() {
        Rpc rpc = Rpc.create();
        assertThrows(IllegalArgumentException.class, () -> rpc.on("", c -> null));
        assertThrows(IllegalArgumentException.class, () -> rpc.on("x".repeat(257), c -> null));
        assertThrows(IllegalArgumentException.class, () -> new RpcException("has space", "m"));
        assertThrows(IllegalArgumentException.class, () -> new RpcException(null, "m"));
        assertEquals("a.b-c_9", new RpcException("a.b-c_9", "m").code());
        assertThrows(IllegalArgumentException.class, () -> RpcOptions.defaults().withMaxConcurrentCalls(0));
        assertThrows(NullPointerException.class, () -> RpcOptions.defaults().withCodec(null));
    }
}
