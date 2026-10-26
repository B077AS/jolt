package io.github.b077as.jolt.examples.notes;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The app's remembered settings: which theme the user chose, and the colours it resolved to. The window needs these at
 * startup, before any page has loaded, to be created with the right title bar and background from its first frame.
 */
final class SettingsStore {

    /**
     * @param preference    what the user picked: {@code system}, {@code light} or {@code dark}
     * @param scheme        what that resolved to the last time the page ran: {@code light}, {@code dark} or {@code null}
     * @param backgroundRgb the page background as {@code 0xRRGGBB}
     * @param textRgb       the page text colour as {@code 0xRRGGBB}
     */
    record Settings(String preference, String scheme, int backgroundRgb, int textRgb) {
        static Settings defaults() {
            return new Settings("system", null, 0, 0);
        }
    }

    private static final Gson GSON = new Gson();

    private final Path file;
    private Settings current;

    SettingsStore(Path file) {
        this.file = file;
        this.current = load(file);
    }

    synchronized Settings get() {
        return current;
    }

    synchronized void save(Settings settings) {
        current = settings;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(settings), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save settings to " + file, e);
        }
    }

    private static Settings load(Path file) {
        if (!Files.isRegularFile(file)) {
            return Settings.defaults();
        }
        try {
            Settings loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Settings.class);
            // A hand-edited or damaged file must not stop the app from starting.
            return loaded != null && isPreference(loaded.preference()) ? loaded : Settings.defaults();
        } catch (IOException | RuntimeException e) {
            return Settings.defaults();
        }
    }

    private static boolean isPreference(String value) {
        return "system".equals(value) || "light".equals(value) || "dark".equals(value);
    }
}
