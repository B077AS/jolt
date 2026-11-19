package io.github.b077as.jolt.spring;

import io.github.b077as.jolt.AppResources;
import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.Rpc;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.spring.internal.LoopbackGuard;
import io.github.b077as.jolt.spring.internal.WebServers;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The window side of {@link JoltSpringApplication}: builds the window's options, loads the page once Spring is ready (rpc or
 * web mode), and shows the window when the page has loaded. {@code jolt.window.show} is the only RPC method it adds.
 */
final class Desktop {

    /** The RPC method that shows the window. The page calls it itself in {@code manual} mode. */
    static final String SHOW_METHOD = "jolt.window.show";

    /** Calls {@link #SHOW_METHOD} once the page (the top document) has loaded: no blank window while it starts. */
    static final String SHOW_ON_LOAD = """
            (function () {
              if (window.top !== window) return;
              addEventListener('load', function () {
                if (window.jolt && window.jolt.call) window.jolt.call('jolt.window.show').catch(function () {});
              });
            })();""";

    private static final Log LOG = LogFactory.getLog(Desktop.class);

    private final Application app;
    private final JoltRuntime runtime;
    private final JoltProperties properties;
    private final AtomicBoolean shown = new AtomicBoolean();

    Desktop(Application app, JoltRuntime runtime, JoltProperties properties) {
        this.app = app;
        this.runtime = runtime;
        this.properties = properties;
    }

    /** {@code jolt.window.*} as window options (hidden: it is shown once the page is there). */
    static WindowOptions windowOptions(JoltProperties properties, String title) {
        JoltProperties.Window window = properties.getWindow();
        WindowOptions options = WindowOptions.defaults()
                .withTitle(title)
                .withSize(window.getWidth(), window.getHeight())
                .withMaximized(window.isMaximized())
                .withFrameless(window.isFrameless())
                .withResizable(window.isResizable())
                .withDevTools(window.isDevTools())
                .withVisible(false);
        if (window.getMinWidth() > 0 || window.getMinHeight() > 0) {
            options = options.withMinSize(window.getMinWidth(), window.getMinHeight());
        }
        return options;
    }

    /** Spring is ready: connect RPC, load the page. On the UI thread. */
    void open(WebWindow window, ApplicationContext context) {
        try {
            Rpc rpc = context.getBean(Rpc.class);
            rpc.on(SHOW_METHOD, call -> {
                show(window);
                return null;
            });
            rpc.attach(window);
            if (properties.getWindow().getShow() == JoltProperties.Show.ON_LOAD) {
                window.addInitScript(SHOW_ON_LOAD);
            }
            int port = WebServers.port(context);
            JoltProperties.Mode mode = properties.getMode() == JoltProperties.Mode.AUTO
                    ? (port > 0 ? JoltProperties.Mode.WEB : JoltProperties.Mode.RPC)
                    : properties.getMode();
            runtime.mode = mode;
            if (mode == JoltProperties.Mode.WEB) {
                openWeb(window, context, port);
            } else {
                openResources(window, context);
            }
        } catch (RuntimeException e) {
            LOG.error("The page could not be opened", e);
            showFailure(window, e);
        }
    }

    private void openWeb(WebWindow window, ApplicationContext context, int port) {
        if (port <= 0) {
            throw new IllegalStateException("jolt.mode is web, but the application runs no web server");
        }
        LoopbackGuard guard = context.getBeanProvider(LoopbackGuard.class).getIfAvailable();
        if (guard == null) {
            throw new IllegalStateException("jolt.mode web needs a servlet web application (Spring MVC). For a reactive (WebFlux) one, use "
                    + "jolt.mode=rpc with spring.main.web-application-type=none, or keep its server out of reach yourself");
        }
        String origin = "http://127.0.0.1:" + port;
        window.setSecurity(window.security().withTrustedOrigins(origin));
        window.loadUrl(origin + LoopbackGuard.ENTER_PATH + "?token=" + guard.enterToken());
    }

    private void openResources(WebWindow window, ApplicationContext context) {
        String location = properties.getUi() == null ? "" : properties.getUi().strip();
        AppResources resources = location.startsWith("file:")
                ? AppResources.directory(Path.of(location.substring("file:".length())))
                : AppResources.classpath(context.getClassLoader(), location.startsWith("classpath:") ? location.substring("classpath:".length()) : location);
        try {
            if (resources.read("index.html") == null) {
                throw new IllegalStateException("There is no index.html in " + location + " (jolt.ui). Put the page's files there, "
                        + "for example in src/main/resources/ui, or set jolt.ui.");
            }
        } catch (IOException e) {
            throw new IllegalStateException("The page's files in " + location + " (jolt.ui) cannot be read: " + e.getMessage(), e);
        }
        window.loadResources(resources);
    }

    /** Shows the window after {@code jolt.window.show-after} if the page has not said so by then. */
    void showAnywayLater(WebWindow window) {
        Duration after = properties.getWindow().getShowAfter();
        if (after == null || after.isNegative()) {
            return;
        }
        Thread.ofVirtual().name("jolt-show-anyway").start(() -> {
            try {
                Thread.sleep(after);
            } catch (InterruptedException e) {
                return;
            }
            if (!shown.get()) {
                LOG.warn("The page did not ask to be shown within " + after.toSeconds() + " s (jolt.window.show-after): showing the window anyway");
                show(window);
            }
        });
    }

    /** Shows the window once, then completes {@link JoltRuntime#whenShown()} (after the window really is visible). */
    void show(WebWindow window) {
        if (shown.compareAndSet(false, true)) {
            window.show();
            app.dispatch(() -> runtime.shown.complete(null));
        }
    }

    /** Spring did not start, or the page could not be opened: say so in the window instead of disappearing. */
    void showFailure(WebWindow window, Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (!text.isEmpty()) {
                text.append("\nCaused by: ");
            }
            text.append(t.getClass().getName()).append(t.getMessage() == null ? "" : ": " + t.getMessage());
        }
        window.loadHtml("<!doctype html><meta charset=\"utf-8\"><title>Error</title>"
                + "<body style=\"font:14px system-ui,sans-serif;margin:24px;color:#222;background:#fff\">"
                + "<h2 style=\"margin-top:0\">The application could not start</h2>"
                + "<pre style=\"white-space:pre-wrap;font:12px ui-monospace,monospace\">" + escape(text.toString()) + "</pre>"
                + "<p>The full report is in the application's log.</p></body>");
        show(window);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
