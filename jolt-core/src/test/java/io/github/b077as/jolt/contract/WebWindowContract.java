package io.github.b077as.jolt.contract;

import com.sun.net.httpserver.HttpServer;
import io.github.b077as.jolt.AppOptions;
import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.BlockedEvent;
import io.github.b077as.jolt.SecurityPolicy;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowClosedException;
import io.github.b077as.jolt.WindowOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour every platform backend must provide, driven through the public API with real windows and the real
 * web view. A backend module extends this class and enables it on its own operating system. Each test runs its
 * own {@link Application} on the test thread, which therefore is the UI thread.
 */
public abstract class WebWindowContract {

    private static final Path DATA_DIR = tempDir();

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("jolt-test-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Set JOLT_TEST_TRACE=1 to print each test's name before it runs; handy when a native crash kills the JVM. */
    @BeforeEach
    void traceTestName(TestInfo info) {
        if (System.getenv("JOLT_TEST_TRACE") != null) {
            System.err.println(">>> " + info.getDisplayName());
        }
    }

    /** Runs an application; a watchdog quits it if the body never finishes, so a bug fails instead of hanging. */
    public static void runApp(long timeoutSeconds, Consumer<Application> onStart) {
        Application app = Application.create(AppOptions.of("io.github.b077as.jolt.test").withDataDirectory(DATA_DIR));
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(timeoutSeconds * 1000);
            } catch (InterruptedException e) {
                return;
            }
            timedOut.set(true);
            System.err.println("=== watchdog fired after " + timeoutSeconds + "s; thread dump ===");
            Thread.getAllStackTraces().forEach((thread, stack) -> {
                System.err.println(thread);
                for (StackTraceElement frame : stack) {
                    System.err.println("    at " + frame);
                }
            });
            app.quit();
        }, "test-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            app.run(() -> onStart.accept(app));
        } finally {
            watchdog.interrupt();
            app.close();
        }
        assertFalse(timedOut.get(), "timed out after " + timeoutSeconds + "s");
    }

    @Test
    public void pageToJavaToPageRoundTrip() {
        CompletableFuture<String> received = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            window.onMessage(message -> {
                received.complete(message);
                window.close();
            });
            window.loadHtml("<script>window.jolt.postMessage('hello from page')</script>");
        });
        assertEquals("hello from page", received.getNow(null));
    }

    @Test
    public void awkwardStringsSurviveBothDirections() {
        String awkward = "quote\" backslash\\ newline\n tab\t nul-ish\u0001 ls  ps  </script> emoji 😀 é中";
        CompletableFuture<String> echoed = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            window.onMessage(message -> {
                if (message.equals("ready")) {
                    window.postMessage(awkward);
                } else {
                    echoed.complete(message);
                    window.close();
                }
            });
            window.loadHtml("<script>"
                    + "window.jolt.onMessage(m => window.jolt.postMessage(m));"
                    + "window.jolt.postMessage('ready');"
                    + "</script>");
        });
        assertEquals(awkward, echoed.getNow(null));
    }

    @Test
    public void evalReturnsJsonEncodedValues() {
        List<String> results = new ArrayList<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            window.onMessage(message -> {
                CompletableFuture<?> all = CompletableFuture.allOf(
                        window.eval("6 * 7").thenAccept(results::add),
                        window.eval("'text'").thenAccept(results::add),
                        window.eval("({a: [1, 2]})").thenAccept(results::add),
                        window.eval("undefined").thenAccept(results::add));
                all.whenComplete((v, e) -> window.close());
            });
            window.loadHtml("<script>window.jolt.postMessage('ready')</script>");
        });
        assertEquals(List.of("42", "\"text\"", "{\"a\":[1,2]}", "null"), results);
    }

    @Test
    public void initScriptRunsBeforePageScripts() {
        CompletableFuture<String> seen = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            window.onMessage(message -> {
                seen.complete(message);
                window.close();
            });
            window.addInitScript("window.__marker = 'set-by-init';");
            window.loadHtml("<script>window.jolt.postMessage(String(window.__marker))</script>");
        });
        assertEquals("set-by-init", seen.getNow(null));
    }

    @Test
    public void evalsFromManyThreadsAllComplete() throws Exception {
        int threads = 8;
        int perThread = 25;
        CompletableFuture<List<Integer>> outcome = new CompletableFuture<>();
        runApp(60, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            window.onMessage(message -> {
                // Submit from plain threads that are not the UI thread, and block only there.
                Thread driver = new Thread(() -> {
                    try {
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        CompletableFuture<Integer>[] futures = new CompletableFuture[threads * perThread];
                        Thread[] workers = new Thread[threads];
                        for (int t = 0; t < threads; t++) {
                            int worker = t;
                            workers[t] = new Thread(() -> {
                                for (int i = 0; i < perThread; i++) {
                                    int n = worker * 1000 + i;
                                    futures[worker * perThread + i] = window.eval(n + " * 2").thenApply(Integer::parseInt);
                                }
                            });
                            workers[t].start();
                        }
                        for (Thread w : workers) {
                            w.join();
                        }
                        List<Integer> values = new ArrayList<>();
                        for (CompletableFuture<Integer> f : futures) {
                            values.add(f.get(30, TimeUnit.SECONDS));
                        }
                        outcome.complete(values);
                    } catch (Throwable t) {
                        outcome.completeExceptionally(t);
                    } finally {
                        window.close();
                    }
                }, "eval-driver");
                driver.setDaemon(true);
                driver.start();
            });
            window.loadHtml("<script>window.jolt.postMessage('ready')</script>");
        });
        List<Integer> values = outcome.get(5, TimeUnit.SECONDS);
        List<Integer> expected = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < perThread; i++) {
                expected.add((t * 1000 + i) * 2);
            }
        }
        assertEquals(expected, values);
    }

    @Test
    public void twoWindowsHaveIndependentLifetimes() {
        List<String> titles = new ArrayList<>();
        AtomicBoolean secondEvalAfterFirstClosed = new AtomicBoolean();
        runApp(45, app -> {
            WebWindow a = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            WebWindow b = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            AtomicBoolean aLoaded = new AtomicBoolean();
            AtomicBoolean bLoaded = new AtomicBoolean();
            Runnable bothLoaded = () -> {
                if (aLoaded.get() && bLoaded.get()) {
                    a.eval("document.title").thenAccept(titles::add)
                            .thenCompose(v -> b.eval("document.title")).thenAccept(titles::add)
                            .whenComplete((v, e) -> a.close());
                }
            };
            a.onMessage(m -> {
                aLoaded.set(true);
                bothLoaded.run();
            });
            b.onMessage(m -> {
                bLoaded.set(true);
                bothLoaded.run();
            });
            a.onClosed(() -> b.eval("1 + 1").whenComplete((r, e) -> {
                secondEvalAfterFirstClosed.set(e == null && "2".equals(r)); // b must still work
                b.close();
            }));
            a.loadHtml("<title>A</title><script>window.jolt.postMessage('x')</script>");
            b.loadHtml("<title>B</title><script>window.jolt.postMessage('x')</script>");
        });
        assertEquals(List.of("\"A\"", "\"B\""), titles);
        assertTrue(secondEvalAfterFirstClosed.get(), "second window should keep working after the first closed");
    }

    @Test
    public void evalAfterCloseFailsInsteadOfHanging() {
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.onClosed(() -> window.eval("1").whenComplete((r, e) -> failure.complete(e)));
            window.ready().thenRun(window::close);
        });
        assertInstanceOf(WindowClosedException.class, failure.getNow(null));
    }

    @Test
    public void closingTheLastWindowEndsTheLoopAndAllowsAnotherApplication() {
        // two applications back to back in one process: window class, COM and environment must all be reusable
        for (int i = 0; i < 2; i++) {
            AtomicBoolean ran = new AtomicBoolean();
            runApp(30, app -> {
                WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
                window.onMessage(m -> {
                    ran.set(true);
                    window.close();
                });
                window.loadHtml("<script>window.jolt.postMessage('x')</script>");
            });
            assertTrue(ran.get());
        }
    }

    // ---- edge cases and misuse ----

    @Test
    public void quitFromAnotherThreadEndsTheLoopAndClosesWindows() {
        AtomicBoolean closed = new AtomicBoolean();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.onClosed(() -> closed.set(true));
            window.onMessage(message -> {
                Thread quitter = new Thread(app::quit, "quitter");
                quitter.setDaemon(true);
                quitter.start();
            });
            window.loadHtml("<script>window.jolt.postMessage('x')</script>");
        });
        assertTrue(closed.get(), "quit() should destroy open windows");
    }

    @Test
    public void applicationCanRunOnANonMainThread() throws Exception {
        CompletableFuture<String> got = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread ui = new Thread(() -> {
            try {
                runApp(30, app -> {
                    WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
                    window.onMessage(message -> {
                        got.complete(message);
                        window.close();
                    });
                    window.loadHtml("<script>window.jolt.postMessage('from other thread')</script>");
                });
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "jolt-ui-thread");
        ui.start();
        ui.join(60_000);
        assertEquals(null, failure.get());
        assertEquals("from other thread", got.getNow(null));
    }

    @Test
    public void burstOfMessagesArrivesCompleteAndInOrder() {
        int count = 2000;
        List<String> received = new ArrayList<>();
        runApp(60, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.onMessage(message -> {
                if (message.equals("done")) {
                    window.close();
                } else {
                    received.add(message);
                }
            });
            window.loadHtml("<script>for (let i = 0; i < " + count + "; i++) window.jolt.postMessage('m' + i);"
                    + "window.jolt.postMessage('done');</script>");
        });
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            expected.add("m" + i);
        }
        assertEquals(expected, received);
    }

    @Test
    public void megabyteMessagesRoundTrip() {
        String big = "x".repeat(1 << 20) + "END";
        CompletableFuture<String> fromPage = new CompletableFuture<>();
        CompletableFuture<String> echoedLength = new CompletableFuture<>();
        runApp(60, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.onMessage(message -> {
                if (message.startsWith("big:")) {
                    fromPage.complete(message.substring(4));
                    window.postMessage(big);
                } else {
                    echoedLength.complete(message);
                    window.close();
                }
            });
            window.loadHtml("<script>"
                    + "window.jolt.onMessage(m => window.jolt.postMessage('len:' + m.length + ':' + m.slice(-3)));"
                    + "window.jolt.postMessage('big:' + 'x'.repeat(1 << 20) + 'END');"
                    + "</script>");
        });
        assertEquals(big, fromPage.getNow(null));
        assertEquals("len:" + big.length() + ":END", echoedLength.getNow(null));
    }

    @Test
    public void throwingListenerDoesNotStopOtherListenersOrLaterMessages() {
        List<String> received = new ArrayList<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.onMessage(message -> {
                throw new IllegalStateException("listener bug on purpose: " + message);
            });
            window.onMessage(message -> {
                received.add(message);
                if (message.equals("second")) {
                    window.close();
                }
            });
            window.loadHtml("<script>window.jolt.postMessage('first'); window.jolt.postMessage('second');</script>");
        });
        assertEquals(List.of("first", "second"), received);
    }

    @Test
    public void thousandsOfChainedEvalsComplete() {
        int count = 3000;
        CompletableFuture<Integer> finished = new CompletableFuture<>();
        runApp(90, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.onMessage(message -> chain(window, 0, count).whenComplete((v, e) -> {
                if (e != null) {
                    finished.completeExceptionally(e);
                } else {
                    finished.complete(v);
                }
                window.close();
            }));
            window.loadHtml("<script>window.jolt.postMessage('go')</script>");
        });
        assertEquals(count, finished.getNow(-1));
    }

    private static CompletableFuture<Integer> chain(WebWindow window, int i, int n) {
        if (i == n) {
            return CompletableFuture.completedFuture(i);
        }
        return window.eval(i + " + 1").thenCompose(result -> chain(window, i + 1, n));
    }

    @Test
    public void windowOperationsDoNotBreakTheWebView() {
        CompletableFuture<String> result = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.onMessage(message -> {
                window.setTitle("Jolt 🚀 – tést 中文");
                window.setSize(500, 400);
                window.hide();
                window.show();
                window.eval("1 + 1").whenComplete((r, e) -> {
                    result.complete(e == null ? r : "error: " + e);
                    window.close();
                });
            });
            window.loadHtml("<script>window.jolt.postMessage('go')</script>");
        });
        assertEquals("2", result.getNow(null));
    }

    @Test
    public void scriptThatThrowsFailsTheEval() {
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        CompletableFuture<String> value = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.onMessage(message -> window.eval("throw new Error('boom on purpose')").whenComplete((r, e) -> {
                value.complete(r);
                failure.complete(e);
                window.close();
            }));
            window.loadHtml("<script>window.jolt.postMessage('go')</script>");
        });
        assertEquals(null, value.getNow("<not completed>"), "a throwing script must not produce a value");
        assertInstanceOf(io.github.b077as.jolt.JoltException.class, failure.getNow(null));
    }

    @Test
    public void scriptWithSyntaxErrorFailsTheEval() {
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.onMessage(message -> window.eval("this is not ( valid javascript").whenComplete((r, e) -> {
                failure.complete(e);
                window.close();
            }));
            window.loadHtml("<script>window.jolt.postMessage('go')</script>");
        });
        assertInstanceOf(io.github.b077as.jolt.JoltException.class, failure.getNow(null));
    }

    @Test
    public void loadUrlNavigatesAndTheBridgeIsAvailable() throws Exception {
        try (TestServer server = new TestServer("<script>window.jolt.postMessage('url-ok')</script>")) {
            CompletableFuture<String> got = new CompletableFuture<>();
            runApp(30, app -> {
                WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200)
                        .withSecurity(SecurityPolicy.standard().withTrustedOrigins(server.origin)));
                window.onMessage(message -> {
                    got.complete(message);
                    window.close();
                });
                window.loadUrl(server.origin + "/");
            });
            assertEquals("url-ok", got.getNow(null));
        }
    }

    /** A tiny web server on a free local port, standing in for "some other website". */
    static final class TestServer implements AutoCloseable {
        final HttpServer server;
        final String origin;

        TestServer(String html) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            server.createContext("/", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            origin = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    public void navigationToAnUntrustedOriginIsBlockedAndReported() throws Exception {
        try (TestServer server = new TestServer("<script>window.jolt.postMessage('should-not-run')</script>")) {
            CompletableFuture<BlockedEvent> blocked = new CompletableFuture<>();
            CompletableFuture<String> location = new CompletableFuture<>();
            List<String> messages = new ArrayList<>();
            runApp(30, app -> {
                WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200)); // standard policy
                window.onMessage(messages::add);
                window.onBlocked(event -> {
                    blocked.complete(event);
                    window.eval("location.href").thenAccept(href -> {
                        location.complete(href);
                        window.close();
                    });
                });
                window.loadUrl(server.origin + "/");
            });
            assertEquals(BlockedEvent.Kind.NAVIGATION, blocked.getNow(null).kind());
            assertTrue(blocked.getNow(null).url().startsWith(server.origin), blocked.getNow(null).url());
            assertEquals("\"about:blank\"", location.getNow(null));
            assertTrue(messages.isEmpty());
        }
    }

    @Test
    public void trustCanBeGrantedWhileTheWindowIsRunning() throws Exception {
        try (TestServer server = new TestServer("<script>window.jolt.postMessage('now-trusted')</script>")) {
            CompletableFuture<String> got = new CompletableFuture<>();
            AtomicInteger blocks = new AtomicInteger();
            runApp(30, app -> {
                WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
                window.onMessage(message -> {
                    got.complete(message);
                    window.close();
                });
                window.onBlocked(event -> {
                    blocks.incrementAndGet();
                    window.setSecurity(window.security().withTrustedOrigins(server.origin));
                    // WebView2 ignores a navigation to the very URL it has just cancelled, so go to another page of the same site.
                    window.loadUrl(server.origin + "/second");
                });
                window.loadUrl(server.origin + "/");
            });
            assertEquals(1, blocks.get());
            assertEquals("now-trusted", got.getNow(null));
        }
    }

    @Test
    public void messagesFromAnUntrustedPageAreIgnoredUntilThePolicyAllowsThem() throws Exception {
        try (TestServer server = new TestServer("<script>window.jolt.postMessage('first')</script>")) {
            List<String> messages = new ArrayList<>();
            CompletableFuture<BlockedEvent> blocked = new CompletableFuture<>();
            runApp(30, app -> {
                WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200)
                        .withSecurity(SecurityPolicy.standard().withLockNavigation(false)));
                window.onMessage(message -> {
                    messages.add(message);
                    if (message.equals("second")) {
                        window.close();
                    }
                });
                window.onBlocked(event -> {
                    blocked.complete(event);
                    window.setSecurity(window.security().withRestrictMessages(false));
                    window.eval("window.jolt.postMessage('second')");
                });
                window.loadUrl(server.origin + "/");
            });
            assertEquals(BlockedEvent.Kind.MESSAGE, blocked.getNow(null).kind());
            assertEquals(List.of("second"), messages);
        }
    }

    @Test
    public void permissivePolicyLetsAnyPageThrough() throws Exception {
        try (TestServer server = new TestServer("<script>window.jolt.postMessage('open')</script>")) {
            CompletableFuture<String> got = new CompletableFuture<>();
            runApp(30, app -> {
                WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200)
                        .withSecurity(SecurityPolicy.permissive()));
                window.onMessage(message -> {
                    got.complete(message);
                    window.close();
                });
                window.loadUrl(server.origin + "/");
            });
            assertEquals("open", got.getNow(null));
        }
    }

    @Test
    public void framesInsideThePageDoNotGetTheBridgeUnlessAsked() {
        assertEquals("undefined", frameBridgeType(SecurityPolicy.standard()));
        assertEquals("object", frameBridgeType(SecurityPolicy.standard().withBridgeInSubframes(true)));
    }

    private static String frameBridgeType(SecurityPolicy policy) {
        CompletableFuture<String> got = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200).withSecurity(policy));
            window.onMessage(message -> {
                got.complete(message);
                window.close();
            });
            window.loadHtml("<script>window.addEventListener('DOMContentLoaded', () => {"
                    + "const f = document.createElement('iframe');"
                    + "f.onload = () => window.jolt.postMessage(String(typeof f.contentWindow.jolt));"
                    + "f.srcdoc = '<p>frame</p>';"
                    + "document.body.appendChild(f);"
                    + "});</script>");
        });
        return got.getNow(null);
    }

    @Test
    public void documentsLargerThanTwoMegabytesLoad() {
        // WebView2's NavigateToString rejects strings over 2 MB, so a naive loadHtml fails on Windows.
        String padding = "<!-- " + "p".repeat(3 * 1024 * 1024) + " -->";
        CompletableFuture<String> got = new CompletableFuture<>();
        runApp(60, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.onMessage(message -> {
                got.complete(message);
                window.close();
            });
            window.loadHtml("<script>window.jolt.postMessage('big-doc-ok')</script>" + padding);
        });
        assertEquals("big-doc-ok", got.getNow(null));
    }

    @Test
    public void initScriptAppliesToEveryNavigation() {
        List<String> seen = new ArrayList<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.addInitScript("window.__visits = (window.__visits || 0) + 1; window.__marker = 'init';");
            window.onMessage(message -> {
                seen.add(message);
                if (seen.size() == 1) {
                    window.loadHtml("<script>window.jolt.postMessage('page2:' + window.__marker)</script>");
                } else {
                    window.close();
                }
            });
            window.loadHtml("<script>window.jolt.postMessage('page1:' + window.__marker)</script>");
        });
        assertEquals(List.of("page1:init", "page2:init"), seen);
    }

    @Test
    public void closeIsIdempotent() {
        AtomicBoolean closedOnce = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger closedCount = new java.util.concurrent.atomic.AtomicInteger();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.onClosed(() -> {
                closedCount.incrementAndGet();
                window.close(); // closing again from inside the callback must be harmless
                window.close();
            });
            window.ready().thenRun(() -> {
                window.close();
                window.close();
                closedOnce.set(true);
            });
        });
        assertTrue(closedOnce.get());
        assertEquals(1, closedCount.get(), "onClosed must fire exactly once");
    }

    @Test
    public void createWindowOffTheUiThreadIsRejected() {
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        runApp(30, app -> {
            Thread other = new Thread(() -> {
                try {
                    app.createWindow(WindowOptions.defaults());
                    failure.complete(null);
                } catch (Throwable t) {
                    failure.complete(t);
                }
                app.quit();
            }, "wrong-thread");
            other.setDaemon(true);
            other.start();
        });
        assertInstanceOf(IllegalStateException.class, failure.getNow(null));
    }

    @Test
    public void dispatchAndQuitAfterTheLoopHasEndedAreHarmless() {
        Application app = Application.create(AppOptions.of("io.github.b077as.jolt.test").withDataDirectory(DATA_DIR));
        app.run(app::quit);
        app.dispatch(() -> { }); // queued, never run, must neither throw nor hang
        app.quit();
        assertFalse(app.isUiThread());
        app.close();
    }

    // ---- window appearance: icon, title bar, background ----

    /** A small valid PNG, drawn in the test so no resource file is needed. */
    public static byte[] samplePng() {
        try {
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = image.createGraphics();
            g.setColor(new java.awt.Color(0x3478F6));
            g.fillRoundRect(4, 4, 56, 56, 16, 16);
            g.dispose();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void appearanceOptionsAndSettersLeaveTheWindowWorking() {
        CompletableFuture<String> result = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200)
                    .withIcon(samplePng())
                    .withTitleBarScheme(io.github.b077as.jolt.ColorScheme.DARK)
                    .withBackgroundColor(0x101418));
            window.onMessage(message -> {
                window.setIcon(samplePng());
                window.setTitleBarScheme(io.github.b077as.jolt.ColorScheme.LIGHT);
                window.setTitleBarScheme(io.github.b077as.jolt.ColorScheme.DARK);
                window.setTitleBarColors(0x101418, 0xE8EAED);
                window.setBackgroundColor(0xFFFFFF);
                window.eval("1 + 1").whenComplete((r, e) -> {
                    result.complete(e == null ? r : "error: " + e);
                    window.close();
                });
            });
            window.loadHtml("<script>window.jolt.postMessage('go')</script>");
        });
        assertEquals("2", result.getNow(null));
    }

    @Test
    public void anUndecodableIconIsIgnoredWithoutBreakingTheWindow() {
        CompletableFuture<String> result = new CompletableFuture<>();
        runApp(30, app -> {
            byte[] notAnImage = "this is not a PNG".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200).withIcon(notAnImage));
            window.onMessage(message -> {
                window.setIcon(notAnImage);
                window.setIcon(new byte[0]);
                window.eval("'still alive'").whenComplete((r, e) -> {
                    result.complete(e == null ? r : "error: " + e);
                    window.close();
                });
            });
            window.loadHtml("<script>window.jolt.postMessage('go')</script>");
        });
        assertEquals("\"still alive\"", result.getNow(null));
    }

    /**
     * A small valid {@code .ico} file shaped like a real one: bitmap images for the small sizes and PNG for the large
     * ones, so both kinds of entry are exercised.
     */
    public static byte[] sampleIco() {
        try {
            int[] sizes = {16, 24, 32, 48, 64, 128, 256};
            java.util.List<byte[]> images = new java.util.ArrayList<>();
            for (int size : sizes) {
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = image.createGraphics();
                g.setColor(new java.awt.Color(0xF2381F));
                g.fillRoundRect(size / 16, size / 16, size - size / 8, size - size / 8, size / 4, size / 4);
                g.dispose();
                if (size >= 128) {
                    java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(image, "png", png);
                    images.add(png.toByteArray());
                } else { // classic bitmap entry: header (height doubled), bottom-up BGRA rows, empty AND mask
                    int maskRow = ((size + 31) / 32) * 4;
                    java.nio.ByteBuffer dib = java.nio.ByteBuffer.allocate(40 + size * size * 4 + maskRow * size).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    dib.putInt(40).putInt(size).putInt(size * 2).putShort((short) 1).putShort((short) 32).putInt(0)
                            .putInt(size * size * 4 + maskRow * size).putInt(0).putInt(0).putInt(0).putInt(0);
                    for (int y = size - 1; y >= 0; y--) {
                        for (int x = 0; x < size; x++) {
                            int argb = image.getRGB(x, y);
                            dib.put((byte) argb).put((byte) (argb >> 8)).put((byte) (argb >> 16)).put((byte) (argb >>> 24));
                        }
                    }
                    images.add(dib.array());
                }
            }
            int offset = 6 + 16 * sizes.length;
            java.nio.ByteBuffer ico = java.nio.ByteBuffer.allocate(offset + images.stream().mapToInt(b -> b.length).sum())
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            ico.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
            for (int i = 0; i < sizes.length; i++) {
                byte w = (byte) (sizes[i] >= 256 ? 0 : sizes[i]);
                ico.put(w).put(w).put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32)
                        .putInt(images.get(i).length).putInt(offset);
                offset += images.get(i).length;
            }
            for (byte[] image : images) {
                ico.put(image);
            }
            return ico.array();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void anIconFileIsAcceptedAsTheWindowIconAtCreationAndLater() {
        CompletableFuture<String> result = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200).withIcon(sampleIco()));
            window.onMessage(message -> {
                window.setIcon(sampleIco());
                window.eval("'still alive'").whenComplete((r, e) -> {
                    result.complete(e == null ? r : "error: " + e);
                    window.close();
                });
            });
            window.loadHtml("<script>window.jolt.postMessage('go')</script>");
        });
        assertEquals("\"still alive\"", result.getNow(null));
    }
}
