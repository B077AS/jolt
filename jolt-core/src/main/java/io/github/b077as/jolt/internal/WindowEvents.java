package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.CloseDecision;
import io.github.b077as.jolt.CrashEvent;
import io.github.b077as.jolt.DownloadDecision;
import io.github.b077as.jolt.DownloadRequest;
import io.github.b077as.jolt.LoadFailure;
import io.github.b077as.jolt.PageLoad;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A window's load, crash, download and close handlers, and the rules for them. Shared by the backends so both behave the
 * same; the backends only turn native events into these calls and carry out the answers.
 *
 * <p>Handlers may be registered from any thread. Everything else is called on the UI thread, and no application code runs
 * inside the call: it is queued through the {@code poster}, because calling back into a web view from inside one of its
 * own native events is not safe.
 */
public final class WindowEvents {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt");

    /** Automatic reloads allowed within {@link #RELOAD_PERIOD_NANOS}; a page that crashes as it loads must not loop. */
    static final int MAX_RELOADS = 3;
    static final long RELOAD_PERIOD_NANOS = 60_000_000_000L;

    private final Consumer<Runnable> poster;
    private final SecurityGuard guard;
    private final LongSupplier clock;

    private final CopyOnWriteArrayList<Consumer<PageLoad>> loadListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<LoadFailure>> failureListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<CrashEvent>> crashListeners = new CopyOnWriteArrayList<>();
    private volatile boolean reloadOnCrash;
    private volatile Function<DownloadRequest, DownloadDecision> downloadHandler;
    private volatile Supplier<CloseDecision> closeHandler;

    // UI thread only
    private final ArrayDeque<Long> reloads = new ArrayDeque<>();
    private boolean closePending;

    public WindowEvents(Consumer<Runnable> poster, SecurityGuard guard) {
        this(poster, guard, System::nanoTime);
    }

    WindowEvents(Consumer<Runnable> poster, SecurityGuard guard, LongSupplier clock) {
        this.poster = Objects.requireNonNull(poster, "poster");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.clock = clock;
    }

    // ---- registration (any thread) ----

