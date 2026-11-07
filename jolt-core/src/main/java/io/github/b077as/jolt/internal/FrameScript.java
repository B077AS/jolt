package io.github.b077as.jolt.internal;

/**
 * The page-side half of a frameless window.
 *
 * <ul>
 *   <li>Elements marked {@code data-jolt-drag} move the window (a double click maximizes or restores it); buttons, links and form
 *       fields inside keep working and {@code data-jolt-no-drag} excludes anything else. On Windows a right click there opens the
 *       window menu.</li>
 *   <li>Thin invisible handles along the four edges and corners resize it (hidden while maximized).</li>
 *   <li>The window tells the page its state: {@code <html>} gets {@code data-jolt-maximized}, {@code data-jolt-inactive} (the
 *       window is not the one in front) and {@code data-jolt-platform}; {@code jolt.frame.isMaximized()}, {@code isFocused()} and
 *       {@code onChange()} report it.</li>
 *   <li>An element with {@code data-jolt-titlebar} is a ready-made title bar: it can be dragged, and the minimize, maximize/restore
 *       and close buttons are added at its right end, coloured through CSS custom properties ({@code --jolt-caption-fg} and so on,
 *       see the README).</li>
 *   <li>The maximize button, the kit's own or any element with {@code data-jolt-maximize}, is reported to the window so Windows 11
 *       can show its snap layouts over it; while it is covered the window feeds hover and press back as {@code data-jolt-hover}
 *       and {@code data-jolt-pressed}.</li>
 * </ul>
 */
public final class FrameScript {

    private FrameScript() {}

    /** @param platform {@code "windows"} or {@code "linux"}, put on {@code <html>} as {@code data-jolt-platform} */
    public static String build(String platform) {
        return SCRIPT.replace("__PLATFORM__", platform);
    }

