package io.github.b077as.jolt.contract;

import com.sun.net.httpserver.HttpServer;
import io.github.b077as.jolt.AppResources;
import io.github.b077as.jolt.AppResponse;
import io.github.b077as.jolt.BlockedEvent;
import io.github.b077as.jolt.CloseBehavior;
import io.github.b077as.jolt.CloseDecision;
import io.github.b077as.jolt.CrashEvent;
import io.github.b077as.jolt.DownloadDecision;
import io.github.b077as.jolt.DownloadRequest;
import io.github.b077as.jolt.LoadFailure;
import io.github.b077as.jolt.PageLoad;
import io.github.b077as.jolt.ResourceOptions;
import io.github.b077as.jolt.SecurityPolicy;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.b077as.jolt.contract.WebWindowContract.eventually;
import static io.github.b077as.jolt.contract.WebWindowContract.runApp;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loading, crashes, downloads, close requests and the Content-Security-Policy, which every backend must handle alike.
 * Real windows, like {@link WebWindowContract}; the test thread is the UI thread (or waits for it).
 */
public abstract class LifecycleContract {

    @TempDir
    Path folder;

    /** End the process running the window's page, the way a crash would. UI thread. */
    protected abstract void crashPageProcess(WebWindow window);

    /** What the title bar's close button does. UI thread. */
    protected abstract void clickCloseButton(WebWindow window);

    /** What a refused connection is reported as; not every web view says that is what happened. */
    protected LoadFailure.Reason refusedConnection() {
        return LoadFailure.Reason.CONNECTION;
    }

    @BeforeEach
    void traceTestName(TestInfo info) {
        if (System.getenv("JOLT_TEST_TRACE") != null) {
            System.err.println(">>> " + info.getDisplayName());
        }
    }

    private static WindowOptions small() {
        return WindowOptions.defaults().withSize(400, 300);
    }