    public void onLoad(Consumer<PageLoad> listener) {
        loadListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void onLoadFailed(Consumer<LoadFailure> listener) {
        failureListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void onCrash(Consumer<CrashEvent> listener) {
        crashListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void setReloadOnCrash(boolean reload) {
        reloadOnCrash = reload;
    }

    public void onDownload(Function<DownloadRequest, DownloadDecision> handler) {
        downloadHandler = handler;
    }

    public void onCloseRequest(Supplier<CloseDecision> handler) {
        closeHandler = handler;
    }

    // ---- events (UI thread) ----

    /** A top-level document finished, with an HTTP status of {@code status} (0 if unknown). */
    public void loaded(String url, int status) {
        if (status >= 400) {
            failed(new LoadFailure(url, LoadFailure.Reason.HTTP_STATUS, status, "HTTP " + status));
            return;
        }
        notify(loadListeners, new PageLoad(url, status), "onLoad");
    }

    public void failed(LoadFailure failure) {
        LOG.log(System.Logger.Level.DEBUG, "loading " + failure.url() + " failed: " + failure.reason() + " (" + failure.detail() + ")");
        notify(failureListeners, failure, "onLoadFailed");
    }

    /**
     * The page's process is in trouble. Reports it and says whether the backend should now reload the page (after this
     * call; the listeners are queued).
     */
    public boolean crashed(CrashEvent.Reason reason) {
        boolean reload = false;
        boolean recoverable = reason == CrashEvent.Reason.PROCESS_EXITED || reason == CrashEvent.Reason.OUT_OF_MEMORY;
        if (recoverable && reloadOnCrash) {
            long now = clock.getAsLong();
            while (!reloads.isEmpty() && now - reloads.peekFirst() > RELOAD_PERIOD_NANOS) {
                reloads.removeFirst();
            }
            if (reloads.size() < MAX_RELOADS) {
                reloads.addLast(now);
                reload = true;
            } else {
                LOG.log(System.Logger.Level.WARNING, "the page crashed " + (MAX_RELOADS + 1) + " times within a minute; not reloading it again");
            }
        }
        // an unresponsive page is reported again and again while it lasts
        LOG.log(reason == CrashEvent.Reason.UNRESPONSIVE ? System.Logger.Level.DEBUG : System.Logger.Level.WARNING,
                "the web page process reported " + reason + (reload ? "; reloading" : ""));
        notify(crashListeners, new CrashEvent(reason, reload), "onCrash");
        return reload;
    }

    /** What a backend does with a download. */
    public enum DownloadAction {
        /** Nothing to do with Jolt: let the web view do what it does by itself. */
        WEB_VIEW_DEFAULT,
        /** Refuse it now. */
        DENY,
        /** Hold it; {@code answer} given to {@link #download} is called later on the UI thread. */
        ASK
    }

    /**
     * A download is about to start. With a handler it decides ({@link DownloadAction#ASK}: {@code answer} then receives the
     * destination, or empty to refuse); without one the {@link io.github.b077as.jolt.SecurityPolicy} does.
     */
    public DownloadAction download(DownloadRequest request, Consumer<Optional<Path>> answer) {
        Function<DownloadRequest, DownloadDecision> handler = downloadHandler;
        if (handler == null) {
            return guard.allowDownload(request.url()) ? DownloadAction.WEB_VIEW_DEFAULT : DownloadAction.DENY;
        }
        poster.accept(() -> {
            DownloadDecision decision;
            try {
                decision = Objects.requireNonNull(handler.apply(request), "the download handler returned null");
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "onDownload handler failed; the download is refused", e);
                answer.accept(Optional.empty());
                return;
            }
            decision.destination().whenComplete((destination, failure) -> poster.accept(() -> {
                Optional<Path> chosen = destination == null ? Optional.empty() : destination;
                if (failure != null) {
                    LOG.log(System.Logger.Level.ERROR, "choosing a download destination failed; the download is refused", failure);
                    chosen = Optional.empty();
                } else if (chosen.isPresent() && !chosen.get().isAbsolute()) {
                    LOG.log(System.Logger.Level.ERROR, "the download destination is not an absolute path; refused: " + chosen.get());
                    chosen = Optional.empty();
                }
                answer.accept(chosen);
            }));
        });
        return DownloadAction.ASK;
    }

    /**
     * The user (or the page's {@code window.close()}) wants to close the window.
     *
     * @return false if there is no handler, so the backend closes the window as usual; true if the request is taken care of
     *         here, and {@code close} runs later if the handler allows it
     */
    public boolean interceptClose(Runnable close) {
        Supplier<CloseDecision> handler = closeHandler;
        if (handler == null) {
            return false;
        }
        if (closePending) {
            return true; // one question at a time
        }
        closePending = true;
        poster.accept(() -> {
            CloseDecision decision;
            try {
                decision = Objects.requireNonNull(handler.get(), "the close handler returned null");
            } catch (RuntimeException e) {
                closePending = false;
                LOG.log(System.Logger.Level.ERROR, "onCloseRequest handler failed; the window stays open", e);
                return;
            }
            decision.allowed().whenComplete((allowed, failure) -> poster.accept(() -> {
                closePending = false;
                if (failure != null) {
                    LOG.log(System.Logger.Level.ERROR, "the close decision failed; the window stays open", failure);
                } else if (Boolean.TRUE.equals(allowed)) {
                    close.run();
                }
            }));
        });
        return true;
    }

    private <T> void notify(CopyOnWriteArrayList<Consumer<T>> listeners, T event, String name) {
        if (listeners.isEmpty()) {
            return;
        }
        poster.accept(() -> {
            for (Consumer<T> listener : listeners) {
                try {
                    listener.accept(event);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, name + " listener failed", e);
                }
            }
        });
    }
}
