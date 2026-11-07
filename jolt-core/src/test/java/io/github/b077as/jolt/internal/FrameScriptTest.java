package io.github.b077as.jolt.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameScriptTest {

    @Test
    void thePlatformIsFilledInAndNoPlaceholderIsLeft() {
        String windows = FrameScript.build("windows");
        String linux = FrameScript.build("linux");
        assertTrue(windows.contains("const PLATFORM = 'windows';"));
        assertTrue(linux.contains("const PLATFORM = 'linux';"));
        assertFalse(windows.contains("__PLATFORM__"));
    }

    @Test
    void theScriptSpeaksTheProtocolTheWindowUnderstands() {
        String script = FrameScript.build("windows");
        for (String needed : new String[] {"\\u0001jolt-frame:", "'state'", "'menu'", "'maxbtn:none'", "'maxbtn:'", "'move'",
                "'resize:'", "__joltFrameApply", "__joltFrameHover", "data-jolt-titlebar", "data-jolt-maximize",
                "data-jolt-maximized", "data-jolt-inactive", "--jolt-caption-", "var(--jolt-"}) {
            assertTrue(script.contains(needed), "missing: " + needed);
        }
    }

    @Test
    void theBridgeExposesTheStateTheFrameScriptKeeps() {
        String bridge = BridgeScript.build("x(m)");
        for (String needed : new String[] {"isMaximized", "isFocused", "onChange", "menu:", "__joltFrameState"}) {
            assertTrue(bridge.contains(needed), "missing: " + needed);
        }
    }
}
