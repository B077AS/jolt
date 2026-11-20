package io.github.b077as.jolt;

import io.github.b077as.jolt.internal.PageRpc;
import io.github.b077as.jolt.internal.RpcControllers;
import io.github.b077as.jolt.internal.RpcProtocol;
import io.github.b077as.jolt.internal.TypeRouted;
import io.github.b077as.jolt.internal.TypeScript;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <p><b>Errors.</b> An {@link RpcException} reaches the page with its code, message and data. Any other exception reaches it only as
 * {@link RpcException#INTERNAL} with a generic message, and is logged and given to {@link #onError}, because it is a bug. An app whose
 * messages are meant for the user (a database client shows the database's complaint) says which exception types are expected, and what
 * the page is told about each, with {@link #onException}; those are not logged as warnings.
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
    /** A handler and what is known of the types it takes and returns (for {@link #typeScript()}); {@code null} where unknown. */
    private record Registered(RpcHandler handler, Type params, Type result) {
    }

    private final Map<String, Registered> handlers = new ConcurrentHashMap<>();
    private final Map<WebWindow, Peer> peers = new IdentityHashMap<>();
    private final TypeRouted<RpcExceptionHandler<?>> exceptionHandlers = new TypeRouted<>();
    private final List<RpcInterceptor> interceptors = new CopyOnWriteArrayList<>();
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
        return on(method, null, null, handler);
    }

    /**
     * Like {@link #on(String, RpcHandler)}, saying what the handler takes and returns. The types change nothing at run time: they are
     * what {@link #typeScript()} writes for the method. {@code params} is the type the handler reads the parameters as ({@code void.class}
     * for none, {@code null} if not said); {@code result} is what it returns ({@code null} if not said). A result of {@code Flow.Publisher}
     * or {@link RpcStream} (generic, or the raw class for items of unknown type) marks a streaming method.
     */
    public Rpc on(String method, Type params, Type result, RpcHandler handler) {
        checkName(method, "method");
        handlers.put(method, new Registered(Objects.requireNonNull(handler, "handler"), params, result));
        return this;
    }

    /** Answers {@code method}, reading the parameters as {@code type} first (a bad shape is answered with {@link RpcException#BAD_PARAMS}). */
    public <P> Rpc on(String method, Class<P> type, RpcHandler.WithParams<P> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        return on(method, type, null, call -> handler.handle(call.params(type)));
    }

    /** Like {@link #on(String, Class, RpcHandler.WithParams)}, with the {@link RpcCall} for progress, cancellation or the window. */
    public <P> Rpc on(String method, Class<P> type, RpcHandler.WithParamsAndCall<P> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        return on(method, type, null, call -> handler.handle(call.params(type), call));
    }

    /**
     * Registers the {@link JoltRpc} methods of {@code controller} as handlers (named by the annotation, with the class's prefix), and
     * its {@link JoltRpcExceptionHandler} methods as {@link #onException} handlers:
     *
     * <pre>{@code
     * rpc.register(new NotesService(store));      // "notes.list", "notes.add", ... from @JoltRpc methods
     * }</pre>
     * <b>This is not the preferred way</b>; {@link #on(String, RpcHandler)} is. The methods are found by reflection when this is called,
     * so a mistake (a repeated name, a method of the wrong shape) is reported at that point instead of by the compiler, and reflection
     * needs extra configuration for native images and for trimmed module sets. It earns its place when there is a great deal to
     * register: one class per area of the app, with its error handling beside it, instead of a long list of {@code on} calls. See
     * {@link JoltRpc} for what a method may take and return.
     *
     * <p>A name that already has a handler is an error (unlike {@code on}, which replaces), and so is a controller without any
     * annotated method. Nothing is registered if the controller is refused. {@link JoltRpcExceptionHandler} methods replace earlier
     * handlers for the same exception type, as {@code onException} does.
     *
     * @throws IllegalStateException if the controller has no annotated methods, a method has the wrong shape or a reserved ({@code jolt.})
     *                               or repeated name, or a name is already taken
     */
    public Rpc register(Object controller) {
        Objects.requireNonNull(controller, "controller");
        RpcControllers.Scan scan = RpcControllers.scan(controller);
        for (String name : scan.methods().keySet()) {
            checkName(name, "method");
            if (handlers.containsKey(name)) {
                throw new IllegalStateException("Rpc already has a handler for '" + name + "', which " + controller.getClass().getName()
                        + " registers with @JoltRpc");
            }
        }
        scan.methods().forEach((name, route) -> handlers.put(name, new Registered(route.handler(), route.params(), route.result())));
        scan.exceptionHandlers().forEach(exceptionHandlers::put);
        return this;
    }

    /**
     * Runs {@code interceptor} around every call to a method that exists: it decides whether, and how, the rest of the chain runs. The
     * interceptors run in the order they were registered, the first one outermost. See {@link RpcInterceptor}.
     */
    public Rpc around(RpcInterceptor interceptor) {
        interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
        return this;
    }

    /**
     * Runs {@code before} before every call to a method that exists, in the order interceptors were registered. Throw to stop the call:
     * an {@link RpcException} tells the page why (a sign-in check throws {@code new RpcException("unauthorized", ...)}).
     */
    public Rpc before(RpcInterceptor.Before before) {
        Objects.requireNonNull(before, "before");
        return around((call, next) -> {
            before.before(call);
            return next.handle(call);
        });
    }

    /** Stops answering {@code method}; the page then gets {@link RpcException#UNKNOWN_METHOD}. */
    public Rpc off(String method) {
        handlers.remove(method);
        return this;
    }

    /**
     * Says what the page is told when a handler throws an exception of {@code type} (or a subclass), replacing any earlier handler for
     * the same type. When several types match, the most specific one wins, as with {@code catch} clauses and Spring's
     * {@code @ExceptionHandler}; {@code Throwable.class} catches everything the others do not.
     *
     * <pre>{@code
     * rpc.onException(SQLException.class, (call, e) -> new RpcException("sql", e.getMessage()))
     *    .onException(IllegalArgumentException.class, (call, e) -> RpcException.badParams(e.getMessage()));
     * }</pre>
     * An exception with a handler is an expected failure: it is logged only at {@code DEBUG} and is not given to {@link #onError},
     * unless the handler answers with {@link RpcException#INTERNAL} (as {@link RpcException#exposing} does). An exception without one
     * reaches the page as a generic {@code internal} error and is logged as a warning.
     *
     * <p>The handler runs on the call's thread, after the exception was thrown. It gets the exception unwrapped from
     * {@code ExecutionException} / {@code CompletionException}, and may be an {@link Error} such as a {@code LinkageError}. It is never
     * asked about an {@link RpcException} (that already says what the page is told), a cancelled call, or a {@link VirtualMachineError}.
     * If it throws or returns {@code null}, that is logged and the exception is treated as if it had no handler. The message the page
     * gets is cut to {@value RpcException#MAX_MESSAGE_CHARS} characters.
     *
     * <p>Handlers can be added and replaced at any time.
     *
     * @throws IllegalArgumentException if {@code type} is {@link RpcException} or a subclass
     */
    public <E extends Throwable> Rpc onException(Class<E> type, RpcExceptionHandler<? super E> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        if (RpcException.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("an RpcException already says what the page is told; there is no handler for " + type.getName());
        }
        exceptionHandlers.put(type, handler);
        return this;
    }

    /** The handler of the most specific superclass of {@code error} (itself included) that has one, or {@code null}. */
    @SuppressWarnings("unchecked")
    private RpcExceptionHandler<Throwable> exceptionHandlerFor(Throwable error) {
        return (RpcExceptionHandler<Throwable>) exceptionHandlers.find(error);
    }

    /**
     * Called with every unexpected exception a handler throws, on the handler's thread, for example to report it: one that has no
     * {@link #onException} handler, or whose handler answers {@link RpcException#INTERNAL}. Not {@link RpcException}s, not cancellations,
     * and not the failures the app handles on purpose. Such exceptions are logged in any case. {@code null} removes the listener.
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

    /**
     * TypeScript declarations for the methods registered now: an interface for every record, a type for every enum, and a typed
     * {@code window.jolt}, so that {@code jolt.call("notes.add", { text: "x" })} is checked against the Java types:
     *
     * <pre>{@code
     * rpc.register(new NotesService(store));
     * rpc.writeTypeScript(Path.of("frontend/src/jolt-api.d.ts"));     // at startup, while developing
     * }</pre>
     * The types come from the declarations of {@link #register(Object) registered} methods (parameter and return type, a
     * {@code CompletionStage} unwrapped, a {@code Flow.Publisher<T>} or {@code RpcStream<T>} result as a stream), and from what
     * {@link #on(String, Type, Type, RpcHandler)} is told. A method registered with a plain lambda is typed {@code unknown}. The shapes are
     * those of {@link JsonCodec#basic()}: another JSON library that renames properties or writes dates differently needs declarations
     * of its own. A record component that can be null is written {@code name?: T | null} unless it is primitive or annotated
     * {@code @NonNull} (any annotation of that name). Names under {@code jolt.} are left out.
     *
     * <p>The file declares {@code Window.jolt}: use it instead of declaring that yourself.
     */
    public String typeScript() {
        List<TypeScript.Endpoint> endpoints = new ArrayList<>();
        handlers.forEach((name, registered) -> {
            if (!name.startsWith(RpcControllers.RESERVED_PREFIX)) {
                endpoints.add(new TypeScript.Endpoint(name, registered.params(), registered.result()));
            }
        });
        return TypeScript.generate(endpoints);
    }

    /**
     * Writes {@link #typeScript()} to {@code file} (UTF-8, creating the folders), unless the file already has exactly that text, so a
     * dev server watching it is not restarted for nothing.
     *
     * @throws java.io.UncheckedIOException if the file cannot be written
     */
    public Rpc writeTypeScript(java.nio.file.Path file) {
        String text = typeScript();
        try {
            if (java.nio.file.Files.exists(file) && text.equals(java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8))) {
                return this;
            }
            java.nio.file.Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
            java.nio.file.Files.writeString(file, text, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
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
                case "more" -> {
                    Running run = running.get(key(request.session(), request.id()));
                    if (run != null) {
                        run.grant(request.count());
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
            Registered registered = handlers.get(request.method());
            RpcHandler handler = registered == null ? null : registered.handler();
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
        private final boolean streamRequested;
        private final AtomicBoolean terminated = new AtomicBoolean(); // the one message that ends the call was sent
        private final AtomicInteger outstanding = new AtomicInteger(); // items a Publisher was asked for and has not delivered
        private volatile Sink<?> sink;                                  // call.stream() was called
        private volatile Flow.Subscription subscription;                // the handler returned a Publisher
        private volatile boolean pending;                               // the handler returned but the stream goes on
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
            this.streamRequested = request.stream();
        }

        @Override
        public void run() {
            synchronized (this) {
                if (cancelled) {
                    release();
                    return;
                }
                runner = Thread.currentThread();
            }
            try {
                deliver(chain().handle(this));
            } catch (Throwable thrown) {
                fail(thrown);
            } finally {
                synchronized (this) {
                    runner = null;
                    awaiting = null;
                }
                Thread.interrupted(); // a cancellation's interrupt must not leak into the executor's next task
                if (!pending) {
                    release();
                }
            }
        }

        /** The handler wrapped in the interceptors, the first one registered outermost. */
        private RpcHandler chain() {
            RpcHandler next = call -> awaited(handler.handle(call));
            RpcInterceptor[] around = interceptors.toArray(new RpcInterceptor[0]);
            for (int i = around.length - 1; i >= 0; i--) {
                RpcInterceptor interceptor = around[i];
                RpcHandler inner = next;
                next = call -> interceptor.intercept(call, inner);
            }
            return next;
        }

        /** A {@code CompletionStage} is waited for (and cancelled with the call), its failure thrown as the cause; anything else is the value. */
        private Object awaited(Object result) throws Exception {
            if (!(result instanceof CompletionStage<?> stage)) {
                return result;
            }
            CompletableFuture<?> future = stage.toCompletableFuture();
            synchronized (this) {
                awaiting = future;
            }
            if (cancelled) {
                future.cancel(true);
            }
            try {
                return future.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw e;
            }
        }

        /** What to do with what the handler (and the interceptors around it) returned. */
        private void deliver(Object result) {
            Sink<?> opened = sink;
            if (opened != null) {
                if (result == opened) {
                    pending = true; // the handler gave the stream away: it ends when someone completes it
                    if (cancelled) {
                        release();
                    }
                } else if (result instanceof Flow.Publisher) {
                    throw new IllegalStateException(method + " called stream() and also returned a Publisher");
                } else {
                    opened.complete(); // the handler filled the stream itself and is done
                }
                return;
            }
            if (result instanceof Flow.Publisher<?> publisher) {
                startPublisher(publisher);
                return;
            }
            if (streamRequested) { // a plain value for jolt.stream: a stream of one
                String json = encode(result);
                if (!cancelled && !peer.stopped) {
                    peer.send(RpcProtocol.item(session, id, json));
                }
                finish(RpcProtocol.end(session, id));
                return;
            }
            finish(RpcProtocol.result(session, id, encode(result)));
        }

        /** Forwards the items of {@code publisher}, asking it for as many as the page has room for. */
        private void startPublisher(Flow.Publisher<?> publisher) {
            if (!streamRequested) {
                throw new RpcException(RpcException.STREAM_REQUIRED, method + " is a stream: call it with jolt.stream, not jolt.call");
            }
            pending = true;
            publisher.subscribe(new Flow.Subscriber<Object>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                    boolean stop;
                    synchronized (Running.this) {
                        stop = cancelled;
                        if (!stop) {
                            subscription = s;
                        }
                    }
                    if (stop) {
                        s.cancel();
                        return;
                    }
                    int window = options.streamWindow();
                    outstanding.set(window);
                    s.request(window);
                }

                @Override
                public void onNext(Object item) {
                    outstanding.decrementAndGet();
                    if (cancelled || terminated.get()) {
                        return;
                    }
                    String json;
                    try {
                        json = encode(item);
                    } catch (IllegalArgumentException e) {
                        Flow.Subscription s = subscription;
                        if (s != null) {
                            s.cancel();
                        }
                        fail(e);
                        return;
                    }
                    peer.send(RpcProtocol.item(session, id, json));
                }

                @Override
                public void onError(Throwable error) {
                    fail(error);
                }

                @Override
                public void onComplete() {
                    finish(RpcProtocol.end(session, id));
                }
            });
        }

        /** The page consumed {@code count} items of this stream and has room for that many more. */
        void grant(int count) {
            int window = options.streamWindow();
            int n = Math.max(1, Math.min(count, window)); // a page cannot ask for more than the window
            Sink<?> opened = sink;
            if (opened != null) {
                opened.grant(n, window);
                return;
            }
            Flow.Subscription s = subscription;
            if (s != null && !cancelled) {
                int room = window - outstanding.get();
                if (room > 0) {
                    int add = Math.min(n, room);
                    outstanding.addAndGet(add);
                    s.request(add);
                }
            }
        }

        /** The call stops counting against the window's limit. */
        private void release() {
            done = true;
            peer.running.remove(key, this);
        }

        /**
         * Sends the answer (the one message that ends the call: only the first counts), unless the page gave up; the call stops
         * counting against the window's limit first.
         */
        private void finish(String answer) {
            release();
            if (terminated.compareAndSet(false, true) && !cancelled && !peer.stopped) {
                peer.send(answer);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> RpcStream<T> stream() {
            if (!streamRequested) {
                throw new RpcException(RpcException.STREAM_REQUIRED, method + " is a stream: call it with jolt.stream, not jolt.call");
            }
            synchronized (this) {
                if (sink != null) {
                    throw new IllegalStateException("stream() was already called for " + method);
                }
                Sink<T> opened = new Sink<>();
                sink = opened;
                return opened;
            }
        }

        /** Where a handler puts the items of the stream; one credit per item, given back as the page consumes them. */
        private final class Sink<T> implements RpcStream<T> {

            private final Semaphore credits = new Semaphore(options.streamWindow());
            private boolean closed; // guarded by this

            @Override
            public void send(T item) {
                if (terminated.get()) {
                    throw new IllegalStateException("the stream of " + method + " has ended");
                }
                try {
                    credits.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException(method + " was cancelled");
                }
                if (cancelled) {
                    throw new CancellationException(method + " was cancelled");
                }
                String json;
                try {
                    json = encode(item);
                } catch (IllegalArgumentException e) {
                    credits.release();
                    throw e;
                }
                peer.send(RpcProtocol.item(session, id, json));
            }

            @Override
            public void complete() {
                if (close()) {
                    finish(RpcProtocol.end(session, id));
                }
            }

            @Override
            public void fail(Throwable error) {
                Objects.requireNonNull(error, "error");
                if (close()) {
                    Running.this.fail(error);
                }
            }

            private synchronized boolean close() {
                boolean first = !closed;
                closed = true;
                return first;
            }

            void grant(int n, int window) {
                int room = window - credits.availablePermits();
                if (room > 0) {
                    credits.release(Math.min(n, room));
                }
            }

            /** Lets every sender waiting for a credit go, so that it sees the call was cancelled. */
            void wake() {
                credits.release(1 << 20);
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
                finish(answer(rpc));
                return;
            }
            // What the page is told is decided first: an exception the app handles on purpose is expected, one it does not is a bug.
            VirtualMachineError fatal = isFatal(error) ? (VirtualMachineError) error : null;
            RpcException answer = null;
            if (!(error instanceof VirtualMachineError)) {
                try {
                    answer = handled(error);
                } catch (VirtualMachineError vm) {
                    fatal = isFatal(vm) ? vm : fatal;
                }
            }
            if (answer != null && !RpcException.INTERNAL.equals(answer.code())) {
                LOG.log(System.Logger.Level.DEBUG, "RPC method " + method + " failed with " + answer.code(), error);
            } else {
                LOG.log(System.Logger.Level.WARNING, "RPC method " + method + " failed", error);
                BiConsumer<RpcCall, Throwable> listener = errorListener;
                if (listener != null) {
                    try {
                        listener.accept(this, error);
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.ERROR, "the Rpc onError listener failed", e);
                    }
                }
            }
            finish(answer != null ? answer(answer)
                    : RpcProtocol.error(session, id, RpcException.INTERNAL, "The call to " + method + " failed", null));
            if (fatal != null) {
                throw fatal; // out of memory or worse: answered, but not swallowed
            }
        }

        /** Out of memory and the like: not something to run app code after. A stack overflow is the exception, it unwinds on its own. */
        private static boolean isFatal(Throwable error) {
            return error instanceof VirtualMachineError && !(error instanceof StackOverflowError);
        }

        /** What the {@link #onException} handler for {@code error} says, or {@code null} if there is none or it failed. */
        private RpcException handled(Throwable error) {
            RpcExceptionHandler<Throwable> handler = exceptionHandlerFor(error);
            if (handler == null) {
                return null;
            }
            try {
                RpcException answer = handler.handle(this, error);
                if (answer == null) {
                    LOG.log(System.Logger.Level.ERROR, "the onException handler for " + error.getClass().getName() + " returned null for " + method);
                }
                return answer;
            } catch (VirtualMachineError vm) {
                throw vm;
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.ERROR, "the onException handler for " + error.getClass().getName() + " failed for " + method, t);
                return null;
            }
        }

        /** The protocol message for an {@link RpcException}: its message cut to {@link RpcException#MAX_MESSAGE_CHARS}. */
        private String answer(RpcException rpc) {
            String message = rpc.getMessage();
            if (message != null && message.length() > RpcException.MAX_MESSAGE_CHARS) {
                int cut = RpcException.MAX_MESSAGE_CHARS;
                if (Character.isHighSurrogate(message.charAt(cut - 1))) {
                    cut--; // not in the middle of a pair
                }
                message = message.substring(0, cut) + "…";
            }
            return RpcProtocol.error(session, id, rpc.code(), message, errorData(rpc));
        }

        /** The data of an {@link RpcException} as JSON, or {@code null} if there is none or the codec cannot write it. */
        private String errorData(RpcException rpc) {
            if (rpc.data() == null) {
                return null;
            }
            try {
                return encode(rpc.data());
            } catch (IllegalArgumentException e) {
                LOG.log(System.Logger.Level.WARNING, method + ": the data of an RpcException could not be written", e);
                return null;
            }
        }

        void cancel() {
            Flow.Subscription cancelling;
            Sink<?> opened;
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
                cancelling = subscription;
                opened = sink;
            }
            if (cancelling != null) {
                cancelling.cancel();
            }
            if (opened != null) {
                opened.wake();
            }
            if (pending) {
                release(); // no thread is left to do it: the handler returned and the stream was all that kept the call alive
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
                // the codec's message (which may name classes or fields of the app) stays in the log and in the cause
                throw new RpcException(RpcException.BAD_PARAMS, "Invalid parameters for " + method, null, e);
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
