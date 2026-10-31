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

    /**
     * A Web Worker is out of BlobBodyShim's reach, so the README tells workers to send strings or bytes. Those must work over the
     * in-process transport. (A worker's Blob body aborts the JVM inside webkit_uri_scheme_request_get_http_body: not testable here.)
     */
    @Test
    void aWorkerCanPostStringsAndBytes() {
        java.util.Map<String, String> app = java.util.Map.of(
                "index.html", "<script>const w = new Worker('worker.js'); w.onmessage = e => window.jolt.postMessage(e.data);"
                        + "w.onerror = e => window.jolt.postMessage('worker error: ' + e.message);</script>",
                "worker.js", "(async () => { try {"
                        + "const a = await (await fetch('/echo', { method: 'POST', body: 'text ü' })).text();"
                        + "const b = await (await fetch('/echo', { method: 'POST', body: new TextEncoder().encode('bytes').buffer })).text();"
                        + "postMessage(a + '|' + b); } catch (e) { postMessage('failed: ' + e); } })();");
        io.github.b077as.jolt.RequestHandler echo = request -> request.method().equals("POST") && request.path().equals("/echo")
                ? io.github.b077as.jolt.AppResponse.text("got " + request.bodyText()) : null;
        assertEquals("got text ü|got bytes", resourceApp(app, echo, false));
    }
}
