package io.github.b077as.jolt.internal;

/**
 * The page-side half of {@link io.github.b077as.jolt.WebWindow#setFileDragSource}: when the user starts dragging an element that is
 * (inside) a {@code [data-jolt-drag-file]} element, the web view's own drag is cancelled and the host is asked to start a drag of
 * files instead. It runs in the top-level document only and needs {@code window.jolt}.
 */
public final class FileDragScript {

    private FileDragScript() {}

    public static String build() {
        return """
                (() => {
                  if (window.top !== window || window.__joltFileDrag) return;
                  Object.defineProperty(window, '__joltFileDrag', { value: true });
                  const PREFIX = '%s';
                  addEventListener('dragstart', (e) => {
                    const el = e.target && e.target.closest ? e.target.closest('[data-jolt-drag-file]') : null;
                    if (!el || !e.isTrusted || !window.jolt) return;
                    e.preventDefault();
                    const token = el.getAttribute('data-jolt-drag-file');
                    if (token) window.jolt.postMessage(PREFIX + JSON.stringify({ token }));
                  }, true);
                })();
                """.formatted(FileDragProtocol.PREFIX.replace("\u0001", "\\u0001"));
    }
}
