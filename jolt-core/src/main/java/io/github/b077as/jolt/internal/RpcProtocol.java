package io.github.b077as.jolt.internal;

import java.util.Map;

/**
 * The wire format of {@code io.github.b077as.jolt.Rpc} and its page half.
 *
 * <p>Page to Java travels over the ordinary message bridge, with a prefix application code never sees (the backends hand such messages
 * to {@link PageRpc} before their own listeners), as JSON:
 * <pre>
 * {"t":"hello","s":session}                                    a document started (its session is new)
 * {"t":"call","s":session,"id":n,"m":"method","p":params}      call a method ("st":true after "id": read it as a stream)
 * {"t":"cancel","s":session,"id":n}                            the page gave up on call n
 * {"t":"more","s":session,"id":n,"n":k}                          the page consumed k items of stream n: send k more
 * </pre>
 * Java to page is a script that hands one JSON text to {@code window.__joltRpc.receive}, so it does not reach the page's
 * {@code jolt.onMessage} listeners either:
 * <pre>
 * {"t":"result","s":session,"id":n,"r":value}
 * {"t":"error","s":session,"id":n,"e":{"code":..,"message":..,"data":..}}
 * {"t":"progress","s":session,"id":n,"d":value}
 * {"t":"item","s":session,"id":n,"d":value}                        one item of stream n
 * {"t":"end","s":session,"id":n}                                    stream n is over (an error ends it too)
 * {"t":"event","n":"name","d":value}
 * </pre>
 * The session is a random id each document picks, so answers meant for an earlier document (before a reload) are ignored by the
 * next one, and Java cancels the calls of a document as soon as a newer one speaks.
 */
public final class RpcProtocol {

    private RpcProtocol() {}

    /** Starts every page-to-Java RPC message. A control character, so a page's own messages are unlikely to start with it. */
    public static final String PREFIX = "\u0001jolt-rpc:";

    static final int MAX_SESSION_CHARS = 128;
    public static final int MAX_METHOD_CHARS = 256;
    /** JavaScript's {@code Number.MAX_SAFE_INTEGER}: ids above it could not have come from the page script. */
    static final long MAX_ID = 9_007_199_254_740_991L;

    /** One message from the page. {@code id} is 0 and {@code method}/{@code params} are {@code null} where the kind has none. */
    public record Request(String type, String session, long id, String method, String params, boolean stream, int count) {
        public Request(String type, String session, long id, String method, String params) {
            this(type, session, id, method, params, false, 0);
        }
    }

    public static boolean isRpcMessage(String message) {
        return message.startsWith(PREFIX);
    }