    private static final String SCRIPT = """
            (() => {
              if (window.__joltFrame || window.top !== window || !window.jolt) return;
              Object.defineProperty(window, '__joltFrame', { value: true });
              const PREFIX = '\\u0001jolt-frame:';
              const PLATFORM = '__PLATFORM__';
              const send = (m) => window.jolt.postMessage(PREFIX + m);
              const SKIP = '[data-jolt-no-drag], button, a[href], input, select, textarea, label, summary, [role="button"], [contenteditable]';
              const DRAG = '[data-jolt-drag], [data-jolt-titlebar]';
              const R = () => document.documentElement; // not there yet when the script first runs

              // ---- state, pushed by the window ----
              const state = { maximized: false, focused: true, fullscreen: false };
              Object.defineProperty(window, '__joltFrameState', { value: state });
              const handles = [];
              const kits = [];

              const applyState = (maximized, focused, fullscreen) => {
                const changed = state.maximized !== !!maximized || state.focused !== !!focused || state.fullscreen !== !!fullscreen;
                state.maximized = !!maximized;
                state.focused = !!focused;
                state.fullscreen = !!fullscreen;
                if (R()) {
                  R().toggleAttribute('data-jolt-maximized', state.maximized);
                  R().toggleAttribute('data-jolt-inactive', !state.focused);
                  R().toggleAttribute('data-jolt-fullscreen', state.fullscreen);
                }
                for (const h of handles) h.style.display = state.maximized || state.fullscreen ? 'none' : 'block';
                for (const kit of kits) kit.update();
                reportMax();
                if (changed) {
                  window.dispatchEvent(new CustomEvent('jolt-frame', { detail: { maximized: state.maximized, focused: state.focused, fullscreen: state.fullscreen } }));
                }
              };
              Object.defineProperty(window, '__joltFrameApply', { value: applyState });

              // ---- dragging, double click, window menu ----
              addEventListener('mousedown', (e) => {
                if (e.button !== 0 || e.defaultPrevented) return;
                const t = e.target instanceof Element ? e.target : null;
                if (!t || !t.closest(DRAG) || t.closest(SKIP)) return;
                e.preventDefault();
                send(e.detail >= 2 ? 'maximize' : 'move');
              }, true);

              if (PLATFORM === 'windows') {
                addEventListener('contextmenu', (e) => {
                  const t = e.target instanceof Element ? e.target : null;
                  if (!t || !t.closest(DRAG) || t.closest(SKIP)) return;
                  e.preventDefault();
                  send('menu');
                }, true);
              }

              // ---- resize handles ----
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
              const installHandles = () => {
                for (const [edge, geometry, cursor] of HANDLES) {
                  const handle = document.createElement('div');
                  handle.setAttribute('data-jolt-handle', '');
                  handle.style.cssText = 'position:fixed;z-index:2147483647;background:transparent;' + geometry + 'cursor:' + cursor + ';'
                    + (state.maximized || state.fullscreen ? 'display:none;' : '');
                  handle.addEventListener('mousedown', (e) => {
                    if (e.button !== 0) return;
                    e.preventDefault();
                    send('resize:' + edge);
                  });
                  R().appendChild(handle);
                  handles.push(handle);
                }
              };

              // ---- the ready-made title bar buttons ----
              // Colours come from CSS custom properties, which pass through the shadow root. For each button b in
              // minimize|maximize|close and each of bg, fg, hover-bg, hover-fg, active-bg, active-fg the property --jolt-b-x wins over
              // the general --jolt-caption-x, which wins over the built-in default.
              const NAMES = ['minimize', 'maximize', 'close'];
              const LABELS = { minimize: 'Minimize', maximize: 'Maximize', restore: 'Restore', close: 'Close' };
              const ICONS = {
                minimize: 'M0 4.5h10v1H0z',
                maximize: 'M0 0h10v10H0zM1 1v8h8V1z',
                restore: 'M2 0h8v8H8V7h1V1H2zM0 2h8v8H0zm1 1v6h6V3z',
                close: 'M0 .7.7 0 5 4.3 9.3 0l.7.7L5.7 5 10 9.3l-.7.7L5 5.7.7 10 0 9.3 4.3 5z',
              };
              const stateVar = (b, s, fallback) => 'var(--jolt-' + b + '-' + s + ', var(--jolt-caption-' + s + ', ' + fallback + '))';
              const kitCss = () => {
                let css = ':host{all:initial}.bar{display:flex;height:100%}'
                  + 'button{all:unset;box-sizing:border-box;width:var(--jolt-caption-width,46px);height:100%;display:flex;'
                  + 'align-items:center;justify-content:center;cursor:default;-webkit-user-select:none;user-select:none}'
                  + 'svg{width:var(--jolt-caption-icon,10px);height:var(--jolt-caption-icon,10px);fill:currentColor;'
                  + 'pointer-events:none;shape-rendering:crispEdges}'
                  + ':host([data-inactive]) button{opacity:var(--jolt-caption-inactive-opacity,.55)}';
                for (const b of NAMES) {
                  const closeLike = b === 'close';
                  css += 'button.' + b + '{background:' + stateVar(b, 'bg', 'transparent') + ';color:' + stateVar(b, 'fg', 'inherit') + '}'
                    + 'button.' + b + ':hover,button.' + b + '[data-state=hover]{background:'
                    + stateVar(b, 'hover-bg', closeLike ? '#c42b1c' : 'rgba(128,128,128,.25)') + ';color:'
                    + stateVar(b, 'hover-fg', closeLike ? '#fff' : 'inherit') + '}'
                    + 'button.' + b + ':active,button.' + b + '[data-state=pressed]{background:'
                    + stateVar(b, 'active-bg', closeLike ? '#b22a1b' : 'rgba(128,128,128,.4)') + ';color:'
                    + stateVar(b, 'active-fg', closeLike ? '#fff' : 'inherit') + '}';
                }
                return css;
              };
              const icon = (path) => '<svg viewBox="0 0 10 10" aria-hidden="true"><path fill-rule="evenodd" d="' + path + '"/></svg>';

              const buildKit = (bar) => {
                const wanted = (bar.getAttribute('data-jolt-titlebar') || '').split(/\\s+/).filter((n) => NAMES.includes(n));
                const names = wanted.length ? NAMES.filter((n) => wanted.includes(n)) : NAMES;
                const host = document.createElement('div');
                host.setAttribute('data-jolt-caption', '');
                host.setAttribute('data-jolt-no-drag', '');
                host.style.cssText = 'display:flex;align-self:stretch;flex:none;margin-left:auto;height:var(--jolt-titlebar-height,auto);';
                const shadow = host.attachShadow({ mode: 'open' });
                const style = document.createElement('style');
                style.textContent = kitCss();
                const group = document.createElement('div');
                group.className = 'bar';
                const buttons = {};
                for (const name of names) {
                  const button = document.createElement('button');
                  button.type = 'button';
                  button.className = name;
                  button.tabIndex = -1;
                  button.innerHTML = icon(ICONS[name]);
                  button.addEventListener('click', () => {
                    if (name === 'minimize') window.jolt.frame.minimize();
                    else if (name === 'maximize') window.jolt.frame.toggleMaximize();
                    else window.jolt.frame.close();
                  });
                  group.appendChild(button);
                  buttons[name] = button;
                }
                shadow.append(style, group);
                bar.appendChild(host);
                const kit = {
                  bar, host, maximize: buttons.maximize || null,
                  update() {
                    host.toggleAttribute('data-inactive', !state.focused);
                    for (const name of names) {
                      const b = buttons[name];
                      const key = name === 'maximize' && state.maximized ? 'restore' : name;
                      b.title = LABELS[key];
                      b.setAttribute('aria-label', LABELS[key]);
                      if (name === 'maximize') b.innerHTML = icon(ICONS[key]);
                    }
                  },
                };
                kits.push(kit);
                kit.update();
                return kit;
              };

              const scanBars = () => {
                for (const bar of document.querySelectorAll('[data-jolt-titlebar]')) {
                  if (!kits.some((k) => k.bar === bar) && !bar.querySelector(':scope > [data-jolt-caption]')) buildKit(bar);
                }
                for (let i = kits.length - 1; i >= 0; i--) {
                  if (!kits[i].bar.isConnected) kits.splice(i, 1);
                }
                reportMax();
              };

              // ---- the maximize button: reported to the window, hover and press come back ----
              const maxButton = () => {
                const own = document.querySelector('[data-jolt-maximize]');
                if (own) return own;
                const kit = kits.find((k) => k.maximize && k.bar.isConnected);
                return kit ? kit.maximize : null;
              };
              let lastMax = null;
              let observed = null;
              function reportMax() {
                const el = maxButton();
                let message = 'maxbtn:none';
                if (el) {
                  const r = el.getBoundingClientRect();
                  const dpr = window.devicePixelRatio || 1;
                  const x = Math.round(r.left * dpr), y = Math.round(r.top * dpr);
                  const w = Math.round(r.width * dpr), h = Math.round(r.height * dpr);
                  if (w > 0 && h > 0) message = 'maxbtn:' + x + ',' + y + ',' + w + ',' + h;
                }
                if (el !== observed && window.ResizeObserver) {
                  if (!resizer) resizer = new ResizeObserver(() => reportMax());
                  resizer.disconnect();
                  if (el) resizer.observe(el);
                  observed = el;
                }
                if (message !== lastMax) {
                  lastMax = message;
                  send(message);
                }
              }
              let resizer = null;
              Object.defineProperty(window, '__joltFrameHover', {
                value: (button, pressed) => {
                  const el = maxButton();
                  if (!el) return;
                  const isKit = el.getRootNode() instanceof ShadowRoot;
                  if (isKit) {
                    if (button) el.setAttribute('data-state', pressed ? 'pressed' : 'hover');
                    else el.removeAttribute('data-state');
                  } else {
                    el.toggleAttribute('data-jolt-hover', !!button);
                    el.toggleAttribute('data-jolt-pressed', !!button && pressed);
                  }
                },
              });

              let scheduled = false;
              const later = () => {
                if (scheduled) return;
                scheduled = true;
                requestAnimationFrame(() => { scheduled = false; scanBars(); });
              };

              const install = () => {
                if (!document.documentElement) return;
                installHandles();
                R().setAttribute('data-jolt-platform', PLATFORM);
                scanBars();
                new MutationObserver(later).observe(R(), { childList: true, subtree: true, attributes: true, attributeFilter: ['data-jolt-titlebar', 'data-jolt-maximize'] });
                addEventListener('resize', later);
                send('state');
              };
              if (document.readyState === 'loading') addEventListener('DOMContentLoaded', install);
              else install();
            })();
            """;
}
