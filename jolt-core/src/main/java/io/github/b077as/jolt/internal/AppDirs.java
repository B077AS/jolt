package io.github.b077as.jolt.internal;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/** Per-user data directory conventions. */
public final class AppDirs {

    private static final Pattern SAFE_APP_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private AppDirs() {}

    /** {@code %LOCALAPPDATA%\id} on Windows, {@code $XDG_DATA_HOME/id} (or {@code ~/.local/share/id}) elsewhere. */
    public static Path defaultDataDirectory(String appId) {
        return defaultDataDirectory(appId, System.getProperty("os.name", ""), System::getenv, System.getProperty("user.home"));
    }

    /** Testable form of {@link #defaultDataDirectory(String)}. */
    static Path defaultDataDirectory(String appId, String osName, java.util.function.UnaryOperator<String> env, String home) {
        if (!SAFE_APP_ID.matcher(appId).matches() || appId.contains("..")) {
            throw new IllegalArgumentException(
                    "appId must be letters, digits, '.', '_' or '-' and start with a letter or digit: " + appId);
        }
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String local = env.apply("LOCALAPPDATA");
            Path base = local != null && !local.isBlank() ? Path.of(local) : Path.of(home, "AppData", "Local");
            return base.resolve(appId);
        }
        String xdg = env.apply("XDG_DATA_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".local", "share");
        return base.resolve(appId);
    }
}
