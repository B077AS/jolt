package io.github.b077as.jolt.internal;

/**
 * The page-side half of the message channel. Backends inject it before every document and supply the one
 * platform-specific piece: the expression that hands a string to native code.
 */
public final class BridgeScript {

    private BridgeScript() {}

    /**
     * @param nativeSend a JavaScript statement that sends the string variable {@code m} to the host, for example
     *                   {@code window.chrome.webview.postMessage(m)}
     */
    public static String build(String nativeSend) {
        return build(nativeSend, true);
    }

    /**
     * @param subframes whether the bridge also exists in frames inside the page; when false, only the top-level document
     *                  gets {@code window.jolt}
     */
    public static String build(String nativeSend, boolean subframes) {
        return """
                (() => {
                  if (window.jolt%s) return;
                  const listeners = new Set();
                  const send = (m) => { %s; };
                  Object.defineProperty(window, 'jolt', {
                    value: Object.freeze({
                      postMessage: (m) => send(String(m)),
                      onMessage: (cb) => { listeners.add(cb); return () => listeners.delete(cb); },
                      saveAs: (data, options) => {
                        const f = window.__joltSaveAs;
                        return f ? f(data, options) : Promise.reject(new Error("saveAs is not enabled: call enableSaveAs() on the Java window"));
                      },
                      notify: (title, body, options) => {
                        const f = window.__joltNotify;
                        return f ? f(title, body, options) : Promise.reject(new Error("notify is not enabled: call enableNotifications() on the Java window"));
                      },
                      frame: Object.freeze({
                        minimize: () => send('\\u0001jolt-frame:minimize'),
                        toggleMaximize: () => send('\\u0001jolt-frame:maximize'),
                        close: () => send('\\u0001jolt-frame:close'),
                      }),
                      openWindow: (path, options) => {
                        const w = window.__joltWindows;
                        return w ? w.open(path, options) : Promise.reject(new Error("openWindow is not enabled: call enableOpenWindow() on the Java window"));
                      },
                      broadcast: (message) => {
                        const w = window.__joltWindows;
                        return w ? w.broadcast(message) : Promise.reject(new Error("broadcast is not enabled: call enableOpenWindow() on the Java window"));
                      },
                      onBroadcast: (callback) => {
                        const w = window.__joltWindows;
                        if (!w) throw new Error("onBroadcast is not enabled: call enableOpenWindow() on the Java window");
                        return w.onBroadcast(callback);
                      },
                      channel: (name, options) => {
                        const open = window.__joltChannelFactory;
                        if (!open) throw new Error("no binary channel: call openChannel(name) on the Java side before the page loads");
                        return open(String(name), options);
                      },
                      __dispatch: (m) => {
                        for (const cb of [...listeners]) {
                          try { cb(m); } catch (e) { console.error(e); }
                        }
                      },
                    }),
                  });
                })();
                """.formatted(subframes ? "" : " || window.top !== window", nativeSend);
    }

    /** Script that delivers {@code message} to the page's {@code onMessage} listeners. */
    public static String dispatch(String message) {
        return "window.jolt && window.jolt.__dispatch(" + JsLiteral.quote(message) + ");";
    }
}
