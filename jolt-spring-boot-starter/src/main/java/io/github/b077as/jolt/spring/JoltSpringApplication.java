package io.github.b077as.jolt.spring;

import io.github.b077as.jolt.AppOptions;
import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOptions;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Runs a Spring Boot application in a Jolt window. Use it instead of {@code SpringApplication.run}:
 *
 * <pre>{@code
 * @SpringBootApplication
 * public class NotesApp {
 *     public static void main(String[] args) {
 *         JoltSpringApplication.run(NotesApp.class, args);
 *     }
 * }
 * }</pre>
 *
 * <p>Two ways for the page to reach the application, chosen by {@code jolt.mode} ({@code auto} by default):
 *
 * <ul>
 *   <li><b>rpc</b> (no web server; {@code spring.main.web-application-type=none} or no Spring MVC on the class path): the page's
 *       files come from {@code jolt.ui} ({@code classpath:ui} by default, i.e. {@code src/main/resources/ui}) and call
 *       {@link JoltRpc} bean methods with {@code jolt.call}. Nothing listens on the network.</li>
 *   <li><b>web</b> (a servlet web application, Spring MVC): the window shows the application's own server, with its
 *       controllers, templates and static files as they are. The server listens on {@code 127.0.0.1} on a free port (unless
 *       {@code server.address} / {@code server.port} say otherwise) and answers only the window: see {@code jolt.web.*}.
 *       {@code jolt.call} works there too.</li>
 * </ul>
 *
 * <p><b>Start-up.</b> The calling thread becomes Jolt's UI thread. Spring starts on another thread at the same time, so the
 * window and its web view are being created while the beans are: the slow parts overlap instead of adding up. The window is
 * hidden until the page has loaded ({@code jolt.window.show}). Its settings come from {@code jolt.window.*} in the environment,
 * and from {@link #window(UnaryOperator)}.
 *
 * <p><b>Shutting down.</b> Closing the last window closes the Spring context, and closing the context (for example
 * {@code SpringApplication.exit}) closes the window. {@code run} returns after both.
 *
 * <p>If Spring fails to start, the window shows what went wrong instead of disappearing, and {@code run} throws once it is
 * closed.
 */
public final class JoltSpringApplication {

    private static final Log LOG = LogFactory.getLog(JoltSpringApplication.class);

    private final Class<?>[] sources;
    private UnaryOperator<WindowOptions> windowOptions = UnaryOperator.identity();
    private UnaryOperator<AppOptions> appOptions = UnaryOperator.identity();
    private Consumer<SpringApplication> springCustomizer = spring -> { };
    private Consumer<WebWindow> onWindow = window -> { };

    /** @param sources the application's configuration classes, the first one being the main class */
    public JoltSpringApplication(Class<?>... sources) {
        if (sources == null || sources.length == 0) {
            throw new IllegalArgumentException("at least one source class is needed");
        }
        this.sources = sources.clone();
    }

    /** {@code new JoltSpringApplication(source).run(args)}. */
    public static ConfigurableApplicationContext run(Class<?> source, String... args) {
        return new JoltSpringApplication(source).run(args);
    }

    /**
     * Changes the main window's options after {@code jolt.window.*} were applied: an icon, a background colour, a title bar scheme.
     * The window always starts hidden; when it appears is {@code jolt.window.show}.
     */
    public JoltSpringApplication window(UnaryOperator<WindowOptions> customizer) {
        this.windowOptions = Objects.requireNonNull(customizer, "customizer");
        return this;
    }

    /** Changes the Jolt application's options (data folder, quiet native logs) after {@code jolt.app-id} was applied. */
    public JoltSpringApplication application(UnaryOperator<AppOptions> customizer) {
        this.appOptions = Objects.requireNonNull(customizer, "customizer");
        return this;
    }

    /**
     * Called on the UI thread as soon as the main window exists, while Spring may still be starting: the place for a menu bar,
     * a tray icon or file-drop listeners that must be there before the page loads.
     */
    public JoltSpringApplication onWindow(Consumer<WebWindow> listener) {
        this.onWindow = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** Changes the {@link SpringApplication} before it runs (profiles, banner, extra listeners). */
    public JoltSpringApplication spring(Consumer<SpringApplication> customizer) {
        this.springCustomizer = Objects.requireNonNull(customizer, "customizer");
        return this;
    }

    /**
     * Starts Spring and the window, and returns when both are closed.
     *
     * @return the application context, already closed
     * @throws IllegalStateException when Spring could not start (after the window that reported it was closed)
     */
    public ConfigurableApplicationContext run(String... args) {
        JoltRuntime runtime = new JoltRuntime();
        CompletableFuture<ConfigurableEnvironment> environment = new CompletableFuture<>();
        CompletableFuture<ConfigurableApplicationContext> ready = new CompletableFuture<>();
        AtomicBoolean shuttingDown = new AtomicBoolean();

        SpringApplication spring = new SpringApplication(sources);
        spring.setMainApplicationClass(sources[0]);
        Map<String, Object> defaults = new LinkedHashMap<>();
        // Lowest priority: an application's own settings win. A free port on loopback only; the window is the only client.
        defaults.put("server.address", "127.0.0.1");
        defaults.put("server.port", "0");
        spring.setDefaultProperties(defaults);
        spring.addInitializers(context -> register(context, runtime));
        spring.addListeners((ApplicationListener<ApplicationEvent>) event -> {
            switch (event) {
                case ApplicationEnvironmentPreparedEvent prepared -> environment.complete(prepared.getEnvironment());
                case ApplicationReadyEvent started -> ready.complete(started.getApplicationContext());
                case ApplicationFailedEvent failed -> {
                    environment.completeExceptionally(failed.getException());
                    ready.completeExceptionally(failed.getException());
                }
                case ContextClosedEvent closed -> {
                    // Closed from elsewhere (SpringApplication.exit, a shutdown hook): the window goes too.
                    if (!shuttingDown.get() && !ready.isCompletedExceptionally() && closed.getApplicationContext() == ready.getNow(null)) {
                        runtime.application.thenAccept(Application::quit);
                    }
                }
                default -> { }
            }
        });
        springCustomizer.accept(spring);

        Thread starter = Thread.ofPlatform().name("spring-boot").start(() -> {
            try {
                spring.run(args);
            } catch (Throwable t) {
                environment.completeExceptionally(t);
                ready.completeExceptionally(t);
            }
        });

        ConfigurableEnvironment env;
        try {
            env = environment.join();
        } catch (CompletionException e) {
            join(starter);
            throw new IllegalStateException("The Spring application could not start", e.getCause());
        }
        JoltProperties properties = Binder.get(env).bindOrCreate("jolt", JoltProperties.class);
        warnIfNotLoopback(env);

        Application app;
        WindowOptions options;
        try {
            options = windowOptions.apply(Desktop.windowOptions(properties, title(properties, env))).withVisible(false);
            app = Application.create(appOptions.apply(AppOptions.of(appId(properties, env))));
        } catch (RuntimeException e) {
            runtime.application.completeExceptionally(e);
            runtime.window.completeExceptionally(e);
            shuttingDown.set(true);
            closeSpring(starter, ready);
            throw e;
        }
        runtime.application.complete(app);
        Desktop desktop = new Desktop(app, runtime, properties);
        try {
            app.run(() -> {
                WebWindow window;
                try {
                    window = app.createWindow(options);
                } catch (RuntimeException e) {
                    LOG.error("The Jolt window could not be created", e);
                    runtime.window.completeExceptionally(e);
                    app.quit();
                    return;
                }
                runtime.window.complete(window);
                try {
                    onWindow.accept(window);
                } catch (RuntimeException e) {
                    LOG.error("The onWindow listener failed", e);
                }
                desktop.showAnywayLater(window);
                ready.whenComplete((context, failure) -> app.dispatch(() -> {
                    if (failure != null) {
                        desktop.showFailure(window, failure);
                    } else {
                        desktop.open(window, context);
                    }
                }));
            });
        } finally {
            shuttingDown.set(true);
            app.close();
            closeSpring(starter, ready);
        }
        try {
            return ready.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("The Spring application could not start", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    /** Puts the runtime, the Jolt application and the window into the context. */
    private static void register(ConfigurableApplicationContext context, JoltRuntime runtime) {
        if (context instanceof GenericApplicationContext generic) {
            generic.registerBean(JoltRuntime.BEAN_NAME, JoltRuntime.class, () -> runtime);
            // Lazy: waiting for them only when something asks. No destroy method: Jolt closes them itself, on its own thread.
            generic.registerBean("joltApplication", Application.class, runtime::application, definition -> {
                definition.setLazyInit(true);
                definition.setDestroyMethodName("");
            });
            generic.registerBean("joltWindow", WebWindow.class, runtime::window, definition -> {
                definition.setLazyInit(true);
                definition.setDestroyMethodName("");
            });
        } else {
            context.getBeanFactory().registerSingleton(JoltRuntime.BEAN_NAME, runtime);
        }
    }

    private String appId(JoltProperties properties, ConfigurableEnvironment env) {
        if (properties.getAppId() != null && !properties.getAppId().isBlank()) {
            return properties.getAppId().strip();
        }
        String name = env.getProperty("spring.application.name");
        return name != null && !name.isBlank() ? name.strip() : sources[0].getName();
    }

    private String title(JoltProperties properties, ConfigurableEnvironment env) {
        if (!properties.getWindow().getTitle().isBlank()) {
            return properties.getWindow().getTitle();
        }
        String name = env.getProperty("spring.application.name");
        return name != null && !name.isBlank() ? name.strip() : sources[0].getSimpleName();
    }

    private static void warnIfNotLoopback(ConfigurableEnvironment env) {
        String address = env.getProperty("server.address");
        if (address != null && !address.equals("127.0.0.1") && !address.equals("localhost") && !address.equals("::1")) {
            LOG.warn("server.address is " + address + ": other machines can reach the server. Jolt's guard still refuses every request "
                    + "that does not come from the window, but 127.0.0.1 (the default) keeps them from connecting at all.");
        }
    }

    /** Waits for Spring to finish starting (or failing), then closes the context. */
    private static void closeSpring(Thread starter, CompletableFuture<ConfigurableApplicationContext> ready) {
        join(starter);
        ConfigurableApplicationContext context = ready.isCompletedExceptionally() ? null : ready.getNow(null);
        if (context != null && context.isActive()) {
            context.close();
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
