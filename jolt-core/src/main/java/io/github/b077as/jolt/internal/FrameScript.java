package io.github.b077as.jolt.internal;

/**
 * The page-side half of a frameless window: elements marked {@code data-jolt-drag} move the window (a double click maximizes or
 * restores it), and thin invisible handles along the four edges and corners resize it. Buttons, links and form fields inside a drag
 * region keep working, and {@code data-jolt-no-drag} excludes anything else.
 */
public final class FrameScript {

    private FrameScript() {}

    public static String build() {
        return """
                (() => {
                  if (window.__joltFrame || window.top !== window || !window.jolt) return;
                  Object.defineProperty(window, '__joltFrame', { value: true });
                  const PREFIX = '\\u0001jolt-frame:';
                  const send = (m) => window.jolt.postMessage(PREFIX + m);
                  const SKIP = '[data-jolt-no-drag], button, a[href], input, select, textarea, label, summary, [role="button"], [contenteditable]';

                  addEventListener('mousedown', (e) => {
                    if (e.button !== 0 || e.defaultPrevented) return;
                    const t = e.target instanceof Element ? e.target : null;
                    if (!t || !t.closest('[data-jolt-drag]') || t.closest(SKIP)) return;
                    e.preventDefault();
                    send(e.detail >= 2 ? 'maximize' : 'move');
                  }, true);

                  const HANDLES = [
                    [1, 'left:0;top:10px;bottom:10px;width:5px;', 'ew-resize'],
                    [2, 'right:0;top:10px;bottom:10px;width:5px;', 'ew-resize'],
                    [3, 'top:0;left:10px;right:10px;height:5px;', 'ns-resize'],
                    [6, 'bottom:0;left:10px;right:10px;height:5px;', 'ns-resize'],
                    [4, 'left:0;top:0;width:10px;height:10px;', 'nwse-resize'],
                    [5, 'right:0;top:0;width:10px;height:10px;', 'nesw-resize'],
                    [7, 'left:0;bottom:0;width:10px;height:10px;', 'nesw-resize'],
                    [8, 'right:0;bottom:0;width:10px;height:10px;', 'nwse-resize'],
                  ];
                  const install = () => {
                    if (!document.documentElement) return;
                    for (const [edge, geometry, cursor] of HANDLES) {
                      const handle = document.createElement('div');
                      handle.setAttribute('data-jolt-handle', '');
                      handle.style.cssText = 'position:fixed;z-index:2147483647;background:transparent;' + geometry + 'cursor:' + cursor + ';';
                      handle.addEventListener('mousedown', (e) => {
                        if (e.button !== 0) return;
                        e.preventDefault();
                        send('resize:' + edge);
                      });
                      document.documentElement.appendChild(handle);
                    }
                  };
                  if (document.readyState === 'loading') addEventListener('DOMContentLoaded', install);
                  else install();
                })();
                """;
    }
}
