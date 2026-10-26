package io.github.b077as.jolt;

import io.github.b077as.jolt.internal.AppDirs;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Application-wide settings.
 *
 * @param appId         stable reverse-DNS style identifier, for example {@code com.example.notes}; used to name
 *                      per-application data directories
 * @param dataDirectory where the web engine keeps cookies, caches and local storage; must be writable
 * @param quitWhenLastWindowClosed whether closing the last window ends the event loop
 */
public record AppOptions(String appId, Path dataDirectory, boolean quitWhenLastWindowClosed) {

    public AppOptions {
        Objects.requireNonNull(appId, "appId");
        if (appId.isBlank()) {
            throw new IllegalArgumentException("appId must not be blank");
        }
        Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    /** Options with the platform's conventional per-user data directory for {@code appId}. */
    public static AppOptions of(String appId) {
        return new AppOptions(appId, AppDirs.defaultDataDirectory(appId), true);
    }

    public AppOptions withDataDirectory(Path directory) {
        return new AppOptions(appId, directory, quitWhenLastWindowClosed);
    }

    public AppOptions withQuitWhenLastWindowClosed(boolean quit) {
        return new AppOptions(appId, dataDirectory, quit);
    }
}
