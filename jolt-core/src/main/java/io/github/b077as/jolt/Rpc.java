package io.github.b077as.jolt;

import io.github.b077as.jolt.internal.PageRpc;
import io.github.b077as.jolt.internal.RpcProtocol;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;

/**
 * Lets the page call Java methods and get their results, and lets Java push events to the page: request/response on top of the
 * string messages, so an app does not have to build its own.
 *
 * <pre>{@code
 * record NewNote(String text, List<String> tags) {}
 *
 * Rpc rpc = Rpc.create()
 *         .on("notes.list", call -> store.list())                         // no parameters
 *         .on("notes.add", NewNote.class, note -> store.add(note.text(), note.tags()))
 *         .on("export", ExportRequest.class, (request, call) -> {          // long work: progress and cancellation
 *             for (Page page : request.pages()) {
 *                 call.checkCancelled();
 *                 write(page);
 *                 call.progress(page.number());
 *             }
 *             return "done";
 *         });
 * rpc.attach(window);                                                     // before the page loads
 * rpc.emit("notes.changed", store.count());                               // to every attached window
 * }</pre>
 * <pre>{@code
 * const notes = await jolt.call("notes.list");
 * await jolt.call("notes.add", { text: "Buy milk", tags: ["home"] });
 * const result = await jolt.call("export", { pages }, { timeout: 60_000, signal: controller.signal, onProgress: (n) => bar.value = n });
 * const stop = jolt.onEvent("notes.changed", (count) => badge.textContent = count);
 * }</pre>
 *
 * <p><b>Threads.</b> Handlers run on worker threads (a virtual thread per call unless {@link RpcOptions#executor()} says otherwise),
 * never on the UI thread, so they may block; several calls run at the same time. {@link #emit} works from any thread.
 *
 * <p><b>JSON.</b> Parameters are read and results written by the {@link JsonCodec} in {@link RpcOptions#codec()}: the built-in
 * {@link JsonCodec#basic()} (records, enums, collections, maps), or Gson, Jackson or any other library you plug in.
 *
 * <p><b>Errors.</b> An {@link RpcException} reaches the page with its code, message and data; any other exception only as
 * {@link RpcException#INTERNAL} with a generic message, and it is logged and given to {@link #onError}.
 *
 * <p><b>Security.</b> Only pages whose origin the window's {@link SecurityPolicy} trusts can call, whatever
 * {@link SecurityPolicy#restrictMessages()} says, and only from the top-level document. A window takes at most
 * {@link RpcOptions#maxConcurrentCalls()} calls at once and requests up to {@link RpcOptions#maxMessageChars()}. Everything a page
 * sends is untrusted input: check it in the handler as you would a web request.
 *
 * <p><b>Lifetime.</b> When the page reloads or navigates, the calls it left running are cancelled and their answers dropped; the same
 * happens when the window closes or is {@link #detach detached}. One {@code Rpc} can serve several windows (all of an app's pop-outs,
 * say); {@link RpcCall#window()} tells a handler which one called. A window can be attached to one {@code Rpc} at a time.
 *
 * <p>RPC messages never reach {@link WebWindow#onMessage} or the page's {@code jolt.onMessage}: both stay free for the app's own use.
 */
public final class Rpc {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.rpc");

    private final RpcOptions options;
    private final Executor executor;
    private final Map<String, RpcHandler> handlers = new ConcurrentHashMap<>();
    private final Map<WebWindow, Peer> peers = new IdentityHashMap<>();
    private volatile BiConsumer<RpcCall, Throwable> errorListener;

    private Rpc(RpcOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.executor = options.executor() != null ? options.executor()
                : command -> Thread.ofVirtual().name("jolt-rpc").start(command);
    }

    /** An {@code Rpc} with {@link RpcOptions#defaults()}: the built-in JSON codec, virtual threads. */
    public static Rpc create() {
        return new Rpc(RpcOptions.defaults());
    }

    public static Rpc create(RpcOptions options) {
        return new Rpc(options);
    }

    public RpcOptions options() {
        return options;
    }

