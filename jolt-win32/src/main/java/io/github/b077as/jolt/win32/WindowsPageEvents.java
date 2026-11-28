package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.CrashEvent;
import io.github.b077as.jolt.DownloadRequest;
import io.github.b077as.jolt.LoadFailure;
import io.github.b077as.jolt.internal.WindowEvents;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Turns WebView2's navigation, process and download events into calls on the window's {@link WindowEvents}, and carries
 * out the answers. Everything here runs on the UI thread, inside the event unless said otherwise.
 */
final class WindowsPageEvents {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.win32");

    /** COREWEBVIEW2_PROCESS_FAILED_KIND */
    private static final int BROWSER_PROCESS_EXITED = 0;
    private static final int RENDER_PROCESS_EXITED = 1;
    private static final int RENDER_PROCESS_UNRESPONSIVE = 2;
    /** COREWEBVIEW2_WEB_ERROR_STATUS */
    private static final int CONNECTION_ABORTED = 9;
    private static final long DOWNLOAD_GRACE_MILLIS = 1000;
    private static final int OPERATION_CANCELED = 14;
    private static final String[] ERROR_NAMES = {"UNKNOWN", "CERTIFICATE_COMMON_NAME_IS_INCORRECT", "CERTIFICATE_EXPIRED",
            "CLIENT_CERTIFICATE_CONTAINS_ERRORS", "CERTIFICATE_REVOKED", "CERTIFICATE_IS_INVALID", "SERVER_UNREACHABLE", "TIMEOUT",
            "ERROR_HTTP_INVALID_SERVER_RESPONSE", "CONNECTION_ABORTED", "CONNECTION_RESET", "DISCONNECTED", "CANNOT_CONNECT",
            "HOST_NAME_NOT_RESOLVED", "OPERATION_CANCELED", "REDIRECT_FAILED", "UNEXPECTED_ERROR",
            "VALID_AUTHENTICATION_CREDENTIALS_REQUIRED", "VALID_PROXY_AUTHENTICATION_REQUIRED"};
    /** A navigation that never completes (a move within the document) must not pile up. */
    private static final int MAX_NAVIGATIONS = 32;

