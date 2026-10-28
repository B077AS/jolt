package io.github.b077as.jolt.internal;

/** The page-side half of {@link io.github.b077as.jolt.BinaryChannel}: {@code jolt.channel(name)}, built on a WebSocket. */
final class ChannelScript {

    private ChannelScript() {}

    /**
     * @param port  the loopback port of the window's {@link ChannelServer}
     * @param token its per-run secret; it stays inside this script's closure, and the server also checks the page's origin
     */
    static String build(int port, String token) {
        return """
                (() => {
                  if (window.__joltChannelFactory) return;
                  const P = %1$d, T = %2$s;
                  const encoder = new TextEncoder();
                  const open = (name, options) => {
                    const reconnect = !(options && options.reconnect === false);
                    const onOpen = new Set(), onMessage = new Set(), onClose = new Set();
                    let ws = null, closed = false, delay = 100, timer = null;
                    const emit = (set, value) => {
                      for (const cb of [...set]) {
                        try { cb(value); } catch (e) { console.error(e); }
                      }
                    };
                    const connect = () => {
                      if (closed) return;
                      const socket = new WebSocket('ws://127.0.0.1:' + P + '/c/' + encodeURIComponent(name) + '?t=' + T);
                      let opened = false;
                      socket.binaryType = 'arraybuffer';
                      socket.onopen = () => { opened = true; delay = 100; emit(onOpen); };
                      socket.onmessage = (e) => emit(onMessage, e.data);
                      socket.onclose = () => {
                        if (ws === socket) ws = null;
                        if (opened) emit(onClose);
                        if (!closed && reconnect) { timer = setTimeout(connect, delay); delay = Math.min(delay * 2, 2000); }
                      };
                      ws = socket;
                    };
                    connect();
                    const subscribe = (set) => (cb) => { set.add(cb); return () => set.delete(cb); };
                    return Object.freeze({
                      name,
                      get isOpen() { return !!ws && ws.readyState === 1; },
                      get bufferedAmount() { return ws ? ws.bufferedAmount : 0; },
                      send: (data) => {
                        if (!ws || ws.readyState !== 1) return false;
                        ws.send(typeof data === 'string' ? encoder.encode(data) : data);
                        return true;
                      },
                      onMessage: subscribe(onMessage),
                      onOpen: subscribe(onOpen),
                      onClose: subscribe(onClose),
                      close: () => { closed = true; clearTimeout(timer); if (ws) ws.close(); },
                    });
                  };
                  Object.defineProperty(window, '__joltChannelFactory', { value: open });
                })();
                """.formatted(port, JsLiteral.quote(token));
    }
}
