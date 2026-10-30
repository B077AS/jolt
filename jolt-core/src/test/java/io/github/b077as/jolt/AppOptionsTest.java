package io.github.b077as.jolt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppOptionsTest {

    @Test
    void nativeLogsAreNotHiddenByDefault() {
        assertFalse(AppOptions.of("io.github.b077as.jolt.test").quietNativeLogs());
    }

    @Test
    void quietNativeLogsSurvivesTheOtherWithMethods() {
        AppOptions options = AppOptions.of("io.github.b077as.jolt.test").withQuietNativeLogs(true);
        assertTrue(options.withQuitWhenLastWindowClosed(false).quietNativeLogs());
        assertTrue(options.withDataDirectory(java.nio.file.Path.of("x")).quietNativeLogs());
    }
}
