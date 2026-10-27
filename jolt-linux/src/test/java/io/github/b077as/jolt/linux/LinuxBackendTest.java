package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.contract.WebWindowContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the shared backend contract against Linux + WebKitGTK 4.1. Needs GTK 3 and WebKitGTK installed and a display
 * (X11, or Wayland; under CI use Xvfb or a headless compositor, see {@code docker/linux-test}).
 */
@EnabledOnOs(OS.LINUX)
class LinuxBackendTest extends WebWindowContract {

    /**
     * Maximizing is a request to the window manager. A bare X server (the Xvfb of docker/linux-test x11 mode) has none, so
     * the request cannot take effect there; that run sets JOLT_TEST_NO_WINDOW_MANAGER and those tests are skipped.
     */
    @Override
    protected boolean maximizeIsHonoured() {
        return System.getenv("JOLT_TEST_NO_WINDOW_MANAGER") == null;
    }

    /** The Wayland run uses {@code cage}, a kiosk compositor that keeps windows maximized (JOLT_TEST_KIOSK_COMPOSITOR is set there). */
    @Override
    protected boolean restoreIsHonoured() {
        return maximizeIsHonoured() && System.getenv("JOLT_TEST_KIOSK_COMPOSITOR") == null;
    }

    /** WebKitGTK cannot take a Blob body and a synchronous XMLHttpRequest cannot wait for the conversion: it must throw, not crash. */
    @Test
    void aSynchronousXhrWithABlobThrowsNotSupportedInsteadOfCrashing() {
        java.util.Map<String, String> app = java.util.Map.of("index.html", "<script>"
                + "let result;"
                + "try { const x = new XMLHttpRequest(); x.open('POST', '/save', false); x.send(new Blob(['abc'])); result = 'sent:' + x.status; }"
                + "catch (e) { result = e.name; }"
                + "window.jolt.postMessage(result);</script>");
        assertEquals("NotSupportedError", resourceApp(app, request -> null, false));
    }
}