    /** The message, or {@code null} if it is malformed or of an unknown kind. */
    public static Request parse(String message) {
        if (!isRpcMessage(message)) {
            return null;
        }
        try {
            Map<String, String> m = MiniJson.members(message.substring(PREFIX.length()));
            String type = text(m.get("t"), 16);
            String session = text(m.get("s"), MAX_SESSION_CHARS);
            if (type == null || session == null || session.isEmpty()) {
                return null;
            }
            switch (type) {
                case "hello" -> {
                    return new Request(type, session, 0, null, null);
                }
                case "cancel" -> {
                    long id = id(m.get("id"));
                    return id > 0 ? new Request(type, session, id, null, null) : null;
                }
                case "more" -> {
                    long id = id(m.get("id"));
                    String n = m.get("n");
                    if (id <= 0 || n == null || n.isEmpty() || n.length() > 6 || !n.chars().allMatch(Character::isDigit)) {
                        return null;
                    }
                    return new Request(type, session, id, null, null, false, Integer.parseInt(n));
                }
                case "call" -> {
                    long id = id(m.get("id"));
                    String method = text(m.get("m"), MAX_METHOD_CHARS);
                    if (id <= 0 || method == null || method.isEmpty()) {
                        return null;
                    }
                    String params = m.get("p");
                    return new Request(type, session, id, method, params == null ? "{}" : params, "true".equals(m.get("st")), 0);
                }
                default -> {
                    return null;
                }
            }
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** The session and id of a message too long to be worth parsing whole; {@code null} if it does not start like a call. */
    public static Request peekCall(String message) {
        // The page script always writes "t", "s" and "id" first, before the parameters.
        int end = message.indexOf(",\"m\":", PREFIX.length());
        if (end < 0 || end > PREFIX.length() + 64 + MAX_SESSION_CHARS * 6) {
            return null;
        }
        Request head = parse(message.substring(0, end) + ",\"m\":\"x\"}");
        return head == null || !head.type().equals("call") ? null : head;
    }

    private static String text(String raw, int maxChars) {
        if (raw == null || raw.isEmpty() || raw.charAt(0) != '"') {
            return null;
        }
        String value = MiniJson.decodeString(raw);
        return value.length() <= maxChars ? value : null;
    }

    private static long id(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 16) {
            return -1;
        }
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) < '0' || raw.charAt(i) > '9') {
                return -1;
            }
        }
        long id = Long.parseLong(raw);
        return id <= MAX_ID ? id : -1;
    }

    // ---------------------------------------------------------------- Java to page

    public static String result(String session, long id, String json) {
        return "{\"t\":\"result\",\"s\":" + BasicJson.quote(session) + ",\"id\":" + id + ",\"r\":" + json + "}";
    }

    /** {@code dataJson} is JSON text or {@code null} for none. */
    public static String error(String session, long id, String code, String message, String dataJson) {
        return "{\"t\":\"error\",\"s\":" + BasicJson.quote(session) + ",\"id\":" + id + ",\"e\":{\"code\":" + BasicJson.quote(code)
                + ",\"message\":" + BasicJson.quote(message == null ? code : message) + ",\"data\":" + (dataJson == null ? "null" : dataJson) + "}}";
    }

    public static String progress(String session, long id, String json) {
        return "{\"t\":\"progress\",\"s\":" + BasicJson.quote(session) + ",\"id\":" + id + ",\"d\":" + json + "}";
    }

    public static String item(String session, long id, String json) {
        return "{\"t\":\"item\",\"s\":" + BasicJson.quote(session) + ",\"id\":" + id + ",\"d\":" + json + "}";
    }

    public static String end(String session, long id) {
        return "{\"t\":\"end\",\"s\":" + BasicJson.quote(session) + ",\"id\":" + id + "}";
    }

    public static String event(String name, String json) {
        return "{\"t\":\"event\",\"n\":" + BasicJson.quote(name) + ",\"d\":" + json + "}";
    }

    /** The script that hands {@code json} (one of the messages above) to the page. Harmless in a page without the RPC script. */
    public static String deliverScript(String json) {
        return "window.__joltRpc&&window.__joltRpc.receive(" + JsLiteral.quote(json) + ");";
    }

    /**
     * The page half: {@code window.__joltRpc}, which {@code jolt.call} and {@code jolt.onEvent} use. Only the top-level document gets
     * it; answers are delivered there.
     */
    public static String script() {
        return """
                (() => {
                  if (window.__joltRpc || window.top !== window || !window.jolt) return;
                  const PREFIX = '\\u0001jolt-rpc:';
                  const randomId = () => {
                    if (crypto.randomUUID) return crypto.randomUUID();
                    const b = crypto.getRandomValues(new Uint8Array(16));
                    return Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
                  };
                  const session = randomId();
                  const post = (text) => window.jolt.postMessage(PREFIX + text);
                  const S = JSON.stringify(session);
                  const pending = new Map();
                  const listeners = new Map();
                  let nextId = 1;
                  class JoltRpcError extends Error {
                    constructor(code, message, data) {
                      super(message);
                      this.name = 'JoltRpcError';
                      this.code = code;
                      this.data = data === undefined ? null : data;
                    }
                  }
                  const take = (id) => {
                    const p = pending.get(id);
                    if (!p) return null;
                    pending.delete(id);
                    p.cleanup();
                    return p;
                  };
                  const call = (method, params, options) => new Promise((resolve, reject) => {
                    if (typeof method !== 'string' || method === '') {
                      throw new JoltRpcError('bad_request', 'jolt.call needs a method name');
                    }
                    options = options || {};
                    const signal = options.signal;
                    if (signal && signal.aborted) {
                      reject(signal.reason !== undefined ? signal.reason : new DOMException('The call was aborted', 'AbortError'));
                      return;
                    }
                    let json = JSON.stringify(params === undefined ? {} : params);
                    if (json === undefined) json = 'null';
                    const id = nextId++;
                    let timer = null;
                    let onAbort = null;
                    pending.set(id, {
                      resolve, reject, method,
                      onProgress: typeof options.onProgress === 'function' ? options.onProgress : null,
                      cleanup: () => {
                        if (timer !== null) clearTimeout(timer);
                        if (onAbort) signal.removeEventListener('abort', onAbort);
                      },
                    });
                    const giveUp = (error) => {
                      if (!take(id)) return;
                      post('{"t":"cancel","s":' + S + ',"id":' + id + '}');
                      reject(error);
                    };
                    if (signal) {
                      onAbort = () => giveUp(signal.reason !== undefined ? signal.reason : new DOMException('The call was aborted', 'AbortError'));
                      signal.addEventListener('abort', onAbort, { once: true });
                    }
                    const timeout = Number(options.timeout);
                    if (timeout > 0) {
                      timer = setTimeout(() => giveUp(new JoltRpcError('timeout', method + ' did not answer within ' + timeout + ' ms')), timeout);
                    }
                    try {
                      post('{"t":"call","s":' + S + ',"id":' + id + ',"m":' + JSON.stringify(method) + ',"p":' + json + '}');
                    } catch (e) {
                      take(id);
                      reject(e);
                    }
                  });
                  // An async iterator over the items of a streaming method. It starts when first read, and asks Java for more items as it
                  // consumes them (Java sends only that many ahead). Breaking out of a for-await loop, or aborting the signal, stops it.
                  const stream = (method, params, options) => {
                    if (typeof method !== 'string' || method === '') {
                      throw new JoltRpcError('bad_request', 'jolt.stream needs a method name');
                    }
                    options = options || {};
                    const signal = options.signal;
                    let json = JSON.stringify(params === undefined ? {} : params);
                    if (json === undefined) json = 'null';
                    const queue = [];
                    const waiters = [];
                    let id = 0;
                    let started = false;
                    let finished = false;
                    let failure = null;
                    let consumed = 0;
                    let onAbort = null;
                    const ack = () => {
                      if (started && !finished && consumed > 0 && (queue.length === 0 || consumed >= 16)) {
                        const n = consumed;
                        consumed = 0;
                        try { post('{"t":"more","s":' + S + ',"id":' + id + ',"n":' + n + '}'); } catch (e) { /* the call is ending anyway */ }
                      }
                    };
                    const wake = () => {
                      while (waiters.length) {
                        const w = waiters[0];
                        if (queue.length) { waiters.shift(); consumed++; w.resolve({ value: queue.shift(), done: false }); }
                        else if (failure) { waiters.shift(); const f = failure; failure = null; w.reject(f); }
                        else if (finished) { waiters.shift(); w.resolve({ value: undefined, done: true }); }
                        else break;
                      }
                      ack();
                    };
                    const stop = (error) => {
                      if (started && take(id)) {
                        try { post('{"t":"cancel","s":' + S + ',"id":' + id + '}'); } catch (e) { /* nothing to tell */ }
                      }
                      finished = true;
                      queue.length = 0;
                      if (error !== undefined) failure = error;
                      wake();
                    };
                    const entry = {
                      resolve: () => {},
                      reject: (error) => { failure = error; finished = true; wake(); },
                      push: (item) => { queue.push(item); wake(); },
                      end: () => { finished = true; wake(); },
                      onProgress: null,
                      cleanup: () => { if (onAbort) signal.removeEventListener('abort', onAbort); },
                    };
                    const reasonOf = () => signal.reason !== undefined ? signal.reason : new DOMException('The stream was aborted', 'AbortError');
                    if (signal && signal.aborted) { finished = true; failure = reasonOf(); }
                    const start = () => {
                      if (started || finished) return;
                      started = true;
                      id = nextId++;
                      pending.set(id, entry);
                      if (signal) {
                        onAbort = () => stop(reasonOf());
                        signal.addEventListener('abort', onAbort, { once: true });
                      }
                      try {
                        post('{"t":"call","s":' + S + ',"id":' + id + ',"st":true,"m":' + JSON.stringify(method) + ',"p":' + json + '}');
                      } catch (e) {
                        take(id);
                        finished = true;
                        failure = e;
                      }
                    };
                    const iterator = {
                      next() {
                        start();
                        return new Promise((resolve, reject) => { waiters.push({ resolve, reject }); wake(); });
                      },
                      return() {
                        stop();
                        return Promise.resolve({ value: undefined, done: true });
                      },
                      [Symbol.asyncIterator]() { return iterator; },
                    };
                    return iterator;
                  };
                  const onEvent = (name, callback) => {
                    if (typeof callback !== 'function') throw new TypeError('jolt.onEvent needs a callback');
                    name = String(name);
                    let set = listeners.get(name);
                    if (!set) { set = new Set(); listeners.set(name, set); }
                    const entry = (data) => callback(data);
                    set.add(entry);
                    return () => { set.delete(entry); };
                  };
                  const receive = (text) => {
                    let m;
                    try { m = JSON.parse(text); } catch (e) { return; }
                    if (!m || typeof m !== 'object') return;
                    if (m.t === 'event') {
                      const set = listeners.get(m.n);
                      if (set) for (const cb of [...set]) { try { cb(m.d); } catch (e) { console.error(e); } }
                      return;
                    }
                    if (m.s !== session) return;
                    if (m.t === 'progress') {
                      const p = pending.get(m.id);
                      if (p && p.onProgress) { try { p.onProgress(m.d); } catch (e) { console.error(e); } }
                      return;
                    }
                    if (m.t === 'item') {
                      const p = pending.get(m.id);
                      if (p && p.push) p.push(m.d);
                      return;
                    }
                    if (m.t === 'end') {
                      const p = take(m.id);
                      if (p && p.end) p.end();
                      return;
                    }
                    const p = take(m.id);
                    if (!p) return;
                    if (m.t === 'result') p.resolve(m.r);
                    else { const e = m.e || {}; p.reject(new JoltRpcError(String(e.code || 'internal'), String(e.message || 'the call failed'), e.data)); }
                  };
                  Object.defineProperty(window, '__joltRpc', { value: Object.freeze({ call, stream, onEvent, receive }) });
                  try { post('{"t":"hello","s":' + S + '}'); } catch (e) { /* the first call says it again */ }
                })();
                """;
    }
}