    private final WindowEvents events;
    private final Consumer<Runnable> poster;
    /** "Connection aborted" failures waiting to see whether they were downloads, by address. */
    private final Map<String, LoadFailure> aborted = new HashMap<>();
    /** Top-level navigations under way: WebView2's navigation id to the address, which the completion event does not carry. */
    private final Map<Long, String> navigations = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) {
            return size() > MAX_NAVIGATIONS;
        }
    };
    /** Downloads waiting for the handler's answer, each as the step that refuses it. */
    private final Set<Runnable> waitingDownloads = new LinkedHashSet<>();

    /** @param poster runs a task on the UI thread */
    WindowsPageEvents(WindowEvents events, Consumer<Runnable> poster) {
        this.events = events;
        this.poster = poster;
    }

    // ---- loading ----

    /** A top-level navigation is starting (or was redirected: the id stays, the address changes). */
    void navigationStarting(MemorySegment args, String uri, boolean allowed) {
        long id = readLong(args, WebView2Abi.ICoreWebView2NavigationStartingEventArgs.GET_NAVIGATION_ID);
        if (allowed && uri != null) {
            navigations.put(id, uri);
        } else {
            navigations.remove(id); // a blocked navigation is reported as blocked, not as a failure
        }
    }

    void navigationCompleted(MemorySegment args) {
        String uri = navigations.remove(readLong(args, WebView2Abi.ICoreWebView2NavigationCompletedEventArgs.GET_NAVIGATION_ID));
        if (uri == null) {
            return;
        }
        boolean success = readInt(args, WebView2Abi.ICoreWebView2NavigationCompletedEventArgs.GET_IS_SUCCESS) != 0;
        int status = 0;
        MemorySegment args2 = Com.queryInterface(args, WebView2Abi.ICoreWebView2NavigationCompletedEventArgs2.IID);
        if (!args2.equals(MemorySegment.NULL)) { // older runtimes lack it
            try {
                status = readInt(args2, WebView2Abi.ICoreWebView2NavigationCompletedEventArgs2.GET_HTTP_STATUS_CODE);
            } finally {
                Com.release(args2);
            }
        }
        LOG.log(System.Logger.Level.DEBUG, "navigation to " + uri + " completed: success " + success + ", status " + status);
        if (success || status >= 400) {
            events.loaded(uri, status);
            return;
        }
        int error = readInt(args, WebView2Abi.ICoreWebView2NavigationCompletedEventArgs.GET_WEB_ERROR_STATUS);
        if (error == OPERATION_CANCELED) {
            return; // another navigation took over, or the application stopped it
        }
        String name = error >= 0 && error < ERROR_NAMES.length ? ERROR_NAMES[error] : String.valueOf(error);
        LoadFailure failure = new LoadFailure(uri, reasonOf(error), 0, "WebView2 error " + name);
        if (error == CONNECTION_ABORTED) {
            reportUnlessItBecomesADownload(failure);
        } else {
            events.failed(failure);
        }
    }

    /**
     * A navigation whose answer turns into a download completes as "connection aborted", and only then is the download
     * announced. So that failure waits a moment, and is dropped if a download of its address starts meanwhile.
     */
    private void reportUnlessItBecomesADownload(LoadFailure failure) {
        aborted.put(failure.url(), failure);
        CompletableFuture.delayedExecutor(DOWNLOAD_GRACE_MILLIS, TimeUnit.MILLISECONDS).execute(() -> poster.accept(() -> {
            if (aborted.remove(failure.url(), failure)) {
                events.failed(failure);
            }
        }));
    }

    private static LoadFailure.Reason reasonOf(int error) {
        return switch (error) {
            case 1, 2, 3, 4, 5 -> LoadFailure.Reason.CERTIFICATE;
            case 6, 9, 10, 11, 12 -> LoadFailure.Reason.CONNECTION;
            case 7 -> LoadFailure.Reason.TIMEOUT;
            case 13 -> LoadFailure.Reason.HOST_NOT_FOUND;
            default -> LoadFailure.Reason.OTHER;
        };
    }

    // ---- processes ----

    /** What the window must do after a process failure. */
    enum AfterCrash {
        NOTHING,
        /** The page is gone: fail what was waiting for it. */
        PAGE_GONE,
        /** As {@link #PAGE_GONE}, and load it again. */
        RELOAD,
        /** The browser process is gone, with it the environment every window was made from. */
        WEB_VIEW_LOST
    }

    AfterCrash processFailed(MemorySegment args) {
        int kind = readInt(args, WebView2Abi.ICoreWebView2ProcessFailedEventArgs.GET_PROCESS_FAILED_KIND);
        switch (kind) {
            case BROWSER_PROCESS_EXITED -> {
                events.crashed(CrashEvent.Reason.WEB_VIEW_LOST);
                return AfterCrash.WEB_VIEW_LOST;
            }
            case RENDER_PROCESS_EXITED -> {
                navigations.clear(); // whatever was loading will not finish
                return events.crashed(CrashEvent.Reason.PROCESS_EXITED) ? AfterCrash.RELOAD : AfterCrash.PAGE_GONE;
            }
            case RENDER_PROCESS_UNRESPONSIVE -> events.crashed(CrashEvent.Reason.UNRESPONSIVE);
            // frames, GPU and utility processes are restarted by WebView2 itself
            default -> LOG.log(System.Logger.Level.DEBUG, "a WebView2 helper process failed, kind " + kind);
        }
        return AfterCrash.NOTHING;
    }

    // ---- downloads ----

    void downloadStarting(MemorySegment args) {
        DownloadRequest request;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            if (Com.call(args, WebView2Abi.ICoreWebView2DownloadStartingEventArgs.GET_DOWNLOAD_OPERATION, Com.HR_PTR, out) < 0) {
                refuse(args);
                return;
            }
            MemorySegment operation = Native.readPointer(out);
            try {
                String url = readString(operation, WebView2Abi.ICoreWebView2DownloadOperation.GET_URI);
                String mime = readString(operation, WebView2Abi.ICoreWebView2DownloadOperation.GET_MIME_TYPE);
                long total = readLong(operation, WebView2Abi.ICoreWebView2DownloadOperation.GET_TOTAL_BYTES_TO_RECEIVE);
                // WebView2 has already picked a file in the user's download folder; its name is the suggestion
                String proposed = readString(args, WebView2Abi.ICoreWebView2DownloadStartingEventArgs.GET_RESULT_FILE_PATH);
                request = new DownloadRequest(url, proposed, mime, total);
                navigations.values().remove(url); // a navigation that turned into a download did not fail
                aborted.remove(url);
            } finally {
                Com.release(operation);
            }
        }
        Held held = new Held();
        switch (events.download(request, held::answer)) {
            case DENY -> refuse(args);
            // The answer is queued behind this event (see WindowEvents), so there is time to put it on hold here
            case ASK -> held.hold(args);
            case WEB_VIEW_DEFAULT -> { }
        }
    }

    /** One download whose event is kept open until the application says where it goes. */
    private final class Held {
        private MemorySegment args = MemorySegment.NULL;
        private MemorySegment deferral = MemorySegment.NULL;
        private final Runnable refuse = () -> answer(Optional.empty());

        void hold(MemorySegment event) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(ADDRESS);
                if (Com.call(event, WebView2Abi.ICoreWebView2DownloadStartingEventArgs.GET_DEFERRAL, Com.HR_PTR, out) < 0) {
                    WindowsPageEvents.refuse(event);
                    return;
                }
                deferral = Native.readPointer(out);
            }
            Com.addRef(event); // the event must outlive this callback
            args = event;
            waitingDownloads.add(refuse);
        }

        void answer(Optional<Path> destination) {
            if (!waitingDownloads.remove(refuse)) {
                return; // already answered, or the window is gone
            }
            try {
                if (destination.isEmpty()) {
                    WindowsPageEvents.refuse(args);
                } else {
                    try (Arena arena = Arena.ofConfined()) {
                        Com.callChecked("put_ResultFilePath", args, WebView2Abi.ICoreWebView2DownloadStartingEventArgs.PUT_RESULT_FILE_PATH,
                                Com.HR_PTR, Native.allocWide(arena, destination.get().toString()));
                    }
                    // no download panel of the web view's own: the application is in charge
                    Com.call(args, WebView2Abi.ICoreWebView2DownloadStartingEventArgs.PUT_HANDLED, Com.HR_INT, 1);
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "starting a download failed; it is refused", e);
                WindowsPageEvents.refuse(args);
            } finally {
                ResourceRequests.complete(deferral);
                Com.release(args);
            }
        }
    }

    private static void refuse(MemorySegment args) {
        Com.call(args, WebView2Abi.ICoreWebView2DownloadStartingEventArgs.PUT_CANCEL, Com.HR_INT, 1);
        Com.call(args, WebView2Abi.ICoreWebView2DownloadStartingEventArgs.PUT_HANDLED, Com.HR_INT, 1);
    }

    /** The window is closing: downloads still waiting for an answer are refused while the web view still exists. */
    void windowClosing() {
        for (Runnable refuse : new ArrayList<>(waitingDownloads)) {
            refuse.run();
        }
        navigations.clear();
        aborted.clear();
    }

    // ---- reading properties ----

    private static int readInt(MemorySegment object, int slot) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_INT);
            return Com.call(object, slot, Com.HR_PTR, out) < 0 ? 0 : out.get(JAVA_INT, 0);
        }
    }

    private static long readLong(MemorySegment object, int slot) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_LONG);
            return Com.call(object, slot, Com.HR_PTR, out) < 0 ? -1 : out.get(JAVA_LONG, 0);
        }
    }

    private static String readString(MemorySegment object, int slot) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            if (Com.call(object, slot, Com.HR_PTR, out) < 0) {
                return "";
            }
            MemorySegment raw = Native.readPointer(out);
            if (raw.equals(MemorySegment.NULL)) {
                return "";
            }
            String text = Native.readWide(raw);
            Win32.coTaskMemFree(raw);
            return text;
        }
    }
}