    /** Runs {@code step} on a helper thread's timer, back on the UI thread, to let events that should not happen happen. */
    private static void later(io.github.b077as.jolt.Application app, long millis, Runnable step) {
        CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS).execute(() -> app.dispatch(step));
    }

    /** A server on a loopback port; every path answers what {@code answers} holds for it, others 404. */
    private static final class Server implements AutoCloseable {
        final String origin;
        final List<String> requested = new CopyOnWriteArrayList<>();
        private final HttpServer server;

        Server(Map<String, AppResponse> answers) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    String path = exchange.getRequestURI().getPath();
                    requested.add(path);
                    AppResponse answer = answers.getOrDefault(path, AppResponse.status(404));
                    answer.headers().forEach(exchange.getResponseHeaders()::add);
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    byte[] body = answer.body();
                    exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);
                    if (body.length > 0) {
                        exchange.getResponseBody().write(body);
                    }
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

    // ---- loading ----

    @Test
    public void aLoadedPageIsReported() {
        List<PageLoad> loads = new CopyOnWriteArrayList<>();
        List<LoadFailure> failures = new CopyOnWriteArrayList<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(small());
            window.onLoadFailed(failures::add);
            window.onLoad(load -> {
                loads.add(load);
                later(app, 300, window::close);
            });
            window.loadHtml("<p>hello</p>");
        });
        assertEquals(1, loads.size(), loads.toString());
        assertTrue(loads.getFirst().url().startsWith(SecurityPolicy.APP_ORIGIN), loads.toString());
        assertTrue(failures.isEmpty(), failures.toString());
    }

    private record LoadOutcome(List<PageLoad> loads, List<LoadFailure> failures, List<BlockedEvent> blocked) {}

    /** Opens a window, lets {@code load} navigate it, and collects what is reported until shortly after the first event. */
    private static LoadOutcome outcome(WindowOptions options, java.util.function.Consumer<WebWindow> load) {
        LoadOutcome seen = new LoadOutcome(new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>());
        runApp(60, app -> {
            WebWindow window = app.createWindow(options);
            AtomicInteger events = new AtomicInteger();
            Runnable done = () -> {
                if (events.incrementAndGet() == 1) {
                    later(app, 700, window::close);
                }
            };
            window.onLoad(e -> {
                seen.loads().add(e);
                done.run();
            });
            window.onLoadFailed(e -> {
                seen.failures().add(e);
                done.run();
            });
            window.onBlocked(e -> {
                seen.blocked().add(e);
                done.run();
            });
            load.accept(window);
        });
        return seen;
    }

    private static final AppResources ONE_PAGE =
            path -> path.equals("index.html") ? "<p>app</p>".getBytes(StandardCharsets.UTF_8) : null;

    @Test
    public void anAppPageIsReportedAsLoaded() {
        for (boolean loopback : new boolean[] {false, true}) {
            LoadOutcome seen = outcome(small(), window -> {
                if (loopback) {
                    window.loadResourcesOverLoopback(ONE_PAGE, null, ResourceOptions.defaults(), 0);
                } else {
                    window.loadResources(ONE_PAGE);
                }
            });
            assertEquals(1, seen.loads().size(), "loopback " + loopback + ": " + seen);
            assertEquals(200, seen.loads().getFirst().httpStatus(), "loopback " + loopback);
            assertTrue(seen.failures().isEmpty(), seen.toString());
        }
    }

    @Test
    public void aMissingAppPageIsReportedWithItsStatus() {
        for (boolean loopback : new boolean[] {false, true}) {
            ResourceOptions options = ResourceOptions.defaults().withStartPath("/nothing.html");
            LoadOutcome seen = outcome(small(), window -> {
                if (loopback) {
                    window.loadResourcesOverLoopback(ONE_PAGE, null, options, 0);
                } else {
                    window.loadResources(ONE_PAGE, null, options);
                }
            });
            assertTrue(seen.loads().isEmpty(), "loopback " + loopback + ": " + seen);
            assertEquals(1, seen.failures().size(), "loopback " + loopback + ": " + seen);
            LoadFailure failure = seen.failures().getFirst();
            assertEquals(LoadFailure.Reason.HTTP_STATUS, failure.reason(), failure.toString());
            assertEquals(404, failure.httpStatus(), failure.toString());
            assertTrue(failure.url().endsWith("/nothing.html"), failure.toString());
        }
    }

    @Test
    public void aServerErrorIsAFailure() throws Exception {
        try (Server server = new Server(Map.of("/", AppResponse.html("<p>broken</p>").withStatus(500)))) {
            LoadOutcome seen = outcome(small().withSecurity(SecurityPolicy.standard().withTrustedOrigins(server.origin)),
                    window -> window.loadUrl(server.origin + "/"));
            assertTrue(seen.loads().isEmpty(), seen.toString());
            assertEquals(1, seen.failures().size(), seen.toString());
            assertEquals(500, seen.failures().getFirst().httpStatus(), seen.toString());
        }
    }

    @Test
    public void aServerThatIsNotThereIsAConnectionFailure() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort(); // free again once closed: nothing listens there
        }
        String origin = "http://127.0.0.1:" + port;
        LoadOutcome seen = outcome(small().withSecurity(SecurityPolicy.standard().withTrustedOrigins(origin)),
                window -> window.loadUrl(origin + "/"));
        assertTrue(seen.loads().isEmpty(), seen.toString());
        assertEquals(1, seen.failures().size(), seen.toString());
        assertEquals(refusedConnection(), seen.failures().getFirst().reason(), seen.toString());
        assertEquals(origin + "/", seen.failures().getFirst().url());
    }

    @Test
    public void aBlockedNavigationIsNotAFailure() {
        LoadOutcome seen = outcome(small(), window -> window.loadUrl("http://127.0.0.1:9/"));
        assertEquals(1, seen.blocked().size(), seen.toString());
        assertTrue(seen.failures().isEmpty(), seen.toString());
        assertTrue(seen.loads().isEmpty(), seen.toString());
    }

    @Test
    public void reloadLoadsThePageAgain() {
        List<String> messages = new ArrayList<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(small());
            window.onMessage(message -> {
                messages.add(message);
                if (messages.size() == 1) {
                    window.reload();
                } else {
                    window.close();
                }
            });
            window.loadHtml("<script>window.jolt.postMessage('here')</script>");
        });
        assertEquals(List.of("here", "here"), messages);
    }

    // ---- crashes ----

    @Test
    public void aCrashedPageIsReportedFailsItsEvalsAndCanBeReloaded() {
        List<CrashEvent> crashes = new CopyOnWriteArrayList<>();
        List<String> messages = new ArrayList<>();
        CompletableFuture<Throwable> pending = new CompletableFuture<>();
        runApp(60, app -> {
            WebWindow window = app.createWindow(small());
            window.onCrash(crash -> {
                crashes.add(crash);
                later(app, 500, window::reload);
            });
            window.onMessage(message -> {
                messages.add(message);
                if (messages.size() == 1) {
                    window.eval("while (true) {}").whenComplete((r, e) -> pending.complete(e)); // never answers while the page lives
                    later(app, 300, () -> crashPageProcess(window));
                } else {
                    window.close();
                }
            });
            window.loadHtml("<script>window.jolt.postMessage('alive')</script>");
        });
        assertEquals(1, crashes.size(), crashes.toString());
        assertEquals(CrashEvent.Reason.PROCESS_EXITED, crashes.getFirst().reason());
        assertFalse(crashes.getFirst().reloading());
        assertEquals(List.of("alive", "alive"), messages);
        assertNotNull(pending.getNow(null), "the eval waiting on the dead page should have failed");
    }

    @Test
    public void aCrashedPageIsReloadedByItselfWhenAskedButNotForEver() {
        List<CrashEvent> crashes = new CopyOnWriteArrayList<>();
        AtomicInteger starts = new AtomicInteger();
        runApp(90, app -> {
            WebWindow window = app.createWindow(small());
            window.setReloadOnCrash(true);
            window.onCrash(crash -> {
                crashes.add(crash);
                if (!crash.reloading()) {
                    later(app, 1000, window::close);
                }
            });
            window.onMessage(message -> {
                starts.incrementAndGet();
                later(app, 300, () -> crashPageProcess(window));
            });
            window.loadHtml("<script>window.jolt.postMessage('start')</script>");
        });
        assertEquals(4, starts.get(), "the first load and three reloads");
        assertEquals(List.of(true, true, true, false), crashes.stream().map(CrashEvent::reloading).toList());
    }

    @Test
    public void aCrashedAppPageComesBackToo() {
        Map<String, byte[]> files = Map.of(
                "page.html", "<script src=\"alive.js\"></script>".getBytes(StandardCharsets.UTF_8),
                "alive.js", "window.jolt.postMessage(location.pathname);".getBytes(StandardCharsets.UTF_8));
        for (boolean loopback : new boolean[] {false, true}) {
            List<String> messages = new ArrayList<>();
            ResourceOptions options = ResourceOptions.defaults().withStartPath("/page.html");
            runApp(60, app -> {
                WebWindow window = app.createWindow(small());
                window.setReloadOnCrash(true);
                window.onMessage(message -> {
                    messages.add(message);
                    if (messages.size() == 1) {
                        later(app, 300, () -> crashPageProcess(window));
                    } else {
                        window.close();
                    }
                });
                if (loopback) {
                    window.loadResourcesOverLoopback(files::get, null, options, 0);
                } else {
                    window.loadResources(files::get, null, options);
                }
            });
            assertEquals(List.of("/page.html", "/page.html"), messages, "loopback " + loopback);
        }
    }

    // ---- downloads ----

    /** A page that downloads a blob holding {@code hello} under a hostile suggested name. */
    private static final String DOWNLOAD_PAGE = "<a id=\"a\" download=\"../../sneaky.txt\">get</a><script>"
            + "const a = document.getElementById('a');"
            + "a.href = URL.createObjectURL(new Blob(['hello'], { type: 'text/plain' })); a.click();</script>";

    private static boolean holds(Path file, String text) {
        try {
            return Files.exists(file) && Files.readString(file).equals(text);
        } catch (IOException e) {
            return false; // still being written
        }
    }

    @Test
    public void aDownloadWithNoHandlerIsRefusedAndReported() {
        LoadOutcome seen = outcome(small(), window -> {
            window.loadHtml(DOWNLOAD_PAGE);
        });
        assertTrue(seen.blocked().stream().anyMatch(b -> b.kind() == BlockedEvent.Kind.DOWNLOAD), seen.toString());
    }

    @Test
    public void aDownloadGoesWhereTheHandlerSaysAndReplacesWhatIsThere() throws Exception {
        Path target = folder.resolve("chosen file ü.txt");
        Files.writeString(target, "something older and longer");
        List<DownloadRequest> requests = new CopyOnWriteArrayList<>();
        CompletableFuture<Boolean> arrived = new CompletableFuture<>();
        runApp(60, app -> {
            WebWindow window = app.createWindow(small());
            window.onDownload(request -> {
                requests.add(request);
                eventually(() -> holds(target, "hello"), 20_000).thenAccept(ok -> {
                    arrived.complete(ok);
                    window.close();
                });
                return DownloadDecision.saveTo(target);
            });
            window.loadHtml(DOWNLOAD_PAGE);
        });
        assertTrue(arrived.getNow(false), "the file did not arrive");
        assertEquals(1, requests.size());
        DownloadRequest request = requests.getFirst();
        assertTrue(request.url().startsWith("blob:"), request.toString());
        assertFalse(request.fileName().contains("/") || request.fileName().contains("\\") || request.fileName().startsWith("."),
                request.toString());
        assertTrue(request.fileName().endsWith(".txt"), request.toString());
    }

    @Test
    public void aDownloadWaitsForADecisionMadeLater() {
        Path target = folder.resolve("later.txt");
        CompletableFuture<Boolean> arrived = new CompletableFuture<>();
        CompletableFuture<Boolean> waited = new CompletableFuture<>();
        runApp(60, app -> {
            WebWindow window = app.createWindow(small());
            window.onDownload(request -> {
                CompletableFuture<Optional<Path>> choice = new CompletableFuture<>();
                CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS).execute(() -> {
                    waited.complete(!Files.exists(target));
                    choice.complete(Optional.of(target)); // from another thread
                    eventually(() -> holds(target, "hello"), 20_000).thenAccept(ok -> {
                        arrived.complete(ok);
                        window.close();
                    });
                });
                return DownloadDecision.saveWhen(choice);
            });
            window.loadHtml(DOWNLOAD_PAGE);
        });
        assertTrue(waited.getNow(false), "the file was written before the decision");
        assertTrue(arrived.getNow(false), "the file did not arrive");
    }

    @Test
    public void aDeniedDownloadWritesNothing() {
        AtomicInteger asked = new AtomicInteger();
        runApp(30, app -> {
            WebWindow window = app.createWindow(small());
            window.onDownload(request -> {
                asked.incrementAndGet();
                later(app, 1000, window::close);
                return DownloadDecision.deny();
            });
            window.loadHtml(DOWNLOAD_PAGE);
        });
        assertEquals(1, asked.get());
    }

    @Test
    public void aWindowClosedWhileADownloadWaitsClosesCleanly() {
        runApp(30, app -> {
            WebWindow window = app.createWindow(small());
            window.onDownload(request -> {
                later(app, 300, window::close);
                return DownloadDecision.saveWhen(new CompletableFuture<>()); // never answered
            });
            window.loadHtml(DOWNLOAD_PAGE);
        });
    }

    @Test
    public void anAttachmentBecomesADownloadAndNotAFailedPage() throws Exception {
        AppResponse attachment = AppResponse.text("report body")
                .withHeader("Content-Disposition", "attachment; filename=\"report.txt\"");
        Path target = folder.resolve("report.txt");
        try (Server server = new Server(Map.of("/report", attachment))) {
            List<LoadFailure> failures = new CopyOnWriteArrayList<>();
            List<DownloadRequest> requests = new CopyOnWriteArrayList<>();
            CompletableFuture<Boolean> arrived = new CompletableFuture<>();
            runApp(60, app -> {
                WebWindow window = app.createWindow(small().withSecurity(SecurityPolicy.standard().withTrustedOrigins(server.origin)));
                window.onLoadFailed(failures::add);
                window.onDownload(request -> {
                    requests.add(request);
                    eventually(() -> holds(target, "report body"), 20_000).thenAccept(ok -> {
                        arrived.complete(ok);
                        later(app, 500, window::close);
                    });
                    return DownloadDecision.saveTo(target);
                });
                window.loadUrl(server.origin + "/report");
            });
            assertTrue(arrived.getNow(false), "the file did not arrive; requests " + requests + ", failures " + failures);
            assertEquals("report.txt", requests.getFirst().fileName());
            assertTrue(failures.isEmpty(), failures.toString());
        }
    }

    // ---- close requests ----

    @Test
    public void aCloseRequestCanBeRefusedAndLaterAllowed() {
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        CompletableFuture<Integer> closedAfterRefusal = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(small());
            window.onClosed(closed::incrementAndGet);
            window.onCloseRequest(() -> {
                if (asked.incrementAndGet() == 1) {
                    later(app, 500, () -> {
                        closedAfterRefusal.complete(closed.get());
                        clickCloseButton(window);
                    });
                    return CloseDecision.cancel();
                }
                return CloseDecision.allow();
            });
            clickCloseButton(window);
        });
        assertEquals(2, asked.get());
        assertEquals(0, closedAfterRefusal.getNow(-1));
        assertEquals(1, closed.get());
    }

    @Test
    public void aLaterCloseDecisionIsWaitedForAndAskedOnce() {
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        CompletableFuture<Integer> closedWhileWaiting = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(small());
            window.onClosed(closed::incrementAndGet);
            window.onCloseRequest(() -> {
                asked.incrementAndGet();
                CompletableFuture<Boolean> answer = new CompletableFuture<>();
                later(app, 300, window::requestClose); // ignored: one question at a time
                CompletableFuture.delayedExecutor(900, TimeUnit.MILLISECONDS).execute(() -> {
                    closedWhileWaiting.complete(closed.get());
                    answer.complete(true);
                });
                return CloseDecision.when(answer);
            });
            window.requestClose();
        });
        assertEquals(1, asked.get());
        assertEquals(0, closedWhileWaiting.getNow(-1));
        assertEquals(1, closed.get());
    }

    @Test
    public void thePagesOwnWindowCloseIsAskedAboutToo() {
        AtomicInteger asked = new AtomicInteger();
        runApp(30, app -> {
            WebWindow window = app.createWindow(small());
            window.onCloseRequest(() -> {
                asked.incrementAndGet();
                return CloseDecision.allow();
            });
            // not while the document is still being parsed: WebView2 sometimes ignores that on a process's first load
            window.loadHtml("<script>setTimeout(() => window.close(), 500)</script>");
        });
        assertEquals(1, asked.get());
    }

    @Test
    public void closeFromCodeDoesNotAskAndAFailingHandlerKeepsTheWindow() {
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        CompletableFuture<Integer> closedAfterFailure = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(small());
            window.onClosed(closed::incrementAndGet);
            window.onCloseRequest(() -> {
                asked.incrementAndGet();
                later(app, 500, () -> {
                    closedAfterFailure.complete(closed.get());
                    window.close();
                });
                throw new IllegalStateException("a bug in the handler");
            });
            window.requestClose();
        });
        assertEquals(1, asked.get());
        assertEquals(0, closedAfterFailure.getNow(-1));
        assertEquals(1, closed.get());
    }

    @Test
    public void aWindowThatHidesOnCloseDoesNotAsk() {
        AtomicInteger asked = new AtomicInteger();
        CompletableFuture<Boolean> hidden = new CompletableFuture<>();
        runApp(30, app -> {
            WebWindow window = app.createWindow(small());
            window.setCloseBehavior(CloseBehavior.HIDE);
            window.onCloseRequest(() -> {
                asked.incrementAndGet();
                return CloseDecision.allow();
            });
            later(app, 200, () -> {
                clickCloseButton(window);
                eventually(() -> !window.isVisible(), 5000).thenAccept(gone -> {
                    hidden.complete(gone);
                    window.close();
                });
            });
        });
        assertTrue(hidden.getNow(false));
        assertEquals(0, asked.get());
    }

    // ---- Content-Security-Policy ----

    /**
     * Reports whether the inline script ran, whether eval works, and whether the policy refuses a request to another host.
     * Whether such a request would get through otherwise depends on the network and on mixed-content rules, so it is the
     * policy's own violation event that is looked at.
     */
    private static final String PROBE = "let refused = false;"
            + "addEventListener('securitypolicyviolation', e => { if (String(e.violatedDirective).startsWith('connect-src')) refused = true; });"
            + "addEventListener('load', async () => {"
            + "let evaluated = 'blocked'; try { evaluated = new Function('return 1')() === 1 ? 'ran' : '?'; } catch (e) {}"
            + "try { await fetch('https://other.invalid/data'); } catch (e) {}"
            + "await new Promise(done => setTimeout(done, 500));"
            + "window.jolt.postMessage(`inline=${!!window.inline} eval=${evaluated} remote=${refused ? 'refused' : 'allowed'}`);"
            + "});";

    private static final Map<String, byte[]> PROBE_APP = Map.of(
            "index.html", "<script>window.inline = true</script><script src=\"probe.js\"></script>".getBytes(StandardCharsets.UTF_8),
            "probe.js", PROBE.getBytes(StandardCharsets.UTF_8));

    private static String report(SecurityPolicy policy, java.util.function.Consumer<WebWindow> load) {
        CompletableFuture<String> got = new CompletableFuture<>();
        runApp(40, app -> {
            WebWindow window = app.createWindow(small().withSecurity(policy));
            window.onMessage(message -> {
                got.complete(message);
                window.close();
            });
            load.accept(window);
        });
        return got.getNow(null);
    }

    @Test
    public void appFilesGetAPolicyThatRefusesInlineScriptsEvalAndOtherHosts() {
        for (boolean loopback : new boolean[] {false, true}) {
            String result = report(SecurityPolicy.standard(), window -> {
                if (loopback) {
                    window.loadResourcesOverLoopback(PROBE_APP::get, null, ResourceOptions.defaults(), 0);
                } else {
                    window.loadResources(PROBE_APP::get);
                }
            });
            assertEquals("inline=false eval=blocked remote=refused", result, "loopback " + loopback);
        }
    }

    @Test
    public void theAppPolicyCanBeTurnedOff() {
        String result = report(SecurityPolicy.standard().withContentSecurityPolicy(null),
                window -> window.loadResources(PROBE_APP::get));
        assertEquals("inline=true eval=ran remote=allowed", result);
    }

    @Test
    public void htmlDocumentsRunTheirInlineScriptsButReachNoOtherHost() {
        String page = "<!doctype html><html lang=\"en\"><head><title>t</title></head><body>"
                + "<script>window.inline = true</script><script>" + PROBE + "</script>";
        assertEquals("inline=true eval=blocked remote=refused", report(SecurityPolicy.standard(), w -> w.loadHtml(page)));
        // the policy must not push the page into quirks mode
        String mode = report(SecurityPolicy.standard(),
                w -> w.loadHtml("<!doctype html><script>window.jolt.postMessage(document.compatMode)</script>"));
        assertEquals("CSS1Compat", mode);
        assertEquals("inline=true eval=ran remote=allowed",
                report(SecurityPolicy.standard().withHtmlContentSecurityPolicy(null), w -> w.loadHtml(page)));
    }

    @Test
    public void aRedirectInsideTheAppStillArrivesUnderThePolicy() {
        Map<String, byte[]> files = Map.of(
                "index.html", "<script src=\"go.js\"></script>".getBytes(StandardCharsets.UTF_8),
                "go.js", "location.href = '/from';".getBytes(StandardCharsets.UTF_8),
                "there.html", "<script src=\"there.js\"></script>".getBytes(StandardCharsets.UTF_8),
                "there.js", "window.jolt.postMessage('arrived at ' + location.pathname);".getBytes(StandardCharsets.UTF_8));
        String result = report(SecurityPolicy.standard(), window -> window.loadResources(files::get,
                request -> request.path().equals("/from") ? AppResponse.redirect("/there.html") : null));
        assertEquals("arrived at /there.html", result);
    }
}
