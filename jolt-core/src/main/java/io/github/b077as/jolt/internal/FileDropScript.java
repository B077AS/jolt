package io.github.b077as.jolt.internal;

/**
 * The page-side half of {@link io.github.b077as.jolt.WebWindow#onFileDrop}. It runs in the top-level document only.
 *
 * <p>While files are dragged over the page it accepts the drag (so the web view never navigates to a dropped file), puts
 * {@code data-jolt-drop-active} on {@code <html>} and {@code data-jolt-drop-over} on the nearest {@code [data-jolt-drop]} element,
 * for CSS to style. When the user drops, it tells the host the drop zone, the pointer position and the file names. WebView2 gets
 * the {@code File} objects with the message, which is how it learns their paths; elsewhere the host window reports the drop itself.
 *
 * <p>Opt-in strict mode: with {@code data-jolt-drop-strict} on {@code <html>}, a file drag over anything outside a
 * {@code [data-jolt-drop]} element gets {@code dropEffect = 'none'} (the system's not-allowed cursor) and a drop there is not
 * delivered. Without the attribute the whole page accepts drops. On Linux the host window still sees the drag natively, so only
 * the delivery is refused there, not the cursor.
 */
public final class FileDropScript {

    private FileDropScript() {}

    public static String build() {
        return """
                (() => {
                  if (window.top !== window || window.__joltFileDrop) return;
                  Object.defineProperty(window, '__joltFileDrop', { value: true });
                  const PREFIX = '%s';
                  const MAX_FILES = 4096;
                  let over = null;
                  const hasFiles = (e) => !!e.dataTransfer && Array.prototype.indexOf.call(e.dataTransfer.types || [], 'Files') >= 0;
                  const zoneOf = (t) => (t && t.closest) ? t.closest('[data-jolt-drop]') : null;
                  // strict mode: <html data-jolt-drop-strict> refuses file drags (not-allowed cursor, no delivery) outside [data-jolt-drop]
                  const strict = () => !!document.documentElement && document.documentElement.hasAttribute('data-jolt-drop-strict');
                  const mark = (el) => {
                    if (over === el) return;
                    if (over) over.removeAttribute('data-jolt-drop-over');
                    over = el;
                    if (el) el.setAttribute('data-jolt-drop-over', '');
                  };
                  const active = (on) => {
                    const root = document.documentElement;
                    if (!root) return;
                    if (on) root.setAttribute('data-jolt-drop-active', ''); else root.removeAttribute('data-jolt-drop-active');
                  };
                  const accept = (e) => {
                    if (!hasFiles(e)) return;
                    e.preventDefault();
                    const zone = zoneOf(e.target);
                    try { e.dataTransfer.dropEffect = (strict() && !zone) ? 'none' : 'copy'; } catch (x) {}
                    mark(zone);
                    active(true);
                  };
                  addEventListener('dragenter', accept, true);
                  addEventListener('dragover', accept, true);
                  addEventListener('dragleave', (e) => {
                    if (!hasFiles(e)) return;
                    // leaving the window: the pointer is at or beyond its edge (or reported as 0,0 by some engines)
                    const outside = e.clientX <= 0 || e.clientY <= 0 || e.clientX >= innerWidth - 1 || e.clientY >= innerHeight - 1;
                    if (outside) { mark(null); active(false); }
                  }, true);
                  addEventListener('drop', (e) => {
                    if (!hasFiles(e)) return;
                    e.preventDefault();
                    const zone = zoneOf(e.target);
                    mark(null);
                    active(false);
                    if (!e.isTrusted) return;
                    if (strict() && !zone) return;
                    const files = Array.prototype.slice.call(e.dataTransfer.files || [], 0, MAX_FILES);
                    if (!files.length) return;
                    const message = PREFIX + JSON.stringify({
                      zone: zone ? zone.getAttribute('data-jolt-drop') : null,
                      x: Math.round(e.clientX),
                      y: Math.round(e.clientY),
                      names: files.map((f) => f.name).join('\\u0000'),
                    });
                    const webview = window.chrome && window.chrome.webview;
                    if (webview && webview.postMessageWithAdditionalObjects) {
                      webview.postMessageWithAdditionalObjects(message, files);
                    } else if (window.jolt) {
                      window.jolt.postMessage(message);
                    }
                  }, true);
                })();
                """.formatted(FileDropProtocol.PREFIX.replace("\u0001", "\\u0001"));
    }
}
