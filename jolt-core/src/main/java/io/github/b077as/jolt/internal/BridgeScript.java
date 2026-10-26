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
