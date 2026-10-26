package io.github.b077as.jolt.examples.notes;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.b077as.jolt.AppOptions;
import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.ColorScheme;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A notes app: the page is the user interface, Java owns the data.
 *
 * <p>Run it normally for a window, or with {@code --smoke} to exercise every request automatically and exit with 0
 * on success.
 *
 * <p>Which page it shows: by default the plain HTML page inside the jar. {@code -Dui.url=http://localhost:5173} loads a
 * dev server instead; {@code -Dui.file=frontend/dist/index.html} loads a built single-file page from disk, such as the
 * React + TypeScript one in {@code frontend/}. The Java side is identical for all three.
 */
public final class NotesApp {

    private static final String APP_ID = "io.github.b077as.jolt.notes";

    private NotesApp() {}

    public static void main(String[] args) throws Exception {
        boolean smoke = List.of(args).contains("--smoke");

        // Where this app keeps its data. A smoke run uses a throwaway folder so it never touches real notes, and
        // -Dnotes.data=<folder> picks another one (handy for demos and screenshots).
        String dataOverride = System.getProperty("notes.data");
        Path dataDir = smoke ? Files.createTempDirectory("jolt-notes-smoke-")
                : dataOverride != null ? Path.of(dataOverride)
                : AppOptions.of(APP_ID).dataDirectory();
        NotesStore store = new NotesStore(dataDir.resolve("notes.json"));
        SettingsStore settings = new SettingsStore(dataDir.resolve("settings.json"));
        AtomicReference<String> smokeResult = new AtomicReference<>("timed out");

        Application app = Application.create(AppOptions.of(APP_ID).withDataDirectory(dataDir));
        try {
            app.run(() -> {
                WindowOptions options = WindowOptions.defaults()
                        .withTitle("Jolt Notes")
                        .withSize(460, 680)
                        .withDevTools(Boolean.getBoolean("jolt.devtools"))
                        .withIcon(readBundledBytes("/icon.ico"));
                // Start in the colours the user last had, so a dark app never flashes white on startup.
                SettingsStore.Settings saved = settings.get();
                if (saved.scheme() != null) {
                    options = options
                            .withTitleBarScheme(saved.scheme().equals("dark") ? ColorScheme.DARK : ColorScheme.LIGHT)
                            .withBackgroundColor(saved.backgroundRgb());
                }
                WebWindow window = app.createWindow(options);
                // Tell the page which theme the user chose before any of its scripts run, so its first paint is right.
                // (The value was validated when it was saved: system, light or dark.)
                window.addInitScript("window.__JOLT_PREFERENCE__ = \"" + saved.preference() + "\";");

                // The API the page can call. Each name is what the page passes to call('...').
                new Rpc(window)
                        .on("app.info", params -> Map.of(
                                "os", System.getProperty("os.name"),
                                "java", System.getProperty("java.version")))
                        .on("notes.list", params -> store.list())
                        .on("notes.add", params -> store.add(params.get("text").getAsString()))
                        .on("notes.delete", params -> Map.of("deleted", store.delete(params.get("id").getAsLong())))
                        .on("settings.get", params -> Map.of("preference", settings.get().preference()))
                        // The page cannot restyle the operating system's title bar, but it can ask Java to.
                        .on("window.setTheme", params -> {
                            ThemeRequest theme = ThemeRequest.parse(params);
                            window.setTitleBarScheme(theme.scheme());
                            window.setTitleBarColors(theme.background(), theme.text());
                            window.setBackgroundColor(theme.background());
                            settings.save(new SettingsStore.Settings(theme.preference(),
                                    theme.scheme().name().toLowerCase(java.util.Locale.ROOT), theme.background(), theme.text()));
                            return Map.of("ok", true);
                        });

                if (smoke) {
                    window.addInitScript("window.__SMOKE__ = true;"); // tells the page to test itself
                    window.onMessage(message -> {
                        JsonObject json = tryParse(message);
                        if (json != null && json.has("smoke")) {
                            smokeResult.set(json.get("smoke").getAsString() + (json.has("error") ? ": " + json.get("error").getAsString() : ""));
                            window.close();
                        }
                    });
                    startWatchdog(app, 30_000);
                }

                // Which page to show: a dev server, a built single-file page on disk, or the one inside the jar.
                String devServer = System.getProperty("ui.url");
                String pageFile = System.getProperty("ui.file");
                if (devServer != null) {
                    // The dev server is another origin, so the (secure by default) policy must be told to trust it.
                    window.setSecurity(window.security().withTrustedOrigins(devServer));
                    window.loadUrl(devServer);
                } else if (pageFile != null) {
                    window.loadHtml(readFile(Path.of(pageFile)));
                } else {
                    window.loadHtml(readBundledPage());
                }
            });
        } finally {
            app.close();
        }

        if (smoke) {
            System.out.println("SMOKE " + smokeResult.get());
            if (!smokeResult.get().equals("ok")) {
                System.exit(1);
            }
        }
        // Returning from main (instead of calling System.exit) lets the process end cleanly on every platform.
    }

    private static String readBundledPage() {
        try (InputStream in = NotesApp.class.getResourceAsStream("/ui/index.html")) {
            if (in == null) {
                throw new IllegalStateException("ui/index.html is missing from the application");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read ui/index.html", e);
        }
    }

    private static byte[] readBundledBytes(String resource) {
        try (InputStream in = NotesApp.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is missing from the application");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    private static JsonObject tryParse(String text) {
        try {
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void startWatchdog(Application app, long millis) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                return;
            }
            app.quit();
        }, "smoke-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }
}
