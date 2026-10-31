package io.github.b077as.jolt.contract;

import com.sun.net.httpserver.HttpServer;
import io.github.b077as.jolt.AppOptions;
import io.github.b077as.jolt.AppResources;
import io.github.b077as.jolt.AppResponse;
import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.BinaryChannel;
import io.github.b077as.jolt.BlockedEvent;
import io.github.b077as.jolt.Notification;
import io.github.b077as.jolt.OpenWindowRequest;
import io.github.b077as.jolt.RequestHandler;
import io.github.b077as.jolt.ResourceOptions;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    /**
     * Shows a page that runs {@code script}. It is served as a resource, not with {@code loadHtml}: WebKitGTK blocks a WebSocket
     * to 'ws://127.0.0.1' from the 'https://jolt.localhost' document {@code loadHtml} creates (mixed content), while the
     * resource origin of each backend may open one.
     */
    private static void loadChannelPage(WebWindow window, String script) {
        byte[] page = ("<script>" + script + "</script>").getBytes(StandardCharsets.UTF_8);
        window.loadResources(path -> path.endsWith("index.html") ? page : null);
    }

    /** An Application that behaves as the real one, except that notify records the notification instead of showing it. */
    private static Application recordingNotifications(Application real, CompletableFuture<Notification> sink) {
        return (Application) java.lang.reflect.Proxy.newProxyInstance(Application.class.getClassLoader(), new Class<?>[]{Application.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("notify")) {
                        sink.complete((Notification) args[0]);
                        return CompletableFuture.completedFuture(true);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    public void aPageNotificationCarriesButtonsAndReportsClicksButtonsAndRepliesBackToThePage() {
        CompletableFuture<Notification> shown = new CompletableFuture<>();
        CompletableFuture<String> report = new CompletableFuture<>();
        runApp(40, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            window.enableNotifications(recordingNotifications(app, shown));
            window.onMessage(message -> {
                if (message.equals("shown:true")) {
                    // the user clicks the notification, presses the second button, then sends a reply
                    Notification n = shown.join();
                    n.onClick().run();
                    n.buttons().get(1).action().run();
                    n.reply().onReply().accept("it's \"fine\" ü");
                } else {
                    report.complete(message);
                    window.close();
                }
            });
            loadChannelPage(window, "const seen = [];"
                    + "const done = () => { if (seen.length === 3) jolt.postMessage(seen.join('|')); };"
                    + "jolt.notify('Build', 'finished', {"
                    + "  onclick: () => { seen.push('click'); done(); },"
                    + "  buttons: [{ label: 'Open', onclick: () => seen.push('wrong') }, { label: 'Later', onclick: () => { seen.push('later'); done(); } }],"
                    + "  reply: { label: 'Send', placeholder: 'Say', onreply: (t) => { seen.push('reply=' + t); done(); } }"
                    + "}).then(ok => jolt.postMessage('shown:' + ok), e => jolt.postMessage('error: ' + e.message));");
        });
        assertTrue(report.isDone(), "the page never reported");
        assertEquals("click|later|reply=it's \"fine\" ü", report.getNow(null));
        Notification n = shown.getNow(null);
        assertEquals("Build", n.title());
        assertEquals("finished", n.body());
        assertEquals(List.of("Open", "Later"), n.buttons().stream().map(Notification.Button::label).toList());
        assertEquals("Say", n.reply().placeholder());
    }

    @Test
    public void aBinaryChannelCarriesEveryByteValueBothWays() {
        byte[] all = new byte[256 * 1024];
        for (int i = 0; i < all.length; i++) {
            all[i] = (byte) (i * 31 + (i >> 8));
        }
        CompletableFuture<byte[]> echoed = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            BinaryChannel channel = window.openChannel("bytes");
            channel.onOpen(() -> channel.send(all));
            channel.onMessage(data -> {
                byte[] copy = new byte[data.remaining()];
                data.get(copy);
                echoed.complete(copy);
                window.close();
            });
            // the page sends back what it receives, as an ArrayBuffer
            loadChannelPage(window, "const c = jolt.channel('bytes'); c.onMessage(d => c.send(d));");
        });
        assertTrue(echoed.isDone(), "the page never answered");
        assertArrayEquals(all, echoed.getNow(null));
    }

    @Test
    public void aBinaryChannelReconnectsWhenThePageReloads() {
        AtomicInteger opened = new AtomicInteger();
        CompletableFuture<Integer> reconnected = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            BinaryChannel channel = window.openChannel("reload");
            channel.onOpen(() -> {
                if (opened.incrementAndGet() == 1) {
                    window.eval("location.reload()");
                } else {
                    reconnected.complete(opened.get());
                    window.close();
                }
            });
            loadChannelPage(window, "jolt.channel('reload');");
        });
        assertTrue(reconnected.isDone(), "the reloaded page never reconnected");
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

    /**
     * A window created hidden and shown later must render its page. requestAnimationFrame only runs while the web view is
     * really being painted, so the page itself is the witness that the web view woke up when the window appeared.
     */
    @Test
    public void aWindowCreatedHiddenRendersItsPageOnceShown() {
        CompletableFuture<String> frame = new CompletableFuture<>();
        runApp(20, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300).withVisible(false));
            window.onMessage(message -> {
                if (message.equals("loaded")) {
                    window.show();
                } else if (message.startsWith("frame")) {
                    frame.complete(message);
                    window.close();
                }
            });
            window.loadHtml("<script>window.jolt.postMessage('loaded');"
                    + "addEventListener('load', () => requestAnimationFrame(() => window.jolt.postMessage('frame ' + document.visibilityState)));</script>");
        });
        assertEquals("frame visible", frame.getNow("the page never painted after the window was shown"));
    }

    /**
     * Whether this environment honours a request to maximize. Windows always does; on Linux it takes a window manager or
     * compositor, which a bare X server in a container lacks, so the Linux test class says what its display provides.
     */
    protected boolean maximizeIsHonoured() {
        return true;
    }

    /** Polls on a helper thread (the test thread is the UI thread and has to stay free) until the condition holds or time runs out. */
    protected static CompletableFuture<Boolean> eventually(java.util.function.BooleanSupplier condition, long timeoutMillis) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Thread poller = new Thread(() -> {
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000;
            while (System.nanoTime() < deadline) {
                if (condition.getAsBoolean()) {
                    result.complete(true);
                    return;
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    return;
                }
            }
            result.complete(false);
        }, "test-poller");
        poller.setDaemon(true);
        poller.start();
        return result;
    }

    /**
     * Whether this environment also honours a request to un-maximize. A kiosk compositor (the {@code cage} of the Wayland
     * test run) keeps every window maximized, so there only maximizing can be checked.
     */
    protected boolean restoreIsHonoured() {
        return maximizeIsHonoured();
    }

    @Test
    public void aWindowCanBeMaximizedAndRestored() {
        org.junit.jupiter.api.Assumptions.assumeTrue(maximizeIsHonoured(), "no window manager here to honour maximize");
        List<String> steps = new ArrayList<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            steps.add("initially " + window.isMaximized());
            window.maximize();
            eventually(window::isMaximized, 5000).thenCompose(maximized -> {
                steps.add("after maximize " + maximized);
                if (!restoreIsHonoured()) {
                    return CompletableFuture.completedFuture(true); // nothing more can be checked here
                }
                window.restore();
                return eventually(() -> !window.isMaximized(), 5000);
            }).thenAccept(restored -> {
                if (restoreIsHonoured()) {
                    steps.add("after restore " + restored);
                }
                window.close();
            });
        });
        assertEquals(restoreIsHonoured()
                ? List.of("initially false", "after maximize true", "after restore true")
                : List.of("initially false", "after maximize true"), steps);
    }

    @Test
    public void aWindowCreatedMaximizedStartsMaximized() {
        org.junit.jupiter.api.Assumptions.assumeTrue(maximizeIsHonoured(), "no window manager here to honour maximize");
        CompletableFuture<Boolean> maximized = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300).withMaximized(true));
            eventually(window::isMaximized, 5000).thenAccept(ok -> {
                maximized.complete(ok);
                window.close();
            });
        });
        assertEquals(true, maximized.getNow(false));
    }

    @Test
    public void aMaximizedWindowCreatedHiddenSaysSoAndIsMaximizedWhenShown() {
        List<String> steps = new ArrayList<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300).withVisible(false).withMaximized(true));
            steps.add("hidden " + window.isMaximized());
            window.show();
            if (!maximizeIsHonoured()) {
                // without a window manager the request cannot take effect; what must hold is that nothing broke
                window.eval("1 + 1").whenComplete((r, e) -> {
                    steps.add("shown " + (e == null ? "ok" : "error"));
                    window.close();
                });
                return;
            }
            eventually(window::isMaximized, 5000).thenAccept(ok -> {
                steps.add("shown " + ok);
                window.close();
            });
        });
        assertEquals(maximizeIsHonoured() ? List.of("hidden true", "shown true") : List.of("hidden true", "shown ok"), steps);
    }

    /** A hidden window shown maximized must render its page, and at the maximized size, not the size it was created with. */
    @Test
    public void aWindowShownMaximizedRendersItsPageAtTheFullSize() {
        org.junit.jupiter.api.Assumptions.assumeTrue(maximizeIsHonoured(), "no window manager here to honour maximize");
        CompletableFuture<String> frame = new CompletableFuture<>();
        runApp(20, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300).withVisible(false).withMaximized(true));
            window.onMessage(message -> {
                if (message.equals("loaded")) {
                    window.show();
                } else if (message.startsWith("frame")) {
                    frame.complete(message);
                    window.close();
                }
            });
            // wait a moment after the first frame so the maximize has settled, then report the size the page really has
            window.loadHtml("<script>window.jolt.postMessage('loaded');"
                    + "addEventListener('load', () => requestAnimationFrame(() => setTimeout(() => requestAnimationFrame(() =>"
                    + " window.jolt.postMessage('frame ' + innerWidth + 'x' + innerHeight)), 600)));</script>");
        });
        String message = frame.getNow("the page never painted after the window was shown maximized");
        assertTrue(message.startsWith("frame "), message);
        int width = Integer.parseInt(message.substring("frame ".length()).split("x")[0]);
        assertTrue(width > 600, "the page should see the maximized width, not the 400 it was created with: " + message);
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

    // ---- loadResources: every test runs over the in-process transport and over the loopback fallback ----

    protected static String resourceApp(java.util.Map<String, String> files, io.github.b077as.jolt.RequestHandler handler, boolean loopback) {
        return resourceApp(files, handler, loopback, io.github.b077as.jolt.ResourceOptions.defaults());
    }

    private static String resourceApp(java.util.Map<String, String> files, io.github.b077as.jolt.RequestHandler handler, boolean loopback,
                                      io.github.b077as.jolt.ResourceOptions options) {
        CompletableFuture<String> got = new CompletableFuture<>();
        runApp(40, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.onMessage(message -> {
                got.complete(message);
                window.close();
            });
            io.github.b077as.jolt.AppResources resources =
                    path -> files.containsKey(path) ? files.get(path).getBytes(StandardCharsets.UTF_8) : null;
            if (loopback) {
                window.loadResourcesOverLoopback(resources, handler, options, 0);
            } else {
                window.loadResources(resources, handler, options);
            }
        });
        return got.getNow(null);
    }

    private static final java.util.Map<String, String> MULTI_FILE_APP = java.util.Map.of(
            "index.html", "<link rel=\"stylesheet\" href=\"css/app.css\"><div id=\"box\"></div>"
                    + "<script type=\"module\" src=\"js/main.js\"></script>",
            "css/app.css", "#box { width: 123px; height: 10px; }",
            // a module can run before the stylesheet has loaded; the load event waits for it
            "js/main.js", "import { greet } from './util.js';"
                    + "await new Promise(done => document.readyState === 'complete' ? done() : addEventListener('load', done));"
                    + "const w = getComputedStyle(document.getElementById('box')).width;"
                    + "window.jolt.postMessage(greet('multi') + ':' + w + ':' + location.origin);",
            "js/util.js", "export const greet = name => 'hi ' + name;");

    @Test
    public void loadResourcesServesAMultiFileAppWithModulesAndTheBridge() {
        String message = resourceApp(MULTI_FILE_APP, null, false);
        assertTrue(message != null && message.startsWith("hi multi:123px:"), String.valueOf(message));
        assertFalse(message.contains("127.0.0.1"), "the in-process transport must not use a socket: " + message);
    }

    @Test
    public void loadResourcesServesAMultiFileAppOverLoopback() {
        String message = resourceApp(MULTI_FILE_APP, null, true);
        assertTrue(message != null && message.startsWith("hi multi:123px:http://127.0.0.1:"), String.valueOf(message));
    }

    private static final java.util.Map<String, String> XHR_APP = java.util.Map.of(
            "index.html", "<script type=\"module\" src=\"app.js\"></script>",
            "app.js", "const viaFetch = await fetch('/save', { method: 'POST', body: 'name=Zed' }).then(async r => r.status + ':' + await r.text());"
                    + "const xhr = (token) => new Promise(res => { const x = new XMLHttpRequest(); x.open('POST', '/save');"
                    + " if (token) x.setRequestHeader('X-Jolt-Token', token); x.onloadend = () => res(x.status + ':' + x.responseText); x.send('name=Yan'); });"
                    + "const rawXhr = await xhr(null);"
                    + "const tokenXhr = await xhr(window.joltCsrfToken);"
                    + "window.jolt.postMessage([viaFetch, rawXhr, tokenXhr].join(' | '));");

    private static final io.github.b077as.jolt.RequestHandler SAVE_HANDLER = request -> request.path().equals("/save")
            ? io.github.b077as.jolt.AppResponse.html("saved " + request.formValue("name")) : null;

    @Test
    public void requestHandlerAnswersPageRequests() {
        // nothing but this window's web view can reach the in-process transport, so a plain XMLHttpRequest needs no secret
        assertEquals("200:saved Zed | 200:saved Yan | 200:saved Yan", resourceApp(XHR_APP, SAVE_HANDLER, false));
    }

    @Test
    public void requestHandlerOverLoopbackRefusesForgedRequests() {
        // over a socket Jolt adds the secret to fetch by itself; a raw XMLHttpRequest is refused until the page sends window.joltCsrfToken
        assertEquals("200:saved Zed | 403: | 200:saved Yan", resourceApp(XHR_APP, SAVE_HANDLER, true));
    }

    private static final java.util.Map<String, String> FORM_APP = java.util.Map.of(
            "index.html", "<form id=\"f\" method=\"post\" action=\"/save\"><input name=\"name\" value=\"Zed &amp; co\"></form>"
                    + "<script>document.getElementById('f').requestSubmit()</script>");

    private static final io.github.b077as.jolt.RequestHandler FORM_HANDLER = request ->
            request.path().equals("/save") && request.method().equals("POST")
                    ? io.github.b077as.jolt.AppResponse.html("<script>window.jolt.postMessage("
                            + "'saved:" + request.formValue("name").replace("'", "") + ":' + " + request.body().length + ")</script>")
                    : null;

    @Test
    public void plainHtmlFormPostReachesTheHandler() {
        assertEquals("saved:Zed & co:" + "name=Zed+%26+co".length(), resourceApp(FORM_APP, FORM_HANDLER, false));
    }

    @Test
    public void plainHtmlFormPostReachesTheHandlerOverLoopback() {
        // the secret field was added by Jolt and removed again before the handler saw the body
        assertEquals("saved:Zed & co:" + "name=Zed+%26+co".length(), resourceApp(FORM_APP, FORM_HANDLER, true));
    }

    private static final String PROTOCOL_PAGE = """
            const out = [];
            const note = (label, value) => out.push(label + '=' + value);
            const echo = await fetch('/api/echo?x=1&y=%C3%A9', { method: 'POST',
                headers: { 'Content-Type': 'application/json', 'X-Custom': 'abc' }, body: JSON.stringify({ a: 'é€𝄞' }) });
            note('echo', [echo.status, echo.headers.get('X-Reply'), echo.headers.get('Content-Type'), await echo.text()].join('|'));
            note('missing', (await fetch('/missing.js')).status);
            const bin = new Uint8Array(await (await fetch('/api/binary')).arrayBuffer());
            let sum = 0; for (const b of bin) sum = (sum * 31 + b) % 1000003;
            note('binary', bin.length + ':' + sum);
            note('big', (await (await fetch('/api/big')).arrayBuffer()).byteLength);
            const redirected = await fetch('/api/redirect');
            note('redirect', redirected.status + ':' + await redirected.text());
            note('nocontent', (await fetch('/api/nocontent', { method: 'POST' })).status);
            const head = await fetch('/index.html', { method: 'HEAD' });
            note('head', head.status + ':' + (await head.text()).length);
            const boom = await fetch('/api/throws');
            note('throws', boom.status + ':' + (await boom.text()).includes('internals'));
            note('put', (await fetch('/api/echo', { method: 'PUT', body: 'x' })).status);
            note('delete', (await fetch('/api/echo', { method: 'DELETE' })).status);
            note('type', (await fetch('/style.css')).headers.get('Content-Type'));
            window.jolt.postMessage(out.join(';'));
            """;

    private static final io.github.b077as.jolt.RequestHandler PROTOCOL_HANDLER = request -> switch (request.path()) {
        case "/api/echo" -> io.github.b077as.jolt.AppResponse.text(request.method() + ":" + request.query() + ":"
                        + request.header("x-custom") + ":" + request.bodyText().length())
                .withStatus(request.method().equals("POST") ? 201 : 200).withHeader("X-Reply", "yes");
        case "/api/binary" -> {
            byte[] bytes = new byte[300_000];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (i * 7 + i / 256);
            }
            yield new io.github.b077as.jolt.AppResponse(200, java.util.Map.of("Content-Type", "application/octet-stream"), bytes);
        }
        case "/api/big" -> new io.github.b077as.jolt.AppResponse(200, java.util.Map.of("Content-Type", "application/octet-stream"),
                new byte[3 * 1024 * 1024]);
        case "/api/redirect" -> io.github.b077as.jolt.AppResponse.redirect("/api/after");
        case "/api/after" -> io.github.b077as.jolt.AppResponse.text("after");
        case "/api/nocontent" -> io.github.b077as.jolt.AppResponse.status(204);
        case "/api/throws" -> throw new IllegalStateException("internals");
        default -> null;
    };

    private static String expectedProtocol() {
        byte[] bytes = new byte[300_000];
        long sum = 0;
        for (int i = 0; i < bytes.length; i++) {
            sum = (sum * 31 + ((byte) (i * 7 + i / 256) & 0xff)) % 1000003;
        }
        return "echo=201|yes|text/plain; charset=utf-8|POST:x=1&y=%C3%A9:abc:" + "{\"a\":\"é€𝄞\"}".length()
                + ";missing=404;binary=300000:" + sum + ";big=" + 3 * 1024 * 1024 + ";redirect=200:after;nocontent=204;head=200:0"
                + ";throws=500:false;put=200;delete=200;type=text/css; charset=utf-8";
    }

    private static final java.util.Map<String, String> PROTOCOL_APP = java.util.Map.of(
            "index.html", "<script type=\"module\" src=\"app.js\"></script>",
            "app.js", PROTOCOL_PAGE,
            "style.css", "body{}");

    @Test
    public void requestsAndResponsesKeepTheirStatusHeadersBodiesAndBytes() {
        assertEquals(expectedProtocol(), resourceApp(PROTOCOL_APP, PROTOCOL_HANDLER, false));
    }

    @Test
    public void requestsAndResponsesKeepTheirStatusHeadersBodiesAndBytesOverLoopback() {
        assertEquals(expectedProtocol(), resourceApp(PROTOCOL_APP, PROTOCOL_HANDLER, true));
    }

    private static final java.util.Map<String, String> POST_REDIRECT_GET_APP = java.util.Map.of(
            "index.html", "<form id=\"f\" method=\"post\" action=\"/save\"><input name=\"name\" value=\"Zed\"></form>"
                    + "<script>document.getElementById('f').requestSubmit()</script>");

    private static final io.github.b077as.jolt.RequestHandler POST_REDIRECT_GET_HANDLER = request -> {
        if (request.path().equals("/save") && request.method().equals("POST")) {
            return io.github.b077as.jolt.AppResponse.redirect("/done/");
        }
        if (request.path().equals("/done/")) {
            return io.github.b077as.jolt.AppResponse.html("<script>window.jolt.postMessage('done:' + location.pathname + ':'"
                    + " + new URL('page.css', location.href).pathname)</script>");
        }
        return null;
    };

    @Test
    public void aFormPostAnsweredWithARedirectEndsOnTheNewAddress() {
        // Post/Redirect/Get: the address is the redirect target, so relative URLs resolve against it
        assertEquals("done:/done/:/done/page.css", resourceApp(POST_REDIRECT_GET_APP, POST_REDIRECT_GET_HANDLER, false));
    }

    @Test
    public void aFormPostAnsweredWithARedirectEndsOnTheNewAddressOverLoopback() {
        assertEquals("done:/done/:/done/page.css", resourceApp(POST_REDIRECT_GET_APP, POST_REDIRECT_GET_HANDLER, true));
    }

    @Test
    public void aRouterAnswersPageRequestsWithParametersFiltersAndFallThrough() {
        io.github.b077as.jolt.Router router = io.github.b077as.jolt.Router.create()
                .get("/items/{id}", request -> io.github.b077as.jolt.AppResponse.text("item " + request.param("id")))
                .post("/items", request -> io.github.b077as.jolt.AppResponse.text("made " + request.formValue("name")).withStatus(201))
                .group("/admin", admin -> admin
                        .before(request -> io.github.b077as.jolt.AppResponse.status(403))
                        .get("/panel", request -> io.github.b077as.jolt.AppResponse.text("never")));
        java.util.Map<String, String> files = java.util.Map.of(
                "index.html", "<script type=\"module\" src=\"app.js\"></script>",
                "style.css", "body{}",
                "app.js", "const out = [];"
                        + "const show = async (label, request) => { const r = await request; out.push(label + '=' + r.status + ':' + (await r.text()).slice(0, 12)); };"
                        + "await show('get', fetch('/items/a%20b'));"
                        + "await show('post', fetch('/items', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'name=Zed' }));"
                        + "await show('wrong', fetch('/items/1', { method: 'DELETE' }));"
                        + "await show('filtered', fetch('/admin/panel'));"
                        + "await show('file', fetch('/style.css'));"
                        + "await show('missing', fetch('/nothing'));"
                        + "window.jolt.postMessage(out.join(';'));");
        String expected = "get=200:item a b;post=201:made Zed;wrong=405:;filtered=403:;file=200:body{};missing=404:";
        assertEquals(expected, resourceApp(files, router, false));
        assertEquals(expected, resourceApp(files, router, true));
    }

    // ---- multipart/form-data ----

    private static String describeParts(io.github.b077as.jolt.AppRequest request) {
        StringBuilder out = new StringBuilder();
        for (io.github.b077as.jolt.AppRequest.Part part : request.parts()) {
            long sum = 0;
            for (byte b : part.content()) {
                sum = (sum * 31 + (b & 0xff)) % 1000003;
            }
            out.append(out.length() == 0 ? "" : ";").append(part.name()).append(part.isFile()
                    ? "(" + part.filename() + "," + part.size() + "," + sum + "," + part.contentType() + ")" : "=" + part.text());
        }
        return out.toString();
    }

    private static final io.github.b077as.jolt.RequestHandler UPLOAD_HANDLER = request ->
            request.path().equals("/upload") && request.method().equals("POST")
                    ? io.github.b077as.jolt.AppResponse.text(describeParts(request)) : null;

    @Test
    public void aMultipartFormWithAFileReachesTheHandlerIntact() {
        assertEquals(expectedUpload(), resourceApp(UPLOAD_APP, UPLOAD_HANDLER, false));
    }

    @Test
    public void aMultipartFormWithAFileReachesTheHandlerIntactOverLoopback() {
        assertEquals(expectedUpload(), resourceApp(UPLOAD_APP, UPLOAD_HANDLER, true));
    }

    private static final java.util.Map<String, String> UPLOAD_APP = java.util.Map.of(
            "index.html", "<script type=\"module\" src=\"app.js\"></script>",
            "app.js", "const bytes = new Uint8Array(1000000);"
                    + "for (let i = 0; i < bytes.length; i++) bytes[i] = (i * 7) % 251;"
                    + "const form = new FormData();"
                    + "form.append('title', 'Zed é \"quoted\"');"
                    + "form.append('file', new Blob([bytes], { type: 'application/octet-stream' }), '../../evil name.bin');"
                    + "form.append('empty', new Blob([]), 'nothing.txt');"
                    + "const r = await fetch('/upload', { method: 'POST', body: form });"
                    + "window.jolt.postMessage(r.status + ':' + await r.text());");

    private static String expectedUpload() {
        long sum = 0;
        for (int i = 0; i < 1_000_000; i++) {
            sum = (sum * 31 + (i * 7) % 251) % 1000003;
        }
        return "200:title=Zed é \"quoted\";file(evil name.bin," + 1_000_000 + "," + sum + ",application/octet-stream);empty(nothing.txt,0,0,application/octet-stream)";
    }

    private static final java.util.Map<String, String> MULTIPART_FORM_APP = java.util.Map.of(
            "index.html", "<form id=\"f\" method=\"post\" action=\"/save\" enctype=\"multipart/form-data\">"
                    + "<input name=\"a\" value=\"1 é\"><input type=\"file\" name=\"doc\"></form>"
                    + "<script>document.getElementById('f').requestSubmit()</script>");

    private static final io.github.b077as.jolt.RequestHandler MULTIPART_FORM_HANDLER = request ->
            request.path().equals("/save") && request.method().equals("POST")
                    ? io.github.b077as.jolt.AppResponse.html("<script>window.jolt.postMessage("
                            + io.github.b077as.jolt.internal.JsLiteral.quote(describeParts(request)) + ")</script>")
                    : null;

    @Test
    public void aPlainMultipartFormPostReachesTheHandler() {
        // no file chosen: the browser still sends the part, with an empty file name
        assertEquals("a=1 é;doc(,0,0,application/octet-stream)", resourceApp(MULTIPART_FORM_APP, MULTIPART_FORM_HANDLER, false));
    }

    @Test
    public void aPlainMultipartFormPostReachesTheHandlerOverLoopback() {
        // over a socket Jolt adds the secret to the form as a field and removes it again before the handler sees the parts
        assertEquals("a=1 é;doc(,0,0,application/octet-stream)", resourceApp(MULTIPART_FORM_APP, MULTIPART_FORM_HANDLER, true));
    }

    private static final java.util.Map<String, String> BIG_UPLOAD_APP = java.util.Map.of(
            "index.html", "<script type=\"module\" src=\"app.js\"></script>",
            "app.js", "const form = new FormData();"
                    + "form.append('file', new Blob([new Uint8Array(12 * 1024 * 1024)]), 'big.bin');"
                    + "const r = await fetch('/upload', { method: 'POST', body: form });"
                    + "window.jolt.postMessage(r.status + ':' + (r.status === 200 ? await r.text() : ''));");

    @Test
    public void aBodyOverTheDefaultLimitIsRefusedWith413() {
        assertEquals("413:", resourceApp(BIG_UPLOAD_APP, UPLOAD_HANDLER, false));
    }

    @Test
    public void aBodyOverTheDefaultLimitIsRefusedWith413OverLoopback() {
        assertEquals("413:", resourceApp(BIG_UPLOAD_APP, UPLOAD_HANDLER, true));
    }

    @Test
    public void aRaisedLimitLetsALargeUploadThrough() {
        io.github.b077as.jolt.ResourceOptions roomy = io.github.b077as.jolt.ResourceOptions.defaults().withMaxRequestBytes(32 * 1024 * 1024);
        String expected = "200:file(big.bin," + 12 * 1024 * 1024 + ",0,application/octet-stream)";
        assertEquals(expected, resourceApp(BIG_UPLOAD_APP, UPLOAD_HANDLER, false, roomy));
        assertEquals(expected, resourceApp(BIG_UPLOAD_APP, UPLOAD_HANDLER, true, roomy));
    }

    // Blob bodies: WebKitGTK aborts the process on them, so the Linux backend sends bytes instead. Everything here must behave the
    // same on every platform and transport.

    private static long checksum(byte[] data) {
        long sum = 0;
        for (byte b : data) {
            sum = (sum * 31 + (b & 0xff)) % 1000003;
        }
        return sum;
    }

    private static byte[] pattern(int length, int step) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) ((i * step) % 251);
        }
        return data;
    }

    private static java.util.Map<String, String> blobApp() {
        return java.util.Map.of(
                "index.html", "<script type=\"module\" src=\"app.js\"></script>",
                "app.js", "const bytes = new Uint8Array(200000); for (let i = 0; i < bytes.length; i++) bytes[i] = (i * 13) % 251;"
                        + "const blob = new Blob([bytes], { type: 'application/x-test' });"
                        + "const out = [];"
                        + "const show = async (label, request) => { const r = await request; out.push(label + '=' + r.status + ':' + await r.text()); };"
                        + "const form = () => { const f = new FormData(); f.append('a', '1'); f.append('file', blob, 'x.bin'); return f; };"
                        + "await show('fetch', fetch('/blob', { method: 'POST', body: blob }));"
                        + "await show('fetch-form', fetch('/blob', { method: 'POST', body: form() }));"
                        + "await show('request', fetch(new Request('/blob', { method: 'POST', body: blob })));"
                        + "out.push(await new Promise(done => { const x = new XMLHttpRequest(); x.open('POST', '/blob');"
                        + " if (window.joltCsrfToken) x.setRequestHeader('X-Jolt-Token', window.joltCsrfToken);" // raw XHR needs it over loopback only
                        + " x.onloadend = () => done('xhr=' + x.status + ':' + x.responseText); x.send(form()); }));"
                        + "navigator.sendBeacon('/beacon', new Blob([bytes.slice(0, 1000)], { type: 'application/x-test' }));" // beacons are limited to 64 KB
                        + "await new Promise(done => setTimeout(done, 700));"
                        + "await show('beacon', fetch('/beacon-result'));"
                        + "window.jolt.postMessage(out.join(';'));");
    }

    private static io.github.b077as.jolt.RequestHandler blobHandler() {
        java.util.concurrent.atomic.AtomicReference<String> beacon = new java.util.concurrent.atomic.AtomicReference<>("none");
        return request -> switch (request.path()) {
            case "/blob" -> io.github.b077as.jolt.AppResponse.text(request.isMultipart() ? describeParts(request)
                    : "raw," + request.body().length + "," + checksum(request.body()) + "," + request.header("content-type"));
            case "/beacon" -> {
                beacon.set("raw," + request.body().length + "," + checksum(request.body()) + "," + request.header("content-type"));
                yield io.github.b077as.jolt.AppResponse.status(204);
            }
            case "/beacon-result" -> io.github.b077as.jolt.AppResponse.text(beacon.get());
            default -> null;
        };
    }

    /** Over loopback a beacon is refused (it cannot carry the secret header), so the page finds nothing recorded. */
    private static String expectedBlobs(boolean loopback) {
        long sum = checksum(pattern(200000, 13));
        String raw = "raw,200000," + sum + ",application/x-test";
        String file = "file(x.bin,200000," + sum + ",application/x-test)";
        String beacon = loopback ? "none" : "raw,1000," + checksum(pattern(1000, 13)) + ",application/x-test";
        return "fetch=200:" + raw + ";fetch-form=200:a=1;" + file + ";request=200:" + raw + ";xhr=200:a=1;" + file + ";beacon=200:" + beacon;
    }

    @Test
    public void blobBodiesFromFetchRequestsXhrAndBeaconsAreDelivered() {
        assertEquals(expectedBlobs(false), resourceApp(blobApp(), blobHandler(), false));
    }

    @Test
    public void blobBodiesFromFetchRequestsXhrAndBeaconsAreDeliveredOverLoopback() {
        assertEquals(expectedBlobs(true), resourceApp(blobApp(), blobHandler(), true));
    }

    private static java.util.Map<String, String> fileFormApp(String submitCall) {
        return java.util.Map.of("index.html", "<!doctype html><body><script>"
                + "const f = document.createElement('form'); f.method = 'post'; f.action = '/save'; f.enctype = 'multipart/form-data';"
                + "const text = document.createElement('input'); text.name = 'note'; text.value = 'hi'; f.append(text);"
                + "const file = document.createElement('input'); file.type = 'file'; file.name = 'doc'; f.append(file);"
                + "const bytes = new Uint8Array(5000); for (let n = 0; n < bytes.length; n++) bytes[n] = (n * 7) % 251;"
                + "const transfer = new DataTransfer(); transfer.items.add(new File([bytes], 'up.bin', { type: 'application/x-test' }));"
                + "file.files = transfer.files; document.body.append(f); f." + submitCall + ";</script>");
    }

    @Test
    public void aFormHoldingAFileIsDeliveredWhicheverWayItIsSubmitted() {
        String expected = "note=hi;doc(up.bin,5000," + checksum(pattern(5000, 7)) + ",application/x-test)";
        for (boolean loopback : new boolean[] {false, true}) {
            for (String call : new String[] {"requestSubmit()", "submit()"}) {
                assertEquals(expected, resourceApp(fileFormApp(call), MULTIPART_FORM_HANDLER, loopback), "loopback=" + loopback + " " + call);
            }
        }
    }

    @Test
    public void aFileFormAnsweredWithARedirectEndsOnTheNewAddress() {
        io.github.b077as.jolt.RequestHandler handler = request -> {
            if (request.path().equals("/save") && request.method().equals("POST")) {
                return request.part("doc").size() == 5000 ? io.github.b077as.jolt.AppResponse.redirect("/done/") : io.github.b077as.jolt.AppResponse.status(400);
            }
            if (request.path().equals("/done/")) {
                return io.github.b077as.jolt.AppResponse.html("<script>window.jolt.postMessage('done:' + location.pathname)</script>");
            }
            return null;
        };
        assertEquals("done:/done/", resourceApp(fileFormApp("requestSubmit()"), handler, false));
        assertEquals("done:/done/", resourceApp(fileFormApp("requestSubmit()"), handler, true));
    }

    @Test
    public void browserStorageSurvivesARestartOnTheInProcessOrigin() {
        java.util.Map<String, String> set = java.util.Map.of("index.html",
                "<script>localStorage.setItem('jolt-test', 'kept'); window.jolt.postMessage('set')</script>");
        java.util.Map<String, String> read = java.util.Map.of("index.html",
                "<script>window.jolt.postMessage('got:' + localStorage.getItem('jolt-test'))</script>");
        assertEquals("set", resourceApp(set, null, false));
        assertEquals("got:kept", resourceApp(read, null, false));
    }

    @Test
    public void closingTheWindowWithARequestStillRunningIsHarmless() throws Exception {
        for (boolean loopback : new boolean[] {false, true}) {
            java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
            java.util.Map<String, String> files = java.util.Map.of("index.html",
                    // closing at once could beat the web view to dispatching the request, so let it get under way first
                    "<script>fetch('/slow'); setTimeout(() => window.jolt.postMessage('go'), 250)</script>");
            assertEquals("go", resourceApp(files, request -> {
                if (request.path().equals("/slow")) {
                    try {
                        Thread.sleep(700);
                    } finally {
                        finished.countDown();
                    }
                    return io.github.b077as.jolt.AppResponse.text("late");
                }
                return null;
            }, loopback));
            assertTrue(finished.await(10, TimeUnit.SECONDS), "the handler should still finish");
            Thread.sleep(300); // let the late response try to reach the closed window
            // the process and the infrastructure must be fine afterwards
            assertEquals("hi multi", resourceApp(java.util.Map.of("index.html",
                    "<script type=\"module\" src=\"m.js\"></script>", "m.js", "window.jolt.postMessage('hi multi')"), null, loopback));
        }
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

    // ---- several windows: jolt.openWindow, jolt.broadcast, owner, focus, windows() ----

    /** Answers {@code /} with {@code mainHtml} and {@code /pop} with {@code popHtml}; anything else falls through to a 404. */
    private static RequestHandler twoPages(String mainHtml, String popHtml) {
        return request -> switch (request.path()) {
            case "/" -> AppResponse.html(mainHtml);
            case "/pop" -> AppResponse.html(popHtml);
            default -> null;
        };
    }

    /** Shows the app on {@code startPath}, in the web view or over the loopback fallback. */
    private static void showApp(WebWindow window, RequestHandler handler, boolean loopback, String startPath) {
        ResourceOptions options = ResourceOptions.defaults().withStartPath(startPath);
        AppResources noFiles = path -> null;
        if (loopback) {
            window.loadResourcesOverLoopback(noFiles, handler, options, 0);
        } else {
            window.loadResources(noFiles, handler, options);
        }
    }

    private void aPageOpensWindowsAndAsksForTheSameKeyTwice(boolean loopback) {
        CompletableFuture<String> mainReport = new CompletableFuture<>();
        CompletableFuture<String> popupReport = new CompletableFuture<>();
        AtomicInteger asked = new AtomicInteger();
        AtomicReference<OpenWindowRequest> first = new AtomicReference<>();
        AtomicReference<WebWindow> mainWindow = new AtomicReference<>();
        AtomicInteger windowsWhileOpen = new AtomicInteger();
        runApp(60, app -> {
            RequestHandler handler = twoPages("""
                    <script type="module">
                    const a = await jolt.openWindow('/pop?x=1', { key: 'k', title: 'Popup', width: 500, height: 400 });
                    const b = await jolt.openWindow('/pop?x=2', { key: 'k' });   // same key: the first window comes forward
                    const c = await jolt.openWindow('/nope');                    // the opener refuses
                    jolt.postMessage('result:' + [a, b, c].join(','));
                    </script>""", """
                    <script>jolt.postMessage('pop:' + location.pathname + location.search)</script>""");
            WebWindow main = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            mainWindow.set(main);
            main.onMessage(mainReport::complete);
            main.enableOpenWindow(app, request -> {
                asked.incrementAndGet();
                if (!request.path().startsWith("/pop")) {
                    return null;
                }
                first.compareAndSet(null, request);
                WebWindow popup = request.createWindow(app);
                popup.onMessage(popupReport::complete);
                showApp(popup, handler, loopback, request.path());
                return popup;
            });
            CompletableFuture.allOf(mainReport, popupReport).thenRun(() -> {
                windowsWhileOpen.set(app.windows().size());
                new ArrayList<>(app.windows()).forEach(WebWindow::close);
            });
            showApp(main, handler, loopback, "/");
        });
        assertEquals("result:true,true,false", mainReport.getNow(null));
        assertEquals("pop:/pop?x=1", popupReport.getNow(null), "the new window must open on the requested path, not on /");
        assertEquals(2, asked.get(), "the second request for key 'k' must not reach the opener again");
        assertEquals(2, windowsWhileOpen.get(), "one window for the main page and one pop-out, not two pop-outs");
        OpenWindowRequest request = first.get();
        assertEquals("/pop?x=1", request.path());
        assertEquals("k", request.key());
        assertEquals("Popup", request.title());
        assertEquals(500, request.width());
        assertEquals(400, request.height());
        assertTrue(request.opener() == mainWindow.get(), "the request must name the window whose page made it");
    }

    @Test
    public void aPageOpensAWindowOnAPathAndTheSameKeyBringsItForwardInsteadOfOpeningAnother() {
        aPageOpensWindowsAndAsksForTheSameKeyTwice(false);
    }

    @Test
    public void aPageOpensAWindowOnAPathAndTheSameKeyBringsItForwardInsteadOfOpeningAnotherOverLoopback() {
        aPageOpensWindowsAndAsksForTheSameKeyTwice(true);
    }

    private void windowsBroadcastToEachOtherButNotToThemselves(boolean loopback) {
        CompletableFuture<String> mainReport = new CompletableFuture<>();
        CompletableFuture<String> popupReport = new CompletableFuture<>();
        runApp(60, app -> {
            RequestHandler handler = twoPages("""
                    <script>
                    const seen = [];
                    jolt.onBroadcast(async (text) => {
                      seen.push(text);
                      if (text === 'popup-ready') await jolt.broadcast('hello popup');
                      if (text === 'popup-done') jolt.postMessage(seen.join(','));
                    });
                    jolt.openWindow('/pop');
                    </script>""", """
                    <script>
                    jolt.onBroadcast((text) => { jolt.postMessage('popup got:' + text); jolt.broadcast('popup-done'); });
                    jolt.broadcast('popup-ready');
                    </script>""");
            WebWindow main = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            main.onMessage(mainReport::complete);
            main.enableOpenWindow(app, request -> {
                WebWindow popup = request.createWindow(app);
                popup.onMessage(popupReport::complete);
                showApp(popup, handler, loopback, request.path());
                return popup;
            });
            CompletableFuture.allOf(mainReport, popupReport)
                    .thenRun(() -> new ArrayList<>(app.windows()).forEach(WebWindow::close));
            showApp(main, handler, loopback, "/");
        });
        assertEquals("popup got:hello popup", popupReport.getNow(null));
        // a window never receives its own broadcast: 'hello popup' was sent by main
        assertEquals("popup-ready,popup-done", mainReport.getNow(null));
    }

    @Test
    public void windowsBroadcastToTheOtherWindowsButNotBackToTheSender() {
        windowsBroadcastToEachOtherButNotToThemselves(false);
    }

    @Test
    public void windowsBroadcastToTheOtherWindowsButNotBackToTheSenderOverLoopback() {
        windowsBroadcastToEachOtherButNotToThemselves(true);
    }

    @Test
    public void openWindowRejectsAddressesOutsideTheAppAndRefusesWhenTheOpenerFails() {
        CompletableFuture<String> report = new CompletableFuture<>();
        AtomicInteger asked = new AtomicInteger();
        runApp(40, app -> {
            WebWindow main = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            main.onMessage(message -> {
                report.complete(message);
                main.close();
            });
            main.enableOpenWindow(app, request -> {
                asked.incrementAndGet();
                throw new IllegalStateException("the app cannot open " + request.path());
            });
            RequestHandler handler = twoPages("""
                    <script type="module">
                    const r = [];
                    for (const path of ['//evil.example/x', 'https://evil.example/', '/\\\\evil.example/x', 'relative']) {
                      try { await jolt.openWindow(path); r.push('opened'); } catch (e) { r.push('rejected'); }
                    }
                    r.push(await jolt.openWindow('/boom'));
                    jolt.postMessage(r.join(','));
                    </script>""", "");
            showApp(main, handler, false, "/");
        });
        assertEquals("rejected,rejected,rejected,rejected,false", report.getNow(null));
        assertEquals(1, asked.get(), "only the address inside the app may reach the opener");
    }

    @Test
    public void openWindowIsNotAvailableUntilTheJavaWindowEnablesIt() {
        CompletableFuture<String> report = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow main = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            main.onMessage(message -> {
                report.complete(message);
                main.close();
            });
            loadChannelPage(main, "jolt.openWindow('/pop').then(v => jolt.postMessage('opened'), e => jolt.postMessage(e.message))");
        });
        String message = report.getNow(null);
        assertTrue(message != null && message.contains("enableOpenWindow"), String.valueOf(message));
    }

    @Test
    public void anOwnedWindowIsListedFocusableAndClosedWithItsOwner() {
        AtomicInteger listed = new AtomicInteger();
        AtomicBoolean ownedClosed = new AtomicBoolean();
        runApp(40, app -> {
            WebWindow owner = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            WebWindow owned = app.createWindow(WindowOptions.defaults().withSize(300, 200).withOwner(owner));
            WebWindow independent = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            listed.set(app.windows().size());
            assertTrue(app.windows().contains(owned) && app.windows().contains(owner));
            owned.focus();
            owned.hide();
            owned.focus(); // brings a hidden window back
            // closing the owner must take the owned window with it; the independent one lives on until we close it
            owned.onClosed(() -> {
                ownedClosed.set(true);
                independent.close();
            });
            owner.ready().thenRun(() -> owner.close());
        });
        assertEquals(3, listed.get());
        assertTrue(ownedClosed.get(), "closing the owner must close the window it owns");
    }

    @Test
    public void aWindowOwnedByAClosedWindowIsSimplyIndependent() {
        CompletableFuture<String> report = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow keeper = app.createWindow(WindowOptions.defaults().withSize(200, 200).withVisible(false)); // keeps the loop alive
            WebWindow gone = app.createWindow(WindowOptions.defaults().withSize(200, 200).withVisible(false));
            gone.onClosed(() -> {
                WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200).withOwner(gone));
                window.onMessage(message -> {
                    report.complete(message);
                    window.close();
                    keeper.close();
                });
                window.loadHtml("<script>window.jolt.postMessage('alive')</script>");
            });
            gone.close();
        });
        assertEquals("alive", report.getNow(null));
    }
}