    /**
     * Answers {@code method} with {@code handler}, replacing any earlier one. Handlers can be added and replaced at any time, also while
     * windows are attached.
     *
     * @param method a name of 1 to {@value RpcProtocol#MAX_METHOD_CHARS} characters, such as {@code "notes.add"}
     */
    public Rpc on(String method, RpcHandler handler) {
        checkName(method, "method");
        handlers.put(method, Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /** Answers {@code method}, reading the parameters as {@code type} first (a bad shape is answered with {@link RpcException#BAD_PARAMS}). */
    public <P> Rpc on(String method, Class<P> type, RpcHandler.WithParams<P> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        return on(method, call -> handler.handle(call.params(type)));
    }

    /** Like {@link #on(String, Class, RpcHandler.WithParams)}, with the {@link RpcCall} for progress, cancellation or the window. */
    public <P> Rpc on(String method, Class<P> type, RpcHandler.WithParamsAndCall<P> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        return on(method, call -> handler.handle(call.params(type), call));
    }

    /** Stops answering {@code method}; the page then gets {@link RpcException#UNKNOWN_METHOD}. */
    public Rpc off(String method) {
        handlers.remove(method);
        return this;
    }

    /**
     * Called with every unexpected exception a handler throws (not {@link RpcException}s, not cancellations), on the handler's thread,
     * for example to report it. Such exceptions are logged in any case. {@code null} removes the listener.
     */
    public Rpc onError(BiConsumer<RpcCall, Throwable> listener) {
        this.errorListener = listener;
        return this;
    }

    /**
     * Lets {@code window}'s pages call this {@code Rpc} with {@code jolt.call} and receive its events with {@code jolt.onEvent}. Attach
     * before the page loads (a page already showing gets it too, but may have tried to call before). Attaching the same window again
     * does nothing.
     *
     * @throws IllegalStateException if the window is attached to another {@code Rpc}
     */
    public Rpc attach(WebWindow window) {
        Objects.requireNonNull(window, "window");
        Peer peer;
        synchronized (peers) {
            if (peers.containsKey(window)) {
                return this;
            }
            peer = new Peer(window);
            PageRpc.register(window, peer);
            peers.put(window, peer);
        }
        window.onClosed(() -> {
            synchronized (peers) {
                peers.remove(window, peer);
            }
            PageRpc.unregister(window, peer);
            peer.stop(false);
        });
        String script = RpcProtocol.script();
        window.addInitScript(script);
        window.eval(script).exceptionally(t -> null);
        return this;
    }

    /**
     * Stops serving {@code window}: its running calls are cancelled and the page is told {@link RpcException#UNAVAILABLE}, as it is for
     * calls it makes from now on. The window can be attached again later, to this or another {@code Rpc}.
     */
    public Rpc detach(WebWindow window) {
        Peer peer;
        synchronized (peers) {
            peer = peers.remove(window);
        }
        if (peer != null) {
            PageRpc.unregister(window, peer);
            peer.stop(true);
        }
        return this;
    }

    /** The windows attached now. */
    public List<WebWindow> windows() {
        synchronized (peers) {
            return List.copyOf(peers.keySet());
        }
    }

    /**
     * Sends event {@code name} with {@code data} to the pages of every attached window; they receive it with
     * {@code jolt.onEvent(name, callback)}. A page that is not loaded yet, or has no listener for it, misses it. Any thread.
     *
     * @throws IllegalArgumentException if the codec cannot write {@code data}
     */
    public void emit(String event, Object data) {
        String script = eventScript(event, data);
        for (Peer peer : snapshot()) {
            peer.sendScript(script);
        }
    }

    /**
     * Like {@link #emit(String, Object)} to one window.
     *
     * @throws IllegalStateException if {@code window} is not attached
     */
    public void emit(WebWindow window, String event, Object data) {
        Peer peer;
        synchronized (peers) {
            peer = peers.get(window);
        }
        if (peer == null) {
            throw new IllegalStateException("the window is not attached to this Rpc");
        }
        peer.sendScript(eventScript(event, data));
    }

    private String eventScript(String event, Object data) {
        checkName(event, "event");
        return RpcProtocol.deliverScript(RpcProtocol.event(event, encode(data)));
    }

    private List<Peer> snapshot() {
        synchronized (peers) {
            return new ArrayList<>(peers.values());
        }
    }

    private String encode(Object value) {
        String json;
        try {
            json = options.codec().encode(value);
        } catch (RuntimeException e) {
            throw e instanceof IllegalArgumentException ? e : new IllegalArgumentException("the JsonCodec could not write the value", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("the JsonCodec could not write the value", e);
        }
        if (json == null || json.isBlank()) {
            return "null";
        }
        return json;
    }

    private static void checkName(String name, String what) {
        if (name == null || name.isEmpty() || name.length() > RpcProtocol.MAX_METHOD_CHARS) {
            throw new IllegalArgumentException("an RPC " + what + " name is 1 to " + RpcProtocol.MAX_METHOD_CHARS + " characters: " + name);
        }
    }

    // ---------------------------------------------------------------- one window

    private final class Peer implements PageRpc.Receiver {

        private final WebWindow window;
        private final Map<String, Running> running = new ConcurrentHashMap<>();
        /** The session of the newest document that spoke; calls of other sessions are cancelled. UI thread only. */
        private String session;
        private volatile boolean stopped;

        Peer(WebWindow window) {
            this.window = window;
        }

        @Override
        public void receive(String message, String sourceUrl) {
            if (stopped) {
                return;
            }
            if (!window.security().trusts(sourceUrl)) {
                LOG.log(System.Logger.Level.WARNING, "RPC message from " + sourceUrl + " ignored: its origin is not trusted. Add it with "
                        + "SecurityPolicy.withTrustedOrigins(...)");
                return;
            }
            if (message.length() > options.maxMessageChars() + RpcProtocol.PREFIX.length()) {
                RpcProtocol.Request head = RpcProtocol.peekCall(message);
                if (head != null) {
                    switchSession(head.session());
                    send(RpcProtocol.error(head.session(), head.id(), RpcException.TOO_LARGE,
                            "The request is longer than " + options.maxMessageChars() + " characters", null));
                }
                return;
            }
            RpcProtocol.Request request = RpcProtocol.parse(message);
            if (request == null) {
                return;
            }
            switchSession(request.session());
            switch (request.type()) {
                case "call" -> start(request);
                case "cancel" -> {
                    Running run = running.get(key(request.session(), request.id()));
                    if (run != null) {
                        run.cancel();
                    }
                }
                default -> { } // hello: the session switch was all
            }
        }

        private void switchSession(String newSession) {
            if (newSession.equals(session)) {
                return;
            }
            session = newSession;
            // Cancelled calls stay counted until their thread really ends, so a page cannot get past maxConcurrentCalls by
            // starting and abandoning calls whose handlers ignore the interrupt.
            for (Running run : running.values()) {
                if (!run.session.equals(newSession)) {
                    run.cancel();
                }
            }
        }

        private void start(RpcProtocol.Request request) {
            String key = key(request.session(), request.id());
            if (running.size() >= options.maxConcurrentCalls()) {
                send(RpcProtocol.error(request.session(), request.id(), RpcException.TOO_MANY_CALLS,
                        "More than " + options.maxConcurrentCalls() + " calls at once", null));
                return;
            }
            RpcHandler handler = handlers.get(request.method());
            if (handler == null) {
                send(RpcProtocol.error(request.session(), request.id(), RpcException.UNKNOWN_METHOD,
                        "There is no method named " + request.method(), null));
                return;
            }
            Running run = new Running(this, request, handler, key);
            if (running.putIfAbsent(key, run) != null) {
                return; // the same id twice from one document: not from the page script, ignore it
            }
            try {
                executor.execute(run);
            } catch (RejectedExecutionException e) {
                running.remove(key, run);
                LOG.log(System.Logger.Level.WARNING, "the RPC executor refused " + request.method(), e);
                send(RpcProtocol.error(request.session(), request.id(), RpcException.INTERNAL, "The call could not be started", null));
            }
        }

        void stop(boolean tellPage) {
            stopped = true;
            for (Running run : new ArrayList<>(running.values())) {
                run.cancel();
                if (tellPage) {
                    send(RpcProtocol.error(run.session, run.id, RpcException.UNAVAILABLE, "The window was detached from its Rpc", null));
                }
            }
        }

        /** Hands one protocol message to the page. Any thread. */
        void send(String json) {
            sendScript(RpcProtocol.deliverScript(json));
        }

        void sendScript(String script) {
            window.eval(script).exceptionally(t -> null); // a closed window: nobody is waiting any more
        }

        private static String key(String session, long id) {
            return session + "#" + id;
        }
    }

    // ---------------------------------------------------------------- one call

    private final class Running implements Runnable, RpcCall {

        private final Peer peer;
        private final String session;
        private final long id;
        private final String method;
        private final String paramsJson;
        private final RpcHandler handler;
        private final String key;
        private volatile boolean cancelled;
        private volatile boolean done;
        private Thread runner;                 // guarded by this
        private CompletableFuture<?> awaiting; // guarded by this

        Running(Peer peer, RpcProtocol.Request request, RpcHandler handler, String key) {
            this.peer = peer;
            this.session = request.session();
            this.id = request.id();
            this.method = request.method();
            this.paramsJson = request.params();
            this.handler = handler;
            this.key = key;
        }

        @Override
        public void run() {
            synchronized (this) {
                if (cancelled) {
                    done = true;
                    peer.running.remove(key, this);
                    return;
                }
                runner = Thread.currentThread();
            }
            try {
                Object result = handler.handle(this);
                if (result instanceof CompletionStage<?> stage) {
                    CompletableFuture<?> future = stage.toCompletableFuture();
                    synchronized (this) {
                        awaiting = future;
                    }
                    if (cancelled) {
                        future.cancel(true);
                    }
                    result = future.get();
                }
                String json = encode(result);
                finish(RpcProtocol.result(session, id, json));
            } catch (Throwable thrown) {
                fail(thrown);
            } finally {
                synchronized (this) {
                    runner = null;
                    awaiting = null;
                }
                Thread.interrupted(); // a cancellation's interrupt must not leak into the executor's next task
                done = true;
                peer.running.remove(key, this);
            }
        }

        /** Sends the answer, unless the page gave up; the call stops counting against the window's limit first. */
        private void finish(String answer) {
            done = true;
            peer.running.remove(key, this);
            if (!cancelled && !peer.stopped) {
                peer.send(answer);
            }
        }

        private void fail(Throwable thrown) {
            Throwable error = thrown;
            while ((error instanceof ExecutionException || error instanceof CompletionException) && error.getCause() != null) {
                error = error.getCause();
            }
            if (cancelled) {
                done = true;
                peer.running.remove(key, this);
                return; // the page gave up; how the handler ended does not matter
            }
            if (error instanceof RpcException rpc) {
                String data = null;
                if (rpc.data() != null) {
                    try {
                        data = encode(rpc.data());
                    } catch (IllegalArgumentException e) {
                        LOG.log(System.Logger.Level.WARNING, method + ": the data of an RpcException could not be written", e);
                    }
                }
                finish(RpcProtocol.error(session, id, rpc.code(), rpc.getMessage(), data));
                return;
            }
            LOG.log(System.Logger.Level.WARNING, "RPC method " + method + " failed", error);
            BiConsumer<RpcCall, Throwable> listener = errorListener;
            if (listener != null) {
                try {
                    listener.accept(this, error);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "the Rpc onError listener failed", e);
                }
            }
            String message = options.exposeErrorMessages()
                    ? (error.getMessage() != null ? error.getClass().getSimpleName() + ": " + error.getMessage() : error.toString())
                    : "The call to " + method + " failed";
            finish(RpcProtocol.error(session, id, RpcException.INTERNAL, message, null));
            if (error instanceof VirtualMachineError vm && !(vm instanceof StackOverflowError)) {
                throw vm; // out of memory or worse: answered, but not swallowed
            }
        }

        void cancel() {
            synchronized (this) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                if (runner != null) {
                    runner.interrupt();
                }
                if (awaiting != null) {
                    awaiting.cancel(true);
                }
            }
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public WebWindow window() {
            return peer.window;
        }

        @Override
        public String paramsJson() {
            return paramsJson;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T params(Class<T> type) {
            return (T) params((Type) type);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T params(Type type) {
            Objects.requireNonNull(type, "type");
            try {
                return (T) options.codec().decode(paramsJson, type);
            } catch (Exception e) {
                LOG.log(System.Logger.Level.INFO, "RPC method " + method + ": the parameters could not be read as " + type.getTypeName()
                        + ": " + e.getMessage());
                String message = options.exposeErrorMessages() && e.getMessage() != null
                        ? "Invalid parameters for " + method + ": " + e.getMessage()
                        : "Invalid parameters for " + method;
                throw new RpcException(RpcException.BAD_PARAMS, message, null, e);
            }
        }

        @Override
        public void progress(Object data) {
            if (cancelled || done || peer.stopped) {
                return;
            }
            peer.send(RpcProtocol.progress(session, id, encode(data)));
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public String toString() {
            return "RpcCall[" + method + " #" + id + "]";
        }
    }
}
