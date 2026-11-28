package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.BlockedEvent;
import io.github.b077as.jolt.CloseDecision;
import io.github.b077as.jolt.CrashEvent;
import io.github.b077as.jolt.DownloadDecision;
import io.github.b077as.jolt.DownloadRequest;
import io.github.b077as.jolt.LoadFailure;
import io.github.b077as.jolt.PageLoad;
import io.github.b077as.jolt.SecurityPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowEventsTest {

    private static final long SECOND = 1_000_000_000L;
    private static final Path TARGET = Path.of("chosen.txt").toAbsolutePath();
    private static final DownloadRequest REQUEST = new DownloadRequest("https://example.com/f", "f.txt", "text/plain", 3);

    /** Stands in for the UI thread's queue: nothing runs until {@link #run}. */
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private final AtomicLong clock = new AtomicLong();
    private final SecurityGuard guard = new SecurityGuard(SecurityPolicy.standard(), queue::add);
    private final WindowEvents events = new WindowEvents(queue::add, guard, clock::get);

    private void run() {
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
    }

    @Test
    void listenersRunFromTheQueueAndOneThatThrowsDoesNotStopTheOthers() {
        List<PageLoad> loads = new ArrayList<>();
        events.onLoad(load -> {
            throw new IllegalStateException("bug");
        });
        events.onLoad(loads::add);
        events.loaded("https://example.com/", 200);
        assertTrue(loads.isEmpty(), "not inside the native event");
        run();
        assertEquals(List.of(new PageLoad("https://example.com/", 200)), loads);
    }

    @Test
    void anErrorStatusIsAFailure() {
        List<PageLoad> loads = new ArrayList<>();
        List<LoadFailure> failures = new ArrayList<>();
        events.onLoad(loads::add);
        events.onLoadFailed(failures::add);
        events.loaded("u", 399);
        events.loaded("u", 400);
        events.loaded("u", 0);
        run();
        assertEquals(2, loads.size());
        assertEquals(List.of(new LoadFailure("u", LoadFailure.Reason.HTTP_STATUS, 400, "HTTP 400")), failures);
    }

    @Test
    void crashesReloadOnlyWhenAskedAndNotMoreThanThriceAMinute() {
        List<CrashEvent> seen = new ArrayList<>();
        events.onCrash(seen::add);
        assertFalse(events.crashed(CrashEvent.Reason.PROCESS_EXITED), "off by default");
        events.setReloadOnCrash(true);
        for (int i = 0; i < 3; i++) {
            clock.addAndGet(SECOND);
            assertTrue(events.crashed(CrashEvent.Reason.PROCESS_EXITED));
        }
        assertFalse(events.crashed(CrashEvent.Reason.OUT_OF_MEMORY));
        clock.addAndGet(61 * SECOND);
        assertTrue(events.crashed(CrashEvent.Reason.OUT_OF_MEMORY));
        run();
        assertEquals(List.of(false, true, true, true, false, true), seen.stream().map(CrashEvent::reloading).toList());
    }

    @Test
    void anUnresponsivePageOrALostWebViewIsNeverReloaded() {
        events.setReloadOnCrash(true);
        assertFalse(events.crashed(CrashEvent.Reason.UNRESPONSIVE));
        assertFalse(events.crashed(CrashEvent.Reason.WEB_VIEW_LOST));
    }

    @Test
    void withoutAHandlerThePolicyDecidesAboutDownloads() {
        List<BlockedEvent> blocked = new ArrayList<>();
        guard.onBlocked(blocked::add);
        assertEquals(WindowEvents.DownloadAction.DENY, events.download(REQUEST, d -> { }));
        run();
        assertEquals(List.of(new BlockedEvent(BlockedEvent.Kind.DOWNLOAD, REQUEST.url())), blocked);
        guard.set(SecurityPolicy.standard().withBlockDownloads(false));
        assertEquals(WindowEvents.DownloadAction.WEB_VIEW_DEFAULT, events.download(REQUEST, d -> { }));
    }

    private Optional<Path> answer(java.util.function.Function<DownloadRequest, DownloadDecision> handler) {
        events.onDownload(handler);
        List<Optional<Path>> answers = new ArrayList<>();
        assertEquals(WindowEvents.DownloadAction.ASK, events.download(REQUEST, answers::add));
        assertTrue(answers.isEmpty(), "the answer comes after the native event");
        run();
        assertEquals(1, answers.size());
        return answers.getFirst();
    }

    @Test
    void theHandlerDecidesAndAnythingWrongRefuses() {
        assertEquals(Optional.of(TARGET), answer(r -> DownloadDecision.saveTo(TARGET)));
        assertEquals(Optional.empty(), answer(r -> DownloadDecision.deny()));
        assertEquals(Optional.empty(), answer(r -> null));
        assertEquals(Optional.empty(), answer(r -> {
            throw new IllegalStateException("bug");
        }));
        assertEquals(Optional.empty(), answer(r -> DownloadDecision.saveWhen(CompletableFuture.failedFuture(new RuntimeException()))));
        assertEquals(Optional.empty(), answer(r -> DownloadDecision.saveWhen(CompletableFuture.completedFuture(null))));
        assertEquals(Optional.empty(),
                answer(r -> DownloadDecision.saveWhen(CompletableFuture.completedFuture(Optional.of(Path.of("relative.txt"))))));
    }

    @Test
    void aDestinationMustBeAbsolute() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> DownloadDecision.saveTo(Path.of("x")));
    }

    @Test
    void withoutACloseHandlerTheBackendCloses() {
        assertFalse(events.interceptClose(() -> { }));
    }

    @Test
    void oneCloseQuestionAtATimeAndOnlyTrueCloses() {
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        CompletableFuture<Boolean> first = new CompletableFuture<>();
        events.onCloseRequest(() -> {
            asked.incrementAndGet();
            return CloseDecision.when(first);
        });
        assertTrue(events.interceptClose(closed::incrementAndGet));
        run();
        assertTrue(events.interceptClose(closed::incrementAndGet));
        run();
        assertEquals(1, asked.get());
        first.complete(false);
        run();
        assertEquals(0, closed.get());

        events.onCloseRequest(CloseDecision::allow);
        assertTrue(events.interceptClose(closed::incrementAndGet));
        run();
        assertEquals(1, closed.get());
    }

    @Test
    void aFailingCloseHandlerKeepsTheWindowAndCanBeAskedAgain() {
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        events.onCloseRequest(() -> {
            if (asked.incrementAndGet() == 1) {
                throw new IllegalStateException("bug");
            }
            if (asked.get() == 2) {
                return null;
            }
            return CloseDecision.when(asked.get() == 3 ? CompletableFuture.failedFuture(new RuntimeException())
                    : CompletableFuture.completedFuture(null));
        });
        for (int i = 0; i < 4; i++) {
            events.interceptClose(closed::incrementAndGet);
            run();
        }
        assertEquals(4, asked.get());
        assertEquals(0, closed.get());
    }
}
