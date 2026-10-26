// The typed bridge to the Java side. This is the only file that knows about window.jolt.
//
// Jolt injects `window.jolt` into every page it shows. It carries plain strings in both directions; the request/response
// protocol on top ({id, method, params} -> {id, result | error}) is implemented by Rpc.java, and `call` below is the
// page half of it.

declare global {
  interface Window {
    jolt?: {
      /** Sends a string to Java. */
      postMessage(message: string): void;
      /** Receives strings from Java. Returns a function that removes the listener. */
      onMessage(callback: (message: string) => void): () => void;
    };
    /** Set by the Java side when it is running its automatic self-test. */
    __SMOKE__?: boolean;
  }
}

interface Reply {
  id: number;
  result?: unknown;
  error?: string;
}

interface Waiter {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
}

const waiting = new Map<number, Waiter>();
let nextId = 1;
let listening = false;

function listen(bridge: NonNullable<Window['jolt']>): void {
  if (listening) return;
  listening = true;
  bridge.onMessage((text) => {
    let reply: Reply;
    try {
      reply = JSON.parse(text) as Reply;
    } catch {
      return; // not an RPC reply
    }
    const waiter = waiting.get(reply.id);
    if (!waiter) return;
    waiting.delete(reply.id);
    if (reply.error !== undefined) waiter.reject(new Error(reply.error));
    else waiter.resolve(reply.result);
  });
}

/** Calls a Java handler registered with `Rpc.on(method, ...)` and resolves with its result. */
export function call<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
  const bridge = window.jolt;
  if (!bridge) {
    return Promise.reject(new Error('This page is not running inside a Jolt window, so there is no Java side to call.'));
  }
  listen(bridge);
  return new Promise<T>((resolve, reject) => {
    const id = nextId++;
    waiting.set(id, { resolve: resolve as (value: unknown) => void, reject });
    bridge.postMessage(JSON.stringify({ id, method, params }));
  });
}

/** Sends a message that is not an RPC request (used by the self-test to report its verdict). */
export function notifyJava(payload: Record<string, unknown>): void {
  window.jolt?.postMessage(JSON.stringify(payload));
}
