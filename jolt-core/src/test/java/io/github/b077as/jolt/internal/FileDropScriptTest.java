package io.github.b077as.jolt.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JS itself only runs in a real web view; this checks that the strict-mode pieces are in the script. */
class FileDropScriptTest {

    @Test
    void theScriptHasAnOptInStrictModeThatRefusesDragsOutsideZones() {
        String script = FileDropScript.build();
        assertTrue(script.contains("hasAttribute('data-jolt-drop-strict')"));
        assertTrue(script.contains("(strict() && !zone) ? 'none' : 'copy'"));
        assertTrue(script.contains("if (strict() && !zone) return;"));
    }
}
