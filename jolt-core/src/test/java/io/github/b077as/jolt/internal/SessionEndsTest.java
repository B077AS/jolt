package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.SessionEndReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class SessionEndsTest {

    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final List<String> seen = new ArrayList<>();
    private final SessionEnds ends = new SessionEnds(queue::add, () -> seen.add("quit"));

    @Test
    void listenersAreToldOnceAndOneThatThrowsDoesNotStopTheOthers() {
        ends.add(reason -> {
            throw new IllegalStateException("bug");
        });
        ends.add(reason -> seen.add(reason.name()));
        ends.fire(SessionEndReason.SIGN_OUT_OR_SHUTDOWN);
        ends.fire(SessionEndReason.TERMINATION);
        assertEquals(List.of("SIGN_OUT_OR_SHUTDOWN"), seen);
    }

    @Test
    void terminationRunsTheListenersOnTheUiThreadThenQuitsAndWaitsForTheEnd() throws Exception {
        Thread ui = new Thread(() -> { });
        ends.add(reason -> seen.add(reason.name()));
        ends.started(ui);
        try {
            Thread hook = Thread.ofVirtual().start(ends::terminating);
            Runnable task = queue.poll(5, TimeUnit.SECONDS);
            assertNotNull(task);
            task.run();
            assertEquals(List.of("TERMINATION", "quit"), seen);
            assertFalse(hook.join(java.time.Duration.ofMillis(200)), "the hook waits until the application has ended");
            ends.stopped();
            assertTrue(hook.join(java.time.Duration.ofSeconds(5)));
        } finally {
            ends.stopped();
        }
    }

    @Test
    void afterTheApplicationEndedThereIsNothingToDo() {
        ends.add(reason -> seen.add(reason.name()));
        ends.started(Thread.currentThread());
        ends.stopped();
        ends.terminating();
        assertTrue(queue.isEmpty());
        assertTrue(seen.isEmpty());
    }
}
