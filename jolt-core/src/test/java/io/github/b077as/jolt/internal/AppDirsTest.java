package io.github.b077as.jolt.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppDirsTest {

    @Test
    void windowsUsesLocalAppData() {
        Path p = AppDirs.defaultDataDirectory("com.example.app", "Windows 11",
                Map.of("LOCALAPPDATA", "C:\\Users\\me\\AppData\\Local")::get, "C:\\Users\\me");
        assertEquals(Path.of("C:\\Users\\me\\AppData\\Local", "com.example.app"), p);
    }

    @Test
    void windowsFallsBackToHome() {
        Path p = AppDirs.defaultDataDirectory("app", "Windows 10", Map.<String, String>of()::get, "C:\\Users\\me");
        assertEquals(Path.of("C:\\Users\\me", "AppData", "Local", "app"), p);
    }

    @Test
    void linuxHonoursXdgDataHome() {
        Path p = AppDirs.defaultDataDirectory("app", "Linux", Map.of("XDG_DATA_HOME", "/data")::get, "/home/me");
        assertEquals(Path.of("/data", "app"), p);
    }

    @Test
    void linuxFallsBackToLocalShare() {
        Path p = AppDirs.defaultDataDirectory("app", "Linux", Map.<String, String>of()::get, "/home/me");
        assertEquals(Path.of("/home/me", ".local", "share", "app"), p);
    }

    @Test
    void rejectsPathTraversalInAppId() {
        for (String bad : new String[] {"..", "../x", "a/b", "a\\b", ".hidden", "a..b", ""}) {
            assertThrows(IllegalArgumentException.class,
                    () -> AppDirs.defaultDataDirectory(bad, "Linux", Map.<String, String>of()::get, "/home/me"), bad);
        }
    }
}
