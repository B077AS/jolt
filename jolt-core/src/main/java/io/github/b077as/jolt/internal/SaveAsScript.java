package io.github.b077as.jolt.internal;

/** The page-side half of {@link PageSaveAs}: {@code jolt.saveAs(data, options)}. */
final class SaveAsScript {

    private SaveAsScript() {}

    static String build() {
        return """
                (() => {
                  if (window.__joltSaveAs) return;
                  const CHUNK = 1 << 20, HIGH_WATER = 4 << 20, ENCODER = new TextEncoder(), DECODER = new TextDecoder();
                  let channel = null, nextId = 1;
                  const pending = new Map();

                  const u32 = (n) => { const o = new Uint8Array(4); new DataView(o.buffer).setUint32(0, n); return o; };
                  const concat = (parts) => {
                    const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
                    let at = 0;
                    for (const p of parts) { out.set(p, at); at += p.length; }
                    return out;
                  };
                  const str = (s) => { const b = ENCODER.encode(s); return concat([u32(b.length), b]); };
                  const frame = (type, id, ...parts) => concat([Uint8Array.of(type), u32(id), ...parts]);
                  const readStr = (view, at) => DECODER.decode(new Uint8Array(view.buffer, view.byteOffset + at + 4, view.getUint32(at)));
                  const deferred = () => {
                    const d = {};
                    d.promise = new Promise((resolve, reject) => { d.resolve = resolve; d.reject = reject; });
                    d.promise.catch(() => {});
                    return d;
                  };

                  const onMessage = (buffer) => {
                    const view = new DataView(buffer);
                    const entry = pending.get(view.getUint32(1));
                    if (!entry) return;
                    switch (view.getUint8(0)) {
                      case 11: entry.chosen.resolve(readStr(view, 5)); break;
                      case 12: entry.chosen.resolve(null); break;
                      case 13: entry.done.resolve(Number(view.getBigUint64(5))); break;
                      case 14: { const e = new Error(readStr(view, 5)); entry.chosen.reject(e); entry.done.reject(e); break; }
                    }
                  };

                  const connect = () => {
                    if (!channel) {
                      channel = jolt.channel('jolt.saveAs');
                      channel.onMessage(onMessage);
                      channel.onClose(() => {
                        for (const entry of pending.values()) {
                          const e = new Error('saveAs: the connection to the app was lost');
                          entry.chosen.reject(e);
                          entry.done.reject(e);
                        }
                      });
                    }
                    if (channel.isOpen) return Promise.resolve(channel);
                    return new Promise((resolve, reject) => {
                      const timer = setTimeout(() => {
                        off();
                        reject(new Error('saveAs is not available: the Java window must call enableSaveAs()'));
                      }, 10000);
                      const off = channel.onOpen(() => { clearTimeout(timer); off(); resolve(channel); });
                    });
                  };

                  const isData = (d) => typeof d === 'string' || d instanceof ArrayBuffer || ArrayBuffer.isView(d)
                    || (typeof Blob !== 'undefined' && d instanceof Blob);

                  async function* chunksOf(data) {
                    if (typeof data === 'string') data = ENCODER.encode(data);
                    if (data instanceof ArrayBuffer) data = new Uint8Array(data);
                    else if (ArrayBuffer.isView(data)) data = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
                    if (data instanceof Uint8Array) {
                      for (let at = 0; at < data.length; at += CHUNK) yield data.subarray(at, at + CHUNK);
                    } else {
                      for (let at = 0; at < data.size; at += CHUNK) yield new Uint8Array(await data.slice(at, at + CHUNK).arrayBuffer());
                    }
                  }

                  async function saveAs(data, options) {
                    if (!isData(data)) throw new TypeError('saveAs: data must be a string, ArrayBuffer, typed array or Blob');
                    options = options || {};
                    const filters = (options.filters || []).map((f) => ({ name: String(f.name), extensions: [...f.extensions].map(String) }));
                    const ch = await connect();
                    const id = nextId++;
                    const entry = { chosen: deferred(), done: deferred() };
                    pending.set(id, entry);
                    try {
                      const request = frame(1, id, str(String(options.title || '')), str(String(options.name || '')), u32(filters.length),
                        ...filters.flatMap((f) => [str(f.name), u32(f.extensions.length), ...f.extensions.map(str)]));
                      if (!ch.send(request)) throw new Error('saveAs: the app is not connected');
                      const name = await entry.chosen.promise;
                      if (name === null) return null;
                      for await (const chunk of chunksOf(data)) {
                        while (ch.bufferedAmount > HIGH_WATER) await new Promise((r) => setTimeout(r, 5));
                        if (!ch.send(frame(2, id, chunk))) throw new Error('saveAs: the connection to the app was lost');
                      }
                      ch.send(frame(3, id));
                      return { name, size: await entry.done.promise };
                    } catch (e) {
                      ch.send(frame(4, id)); // tells the app to throw away the half-written file
                      throw e;
                    } finally {
                      pending.delete(id);
                    }
                  }
                  Object.defineProperty(window, '__joltSaveAs', { value: saveAs });
                })();
                """;
    }
}
