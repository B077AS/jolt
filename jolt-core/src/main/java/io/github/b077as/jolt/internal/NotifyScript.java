package io.github.b077as.jolt.internal;

/**
 * The page-side half of {@link PageNotify}: {@code jolt.notify(title, body, options)} resolves with whether the system showed it.
 * The options carry the callbacks ({@code onclick}, {@code buttons[].onclick}, {@code reply.onreply}); they stay in the page and
 * Java only reports which one the user used.
 */
final class NotifyScript {

    private NotifyScript() {}

    static String build() {
        return """
                (() => {
                  if (window.__joltNotify) return;
                  const ENCODER = new TextEncoder();
                  let channel = null, nextId = 1;
                  const pending = new Map();
                  const live = new Map();   // notification id to its options, for the events that come later
                  const MAX_LIVE = 32;

                  const u32 = (n) => { const o = new Uint8Array(4); new DataView(o.buffer).setUint32(0, n); return o; };
                  const str = (s) => { const b = ENCODER.encode(s); const o = new Uint8Array(4 + b.length); o.set(u32(b.length)); o.set(b, 4); return o; };
                  const concat = (parts) => {
                    const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
                    let at = 0;
                    for (const p of parts) { out.set(p, at); at += p.length; }
                    return out;
                  };

                  const connect = () => {
                    if (!channel) {
                      channel = jolt.channel('jolt.notify');
                      channel.onMessage((buffer) => {
                        const view = new DataView(buffer);
                        const status = view.getUint8(4);
                        if (status === 4) return onEvent(view);
                        const entry = pending.get(view.getUint32(0));
                        if (!entry) return;
                        pending.delete(view.getUint32(0));
                        if (status !== 1) live.delete(view.getUint32(0));
                        if (status === 1) entry.resolve(true);
                        else if (status === 0) entry.resolve(false);
                        else entry.reject(new Error(status === 2 ? 'notify: rejected (invalid, or too many notifications)' : 'notify: the system could not show it'));
                      });
                      channel.onClose(() => {
                        for (const entry of pending.values()) entry.reject(new Error('notify: the connection to the app was lost'));
                        pending.clear();
                      });
                    }
                    if (channel.isOpen) return Promise.resolve(channel);
                    return new Promise((resolve, reject) => {
                      const timer = setTimeout(() => {
                        off();
                        reject(new Error('notify is not available: the Java window must call enableNotifications()'));
                      }, 10000);
                      const off = channel.onOpen(() => { clearTimeout(timer); off(); resolve(channel); });
                    });
                  };

                  const DECODER = new TextDecoder();

                  const onEvent = (view) => {
                    const options = live.get(view.getUint32(0));
                    if (!options) return;
                    const kind = view.getUint8(5), index = view.getUint32(6);
                    const text = DECODER.decode(new Uint8Array(view.buffer, view.byteOffset + 14, view.getUint32(10)));
                    const callback = kind === 0 ? options.onclick
                      : kind === 1 ? (options.buttons[index] || {}).onclick
                      : (options.reply || {}).onreply;
                    if (typeof callback !== 'function') return;
                    try { callback(kind === 2 ? text : undefined); } catch (e) { console.error(e); }
                  };

                  const checkOptions = (options) => {
                    const o = options || {};
                    const buttons = o.buttons || [];
                    if (!Array.isArray(buttons) || buttons.length > 5) throw new TypeError('notify: buttons must be an array of at most 5');
                    for (const b of buttons) if (!b || typeof b.label !== 'string') throw new TypeError('notify: each button needs a label');
                    if (o.reply && typeof o.reply.label !== 'string') throw new TypeError('notify: reply needs a label');
                    return { onclick: o.onclick, buttons, reply: o.reply };
                  };

                  async function notify(title, body, options) {
                    const o = checkOptions(options);
                    const ch = await connect();
                    const id = nextId++;
                    const frame = [u32(id), str(String(title)), str(body == null ? '' : String(body)),
                      Uint8Array.of(typeof o.onclick === 'function' ? 1 : 0, o.buttons.length),
                      ...o.buttons.map((b) => str(b.label)),
                      Uint8Array.of(o.reply ? 1 : 0)];
                    if (o.reply) frame.push(str(o.reply.label), str(o.reply.placeholder == null ? '' : String(o.reply.placeholder)));
                    const answer = new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
                    live.set(id, o);
                    if (live.size > MAX_LIVE) live.delete(live.keys().next().value);
                    if (!ch.send(concat(frame))) {
                      pending.delete(id);
                      live.delete(id);
                      throw new Error('notify: the app is not connected');
                    }
                    return answer;
                  }
                  Object.defineProperty(window, '__joltNotify', { value: notify });
                })();
                """;
    }
}
