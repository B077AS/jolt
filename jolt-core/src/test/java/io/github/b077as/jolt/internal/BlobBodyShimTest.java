package io.github.b077as.jolt.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlobBodyShimTest {

    @Test
    void doesNothingOutsideTheGivenOrigin() {
        String script = BlobBodyShim.script("https://jolt.localhost");
        assertTrue(script.contains("const ORIGIN = \"https://jolt.localhost\";"));
        assertTrue(script.contains("if (location.origin !== ORIGIN || window.__joltBodies) return;"));
        assertFalse(script.contains("__ORIGIN__"));
    }

    @Test
    void anOriginCannotBreakOutOfTheLiteral() {
        String script = BlobBodyShim.script("x\";alert(1);//</script>");
        assertFalse(script.contains("</script>"));
        assertTrue(script.contains("const ORIGIN = \"x\\\";alert(1);//"));
    }
}
