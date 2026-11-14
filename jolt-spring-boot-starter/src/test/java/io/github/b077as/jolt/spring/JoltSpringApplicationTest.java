package io.github.b077as.jolt.spring;

import io.github.b077as.jolt.RpcException;
import io.github.b077as.jolt.WebWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Spring applications in real Jolt windows (they need a desktop and a web view, like the backend tests): rpc mode, web mode
 * behind the loopback guard, and a Spring application that fails to start.
 */
class JoltSpringApplicationTest {

    private static final Path DATA = Path.of(System.getProperty("java.io.tmpdir"), "jolt-spring-test");

    /** What the page reported, and the Java side's own observations. A new one per test. */
    static final AtomicReference<CompletableFuture<Map<String, Object>>> report = new AtomicReference<>(new CompletableFuture<>());

    private Thread watchdog;

    /** Quits the application if a test never finishes, so a bug fails instead of hanging. */
    private void watch(AtomicReference<io.github.b077as.jolt.Application> app) {
        watchdog = Thread.ofPlatform().daemon().start(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                return;
            }
            System.err.println("=== watchdog: quitting the application ===");
            if (app.get() != null) {
                app.get().quit();
            }
        });
    }

    @AfterEach
    void stopWatchdog() {
        if (watchdog != null) {
            watchdog.interrupt();
        }
    }

    private static JoltSpringApplication jolt(Class<?> config, AtomicReference<io.github.b077as.jolt.Application> app,
                                              AtomicReference<WebWindow> window) {
        return new JoltSpringApplication(config)
                .application(options -> options.withDataDirectory(DATA))
                .window(options -> options.withSize(500, 400))
                .onWindow(created -> window.set(created))
                .spring(spring -> spring.addListeners(event -> {
                    if (event instanceof org.springframework.boot.context.event.ApplicationReadyEvent ready) {
                        app.set(ready.getApplicationContext().getBean(JoltRuntime.class).application());
                    }
                }));
    }

    @JoltRpc("greet")
    static class Greeter {
        record Hello(String name, List<String> tags) {
        }

        @JoltRpc
        String hello(Hello hello) {
            return "Hello " + hello.name() + " " + hello.tags();
        }

        @JoltRpc
        void refuse() {
            throw new RpcException("no_way", "Not today");
        }
    }

    /** rpc mode: the report closes the Spring context, which must close the window and end run(). */
    static class RpcReports {
        private final ConfigurableApplicationContext context;
        private final JoltRuntime runtime;

        RpcReports(ConfigurableApplicationContext context, JoltRuntime runtime, WebWindow window) {
            this.context = context;
            this.runtime = runtime;
            assertNotNull(window, "the window can be injected");
        }

        @JoltRpc("test.report")
        void report(Map<String, Object> fromPage) {
            Map<String, Object> all = new LinkedHashMap<>(fromPage);
            all.put("mode", String.valueOf(runtime.mode()));
            report.get().complete(all);
            Thread.ofVirtual().start(() -> SpringApplication.exit(context));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class RpcApp {
        @Bean
        Greeter greeter() {
            return new Greeter();
        }

        @Bean
        RpcReports reports(ConfigurableApplicationContext context, JoltRuntime runtime, WebWindow window) {
            return new RpcReports(context, runtime, window);
        }
    }

    @Test
    void rpcModeServesThePageFromTheClasspathAndItsCallsReachJoltRpcBeans() throws Exception {
        report.set(new CompletableFuture<>());
        AtomicReference<io.github.b077as.jolt.Application> app = new AtomicReference<>();
        AtomicReference<WebWindow> window = new AtomicReference<>();
        watch(app);

        ConfigurableApplicationContext context = jolt(RpcApp.class, app, window).run(
                "--spring.main.web-application-type=none", "--spring.main.banner-mode=off",
                "--jolt.ui=classpath:test-ui/rpc", "--jolt.app-id=io.github.b077as.jolt.spring.test");

        Map<String, Object> out = report.get().get(5, TimeUnit.SECONDS);
        assertEquals("Hello Jolt [a, b]", out.get("greeting"), out.toString());
        assertEquals("no_way: Not today", out.get("refused"), out.toString());
        assertEquals("https://jolt.localhost", out.get("origin"), "served inside the web view, no port");
        assertEquals("RPC", out.get("mode"));
        assertFalse(context.isActive(), "closed");
        assertNotNull(window.get());
    }

    @RestController
    static class Api {
        @GetMapping("/api/greet")
        Map<String, String> greet(@RequestParam("name") String name) {
            return Map.of("greeting", "Hello " + name);
        }

        @PostMapping("/api/echo")
        String echo(@RequestBody String body) {
            return "echo " + body;
        }
    }

    /** web mode: the report also tries the server from outside the window (no cookie), then closes the window. */
    static class WebReports {
        private final Environment environment;
        private final JoltRuntime runtime;

        WebReports(Environment environment, JoltRuntime runtime) {
            this.environment = environment;
            this.runtime = runtime;
        }

        @JoltRpc("test.report")
        void report(Map<String, Object> fromPage, WebWindow window) throws Exception {
            Map<String, Object> all = new LinkedHashMap<>(fromPage);
            String port = environment.getProperty("local.server.port");
            all.put("port", port);
            try (HttpClient client = HttpClient.newHttpClient()) {
                HttpResponse<String> outsider = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/greet?name=x")).build(),
                        HttpResponse.BodyHandlers.ofString());
                all.put("outsider", outsider.statusCode());
            }
            all.put("mode", String.valueOf(runtime.mode()));
            all.put("shown", runtime.whenShown().isDone());
            report.get().complete(all);
            window.close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({Api.class, Greeter.class})
    static class WebApp {
        @Bean
        WebReports reports(Environment environment, JoltRuntime runtime) {
            return new WebReports(environment, runtime);
        }
    }

    @Test
    void webModeShowsTheApplicationsOwnServerWhichAnswersOnlyTheWindow() throws Exception {
        report.set(new CompletableFuture<>());
        AtomicReference<io.github.b077as.jolt.Application> app = new AtomicReference<>();
        AtomicReference<WebWindow> window = new AtomicReference<>();
        watch(app);

        ConfigurableApplicationContext context = jolt(WebApp.class, app, window).run(
                "--spring.main.banner-mode=off", "--spring.web.resources.static-locations=classpath:/test-ui/web/",
                "--jolt.app-id=io.github.b077as.jolt.spring.test");

        Map<String, Object> out = report.get().get(5, TimeUnit.SECONDS);
        assertEquals("Hello Jolt", out.get("get"), out.toString());
        assertEquals("echo ping", out.get("post"), out.toString());
        assertEquals("Hello Web []", out.get("rpc"), "jolt.call works in web mode too: " + out);
        assertEquals("http://127.0.0.1:" + out.get("port"), out.get("origin"), out.toString());
        assertEquals("/", out.get("path"), "the entry page redirected to the start page");
        assertEquals(403, out.get("outsider"), "a request without the window's cookie is refused");
        assertEquals("WEB", out.get("mode"));
        assertEquals(true, out.get("shown"), "the window was shown when the page loaded");
        assertFalse(context.isActive(), "closing the window closed the context");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class BrokenApp {
        @Bean
        Object boom() {
            throw new IllegalStateException("boom in a bean");
        }
    }

    @Test
    void whenSpringFailsToStartTheWindowSaysWhyAndRunThrowsOnceItIsClosed() throws Exception {
        AtomicReference<io.github.b077as.jolt.Application> app = new AtomicReference<>();
        AtomicReference<WebWindow> window = new AtomicReference<>();
        CompletableFuture<String> shownText = new CompletableFuture<>();
        watchdog = Thread.ofPlatform().daemon().start(() -> {
            // Reads the page until the error is there, then closes the window.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                WebWindow current = window.get();
                if (current != null) {
                    try {
                        String text = current.eval("document.body ? document.body.innerText : ''").get(5, TimeUnit.SECONDS);
                        if (text.contains("boom in a bean")) {
                            shownText.complete(text);
                            current.close();
                            return;
                        }
                    } catch (Exception ignored) {
                        // not loaded yet
                    }
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (window.get() != null) {
                window.get().close();
            }
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> jolt(BrokenApp.class, app, window).run(
                "--spring.main.web-application-type=none", "--spring.main.banner-mode=off",
                "--jolt.app-id=io.github.b077as.jolt.spring.test"));

        assertTrue(shownText.isDone(), "the window showed the failure");
        assertTrue(shownText.get().contains("The application could not start"), shownText.get());
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertEquals("boom in a bean", root.getMessage());
    }

    static {
        try {
            Files.createDirectories(DATA);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
