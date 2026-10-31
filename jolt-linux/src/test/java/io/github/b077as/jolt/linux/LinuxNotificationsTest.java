package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.Notification;
import io.github.b077as.jolt.contract.WebWindowContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Notifications over the session bus. With {@code JOLT_TEST_NOTIFY_MOCK=1} (set by {@code run-notify-tests.sh}) a stand-in for a
 * desktop's notification service is running and records what it is sent; without it there is no service at all.
 */
@EnabledOnOs(OS.LINUX)
class LinuxNotificationsTest {

    private static final Path LOG = Path.of(System.getenv().getOrDefault("MOCK_NOTIFY_LOG", "/tmp/notifications.jsonl"));
    private static final Path INVOKE = Path.of(System.getenv().getOrDefault("MOCK_NOTIFY_INVOKE", "/tmp/mock-invoke"));

    @Test
    void quotingProducesAValidGVariantTextLiteral() {
        assertEquals("'plain'", GtkNotifications.quote("plain"));
        assertEquals("'it\\'s a \\\\ back\\nline'", GtkNotifications.quote("it's a \\ back\nline"));
        assertEquals("'ab'", GtkNotifications.quote("a\u0000\u001bb"));
        assertEquals("'ünï ✓'", GtkNotifications.quote("ünï ✓"));
    }

    @Test
    @DisabledIfEnvironmentVariable(named = "JOLT_TEST_NOTIFY_MOCK", matches = "1")
    void withoutANotificationServiceNotifyReportsNotShown() {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        WebWindowContract.runApp(40, app -> app.notify(Notification.of("nobody home", "x")).whenComplete((shown, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete(shown);
            }
            app.quit();
        }));
        assertFalse(result.join());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JOLT_TEST_NOTIFY_MOCK", matches = "1")
    void aNotificationReachesTheServiceWithItsTextAndActions() throws IOException {
        Files.deleteIfExists(LOG);
        Files.deleteIfExists(INVOKE);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        WebWindowContract.runApp(40, app -> app.notify(Notification.of("Build done", "it's \"ready\"\nline two ü")
                .withOnClick(() -> { }).withButton("Open", () -> { }).withButton("Dismiss", () -> { }))
                .whenComplete((shown, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        result.complete(shown);
                    }
                    app.quit();
                }));
        assertTrue(result.join());
        String line = Files.readAllLines(LOG).get(0);
        assertTrue(line.contains("\"summary\": \"Build done\""), line);
        assertTrue(line.contains("\"body\": \"it's \\\"ready\\\"\\nline two \\u00fc\""), line);
        assertTrue(line.contains("\"actions\": [\"default\", \"\", \"button0\", \"Open\", \"button1\", \"Dismiss\"]"), line);
    }

    private record Outcome(String action, boolean onUiThread) {}

    /** Shows {@code notification} (built by {@code make}) and returns which action ran once the service answered with {@code key}. */
    private static Outcome clickedWith(String key, java.util.function.BiFunction<Application, CompletableFuture<Outcome>, Notification> make)
            throws IOException {
        Files.deleteIfExists(LOG);
        Files.writeString(INVOKE, key);
        CompletableFuture<Outcome> outcome = new CompletableFuture<>();
        WebWindowContract.runApp(40, app -> {
            app.notify(make.apply(app, outcome)).whenComplete((shown, error) -> {
                if (error != null || !shown) {
                    outcome.completeExceptionally(error != null ? error : new AssertionError("not shown"));
                }
            });
            outcome.whenComplete((o, e) -> app.quit());
        });
        return outcome.orTimeout(5, TimeUnit.SECONDS).join();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JOLT_TEST_NOTIFY_MOCK", matches = "1")
    void clickingTheNotificationRunsOnClickOnTheUiThread() throws IOException {
        Outcome o = clickedWith("default", (app, outcome) -> Notification.of("Click", "me")
                .withOnClick(() -> outcome.complete(new Outcome("click", app.isUiThread())))
                .withButton("Other", () -> outcome.complete(new Outcome("other", app.isUiThread()))));
        assertEquals("click", o.action());
        assertTrue(o.onUiThread());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JOLT_TEST_NOTIFY_MOCK", matches = "1")
    void pressingAButtonRunsThatButtonsAction() throws IOException {
        Outcome o = clickedWith("button1", (app, outcome) -> Notification.of("Buttons", "")
                .withOnClick(() -> outcome.complete(new Outcome("click", false)))
                .withButton("First", () -> outcome.complete(new Outcome("first", false)))
                .withButton("Second", () -> outcome.complete(new Outcome("second", app.isUiThread()))));
        assertEquals("second", o.action());
        assertTrue(o.onUiThread());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JOLT_TEST_NOTIFY_MOCK", matches = "1")
    void anActionNobodyRegisteredIsIgnored() throws IOException {
        CompletableFuture<String> wrong = new CompletableFuture<>();
        Files.writeString(INVOKE, "button7");
        WebWindowContract.runApp(40, app -> {
            app.notify(Notification.of("x", "").withButton("Only", () -> wrong.complete("ran")));
            new Thread(() -> {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
                app.quit();
            }).start();
        });
        assertFalse(wrong.isDone());
        assertEquals(List.of(), List.of());
    }
}
