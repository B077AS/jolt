package io.github.b077as.jolt.examples.hello;

import io.github.b077as.jolt.AppOptions;
import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOptions;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opens a window, loads inline HTML, and talks to it in both directions.
 *
 * <p>{@code --smoke} runs a scripted round trip with no interaction, prints {@code SMOKE OK} and exits 0, or
 * exits 1 with the reason.
 */
public final class HelloWindow {

    private static final String PAGE = """
            <!doctype html>
            <meta charset="utf-8">
            <title>Hello Jolt</title>
            <style>
              body { font: 16px system-ui, sans-serif; margin: 2rem; color: #1b1f24; }
              button { font: inherit; padding: .5rem 1rem; }
              #log { margin-top: 1rem; white-space: pre-wrap; font-family: ui-monospace, monospace; }
            </style>
            <h1>Hello from Jolt</h1>
            <p>This page runs in the system web view. Java runs in the same process.</p>
            <button id="send">Send message to Java</button>
            <div id="log"></div>
            <script>
              const log = (t) => document.getElementById('log').textContent += t + '\\n';
              window.jolt.onMessage((m) => { document.title = 'got:' + m; log('Java says: ' + m); });
              document.getElementById('send').onclick = () => window.jolt.postMessage('hello at ' + new Date().toLocaleTimeString());
              if (location.hash === '#smoke' || %s) window.jolt.postMessage('ping');
            </script>
            """;

    private HelloWindow() {}

    public static void main(String[] args) {
        boolean smoke = List.of(args).contains("--smoke");
        AtomicBoolean failed = new AtomicBoolean();

        Application app = Application.create(AppOptions.of("io.github.b077as.jolt.hello"));
        try {
            app.run(() -> {
                WebWindow window = app.createWindow(WindowOptions.defaults()
                        .withTitle("Hello Jolt").withSize(900, 600).withDevTools(true));
                window.onMessage(message -> {
                    System.out.println("[java] page said: " + message);
                    window.postMessage(smoke ? "pong" : "You said: " + message);
                    if (smoke && message.equals("ping")) {
                        // evals run in order, so this sees the page after "pong" was delivered
                        window.eval("document.title").whenComplete((title, error) -> {
                            check(failed, error == null && "\"got:pong\"".equals(title), "title after pong was " + title + ", " + error);
                            window.close();
                        });
                    }
                });
                window.loadHtml(PAGE.formatted(smoke));

                if (smoke) {
                    window.ready().thenCompose(v -> window.eval("1 + 2")).whenComplete((result, error) ->
                            check(failed, error == null && "3".equals(result), "eval(1 + 2) returned " + result + ", " + error));
                    watchdog(app, failed, 20_000);
                }
            });
        } finally {
            app.close();
        }
        if (smoke) {
            System.out.println(failed.get() ? "SMOKE FAILED" : "SMOKE OK");
            System.exit(failed.get() ? 1 : 0);
        }
    }

    private static void check(AtomicBoolean failed, boolean condition, String detail) {
        if (!condition) {
            failed.set(true);
            System.err.println("[smoke] check failed: " + detail);
        }
    }

    /** Fails the smoke test instead of hanging forever. */
    private static void watchdog(Application app, AtomicBoolean failed, long millis) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                return;
            }
            System.err.println("[smoke] timed out after " + millis + " ms");
            failed.set(true);
            app.quit();
        }, "smoke-watchdog");
        thread.setDaemon(true);
        thread.start();
    }
}
