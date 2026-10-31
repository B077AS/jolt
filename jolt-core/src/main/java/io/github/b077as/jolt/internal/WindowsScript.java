package io.github.b077as.jolt.internal;

/**
 * The page-side half of {@link PageWindows}: {@code jolt.openWindow(path, options)} resolves with whether a window was opened (or
 * an existing one brought forward), {@code jolt.broadcast(text)} sends to the app's other windows, and
 * {@code jolt.onBroadcast(cb)} receives what they send.
 */
final class WindowsScript {

    private WindowsScript() {}

    static String build() {
        return """
                (() => {
                  if (!window.jolt || window.__joltWindows) return;
                  const ENCODER = new TextEncoder(), DECODER = new TextDecoder();
                  let channel = null, nextId = 1;
                  const pending = new Map();
                  const listeners = new Set();

                  const connect = () => {
                    if (!channel) {
                      channel = jolt.channel('jolt.windows');
                      channel.onMessage((buffer) => {
                        let m;
                        try { m = JSON.parse(DECODER.decode(buffer)); } catch (e) { return; }
                        if (m.op === 'broadcast') {
                          for (const cb of [...listeners]) {
                            try { cb(m.text); } catch (e) { console.error(e); }
                          }
                          return;
                        }
                        const entry = pending.get(m.id);
                        if (!entry) return;
                        pending.delete(m.id);
                        if (m.status === 1 || m.status === 2) entry.resolve(true);
                        else if (m.status === 0) entry.resolve(false);
                        else entry.reject(new Error('openWindow: rejected (not an address inside the app, or too many requests)'));
                      });
                      channel.onClose(() => {
                        for (const entry of pending.values()) entry.reject(new Error('openWindow: the connection to the app was lost'));
                        pending.clear();
                      });
                    }
                    if (channel.isOpen) return Promise.resolve(channel);
                    return new Promise((resolve, reject) => {
                      const timer = setTimeout(() => {
                        off();
                        reject(new Error('openWindow is not available: the Java window must call enableOpenWindow()'));
                      }, 10000);
                      const off = channel.onOpen(() => { clearTimeout(timer); off(); resolve(channel); });
                    });
                  };

                  const transmit = (ch, message) => ch.send(ENCODER.encode(JSON.stringify(message)));
                  const text = (value) => (value == null ? null : String(value));

                  async function open(path, options) {
                    if (typeof path !== 'string') throw new TypeError('openWindow: the path must be a string');
                    const o = options || {};
                    const ch = await connect();
                    const id = nextId++;
                    const answer = new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
                    const sent = transmit(ch, { id, op: 'open', path, key: text(o.key), title: text(o.title),
                      width: Number(o.width) || 0, height: Number(o.height) || 0 });
                    if (!sent) {
                      pending.delete(id);
                      throw new Error('openWindow: the app is not connected');
                    }
                    return answer;
                  }

                  async function broadcast(message) {
                    const ch = await connect();
                    if (!transmit(ch, { op: 'broadcast', text: String(message) })) {
                      throw new Error('broadcast: the app is not connected');
                    }
                  }

                  function onBroadcast(callback) {
                    if (typeof callback !== 'function') throw new TypeError('onBroadcast: the callback must be a function');
                    listeners.add(callback);
                    connect().catch(() => {});   // connect now, so a broadcast sent right after is not missed
                    return () => listeners.delete(callback);
                  }

                  Object.defineProperty(window, '__joltWindows', { value: Object.freeze({ open, broadcast, onBroadcast }) });
                  connect().catch(() => {});
                })();
                """;
    }
}
