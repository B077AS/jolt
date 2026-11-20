package io.github.b077as.jolt;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Server streaming: {@code RpcCall.stream()}, a {@code Flow.Publisher} result, and the page's demand as back pressure. */
class RpcStreamTest {

    private static Map<?, ?> error(Map<?, ?> message) {
        assertEquals("error", message.get("t"), String.valueOf(message));
        return (Map<?, ?>) message.get("e");
    }

    private static Object item(RpcTest.FakeWindow page) throws Exception {
        Map<?, ?> m = page.next();
        assertEquals("item", m.get("t"), String.valueOf(m));
        return m.get("d");
    }

    private static void end(RpcTest.FakeWindow page) throws Exception {
        assertEquals("end", page.next().get("t"));
    }

    @Test
    void aHandlerFillsAStreamAndItEndsWhenTheHandlerReturns() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        Rpc.create().on("count", call -> {
            RpcStream<Integer> out = call.stream();
            for (int i = 1; i <= 3; i++) {
                out.send(i);
            }
            return null;
        }).attach(page.window);
        page.sendStream("s", 1, "count", "{}");
        for (int i = 1; i <= 3; i++) {
            assertEquals(i, ((Number) item(page)).intValue());
        }
        end(page);
    }

    @Test
    void aPublisherResultIsForwardedItemByItem() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        try (SubmissionPublisher<String> publisher = new SubmissionPublisher<>()) {
            Rpc.create().on("watch", call -> publisher).attach(page.window);
            page.sendStream("s", 1, "watch", "{}");
            while (publisher.getNumberOfSubscribers() == 0) {
                Thread.sleep(5);
            }
            publisher.submit("a");
            publisher.submit("b");
            assertEquals("a", item(page));
            assertEquals("b", item(page));
            publisher.close();
            end(page);
        }
    }

    @Test
    void aPlainValueForAStreamIsAStreamOfOne() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        Rpc.create().on("one", call -> "only").attach(page.window);
        page.sendStream("s", 1, "one", "{}");
        assertEquals("only", item(page));
        end(page);
    }

    @Test
    void aStreamingMethodCalledWithCallIsRefused() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        Rpc.create()
                .on("filled", call -> {
                    call.stream();
                    return null;
                })
                .on("publisher", call -> new SubmissionPublisher<String>())
                .attach(page.window);
        page.send("s", 1, "filled", "{}");
        assertEquals(RpcException.STREAM_REQUIRED, error(page.next()).get("code"));
        page.send("s", 2, "publisher", "{}");
        assertEquals(RpcException.STREAM_REQUIRED, error(page.next()).get("code"));
    }

    @Test
    void anExceptionEndsTheStreamWithAnErrorAfterTheItemsAlreadySent() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        Rpc.create()
                .onException(IllegalArgumentException.class, (call, e) -> new RpcException("bad", e.getMessage()))
                .on("half", call -> {
                    RpcStream<String> out = call.stream();
                    out.send("first");
                    throw new IllegalArgumentException("then it broke");
                })
                .attach(page.window);
        page.sendStream("s", 1, "half", "{}");
        assertEquals("first", item(page));
        Map<?, ?> e = error(page.next());
        assertEquals("bad", e.get("code"));
        assertEquals("then it broke", e.get("message"));
    }

    @Test
    void aSendWaitsForTheWindowAndMoreItemsFlowAsThePageConsumes() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        AtomicInteger sent = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        Rpc.create(RpcOptions.defaults().withStreamWindow(2)).on("many", call -> {
            RpcStream<Integer> out = call.stream();
            for (int i = 0; i < 5; i++) {
                out.send(i);
                sent.incrementAndGet();
            }
            done.countDown();
            return null;
        }).attach(page.window);
        page.sendStream("s", 1, "many", "{}");
        item(page);
        item(page);
        Thread.sleep(200);
        assertEquals(2, sent.get(), "two items fit in the window; the producer waits for the page");
        page.more("s", 1, 2);
        item(page);
        item(page);
        Thread.sleep(200);
        assertEquals(4, sent.get());
        page.more("s", 1, 2);
        item(page);
        end(page);
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    @Test
    void aPageCannotGrantMoreThanTheWindow() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        AtomicInteger sent = new AtomicInteger();
        Rpc.create(RpcOptions.defaults().withStreamWindow(2)).on("many", call -> {
            RpcStream<Integer> out = call.stream();
            for (int i = 0; i < 10; i++) {
                out.send(i);
                sent.incrementAndGet();
            }
            return null;
        }).attach(page.window);
        page.sendStream("s", 1, "many", "{}");
        item(page);
        item(page);
        page.more("s", 1, 999_999);
        item(page);
        item(page);
        Thread.sleep(200);
        assertEquals(4, sent.get(), "a huge grant opens the window, not the floodgates");
    }

    @Test
    void cancellingAStreamStopsTheProducerAndFreesTheCall() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        CompletableFuture<Throwable> ended = new CompletableFuture<>();
        Rpc rpc = Rpc.create(RpcOptions.defaults().withStreamWindow(1).withMaxConcurrentCalls(1)).on("endless", call -> {
            RpcStream<Integer> out = call.stream();
            try {
                for (int i = 0; ; i++) {
                    out.send(i);
                }
            } catch (RuntimeException e) {
                ended.complete(e);
                throw e;
            }
        }).on("quick", call -> "ok").attach(page.window);
        page.sendStream("s", 1, "endless", "{}");
        item(page);
        page.cancel("s", 1);
        assertTrue(ended.get(5, TimeUnit.SECONDS) instanceof java.util.concurrent.CancellationException);
        page.assertNothingSent(200);
        page.send("s", 2, "quick", "{}");
        assertEquals("ok", page.next().get("r"), "the stream no longer counts against the limit of one call");
    }

    @Test
    void cancellingAPublisherStreamCancelsTheSubscriptionAndFreesTheCall() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        AtomicReference<Boolean> cancelled = new AtomicReference<>(false);
        Flow.Publisher<String> publisher = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });
        Rpc.create(RpcOptions.defaults().withMaxConcurrentCalls(1)).on("watch", call -> publisher).on("quick", call -> "ok").attach(page.window);
        page.sendStream("s", 1, "watch", "{}");
        Thread.sleep(100);
        page.cancel("s", 1);
        assertTrue(cancelled.get());
        page.send("s", 2, "quick", "{}");
        assertEquals("ok", page.next().get("r"));
    }

    @Test
    void aPublisherIsAskedForNoMoreThanTheWindowAtATime() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        List<Long> requested = new ArrayList<>();
        Flow.Publisher<String> publisher = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                synchronized (requested) {
                    requested.add(n);
                }
            }

            @Override
            public void cancel() {
            }
        });
        Rpc.create(RpcOptions.defaults().withStreamWindow(4)).on("watch", call -> publisher).attach(page.window);
        page.sendStream("s", 1, "watch", "{}");
        Thread.sleep(100);
        synchronized (requested) {
            assertEquals(List.of(4L), requested, "the first demand is the window");
        }
        page.more("s", 1, 100);
        Thread.sleep(100);
        synchronized (requested) {
            assertEquals(List.of(4L), requested, "nothing was delivered yet, so the window is still full");
        }
    }

    @Test
    void aStreamHandedToAnotherThreadEndsWhenThatThreadCompletesIt() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        CountDownLatch handedOver = new CountDownLatch(1);
        AtomicReference<RpcStream<String>> stream = new AtomicReference<>();
        Rpc.create().on("later", call -> {
            RpcStream<String> out = call.stream();
            stream.set(out);
            handedOver.countDown();
            return out; // the handler is done, the stream is not
        }).attach(page.window);
        page.sendStream("s", 1, "later", "{}");
        assertTrue(handedOver.await(5, TimeUnit.SECONDS));
        page.assertNothingSent(100);
        stream.get().send("from elsewhere");
        assertEquals("from elsewhere", item(page));
        stream.get().complete();
        end(page);
        assertThrows(IllegalStateException.class, () -> stream.get().send("too late"));
        stream.get().complete(); // twice is fine
    }

    @Test
    void failingAStreamFromAnotherThreadAnswersLikeAThrownException() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        AtomicReference<RpcStream<String>> stream = new AtomicReference<>();
        CountDownLatch handedOver = new CountDownLatch(1);
        Rpc.create().onException(IllegalStateException.class, (call, e) -> new RpcException("down", e.getMessage()))
                .on("later", call -> {
                    RpcStream<String> out = call.stream();
                    stream.set(out);
                    handedOver.countDown();
                    return out;
                }).attach(page.window);
        page.sendStream("s", 1, "later", "{}");
        assertTrue(handedOver.await(5, TimeUnit.SECONDS));
        stream.get().fail(new IllegalStateException("the source died"));
        Map<?, ?> e = error(page.next());
        assertEquals("down", e.get("code"));
        assertEquals("the source died", e.get("message"));
    }

    @Test
    void streamCanOnlyBeCalledOnceAndNeedsAStreamRequest() throws Exception {
        RpcTest.FakeWindow page = new RpcTest.FakeWindow();
        AtomicReference<Throwable> second = new AtomicReference<>();
        Rpc.create().on("twice", call -> {
            call.stream();
            try {
                call.stream();
            } catch (IllegalStateException e) {
                second.set(e);
            }
            return null;
        }).attach(page.window);
        page.sendStream("s", 1, "twice", "{}");
        end(page);
        assertFalse(second.get() == null, "the second call to stream() was refused");
    }
}
