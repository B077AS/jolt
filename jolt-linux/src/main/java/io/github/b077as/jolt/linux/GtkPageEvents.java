package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.CrashEvent;
import io.github.b077as.jolt.DownloadRequest;
import io.github.b077as.jolt.LoadFailure;
import io.github.b077as.jolt.internal.WindowEvents;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Turns WebKitGTK's load, web-process and download signals into calls on the window's {@link WindowEvents}, and carries
 * out the answers. UI thread only; one per window.
 */
final class GtkPageEvents {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.linux");

    /** WebKitLoadEvent */
    private static final int LOAD_STARTED = 0;
    private static final int LOAD_COMMITTED = 2;
    private static final int LOAD_FINISHED = 3;
    /** WebKitWebProcessTerminationReason */
    private static final int EXCEEDED_MEMORY_LIMIT = 1;
    /** WebKitNetworkError and WebKitPolicyError codes */
    private static final int NETWORK_ERROR_TRANSPORT = 300;
    private static final int NETWORK_ERROR_CANCELLED = 302;
    private static final int POLICY_ERROR_INTERRUPTED = 102;
    /** GIOErrorEnum */
    private static final int IO_ERROR_TIMED_OUT = 24;
    private static final int IO_ERROR_HOST_NOT_FOUND = 31;

    private static final SymbolLookup GLIB = SymbolLookup.libraryLookup("libglib-2.0.so.0", Arena.global());
    private static final SymbolLookup GOBJECT = SymbolLookup.libraryLookup("libgobject-2.0.so.0", Arena.global());
    private static final SymbolLookup SOUP = SymbolLookup.libraryLookup("libsoup-3.0.so.0", Arena.global());
    private static final SymbolLookup WEBKIT = SymbolLookup.libraryLookup("libwebkit2gtk-4.1.so.0", Arena.global());

    private static final MethodHandle G_QUARK_TO_STRING = h(GLIB, "g_quark_to_string", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle G_OBJECT_REF = h(GOBJECT, "g_object_ref", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle HEADERS_GET_ONE = h(SOUP, "soup_message_headers_get_one",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GET_MAIN_RESOURCE = h(WEBKIT, "webkit_web_view_get_main_resource",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle RESOURCE_GET_RESPONSE = h(WEBKIT, "webkit_web_resource_get_response",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle RESPONSE_STATUS = h(WEBKIT, "webkit_uri_response_get_status_code",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle RESPONSE_MIME = h(WEBKIT, "webkit_uri_response_get_mime_type",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle RESPONSE_LENGTH = h(WEBKIT, "webkit_uri_response_get_content_length",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle RESPONSE_HEADERS = h(WEBKIT, "webkit_uri_response_get_http_headers",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle DECISION_GET_RESPONSE = h(WEBKIT, "webkit_response_policy_decision_get_response",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle DECISION_MIME_SUPPORTED = h(WEBKIT, "webkit_response_policy_decision_is_mime_type_supported",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle DECISION_MAIN_FRAME = h(WEBKIT, "webkit_response_policy_decision_is_main_frame_main_resource",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle DECISION_DOWNLOAD = h(WEBKIT, "webkit_policy_decision_download",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle DOWNLOAD_GET_WEB_VIEW = h(WEBKIT, "webkit_download_get_web_view",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle DOWNLOAD_GET_REQUEST = h(WEBKIT, "webkit_download_get_request",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle DOWNLOAD_GET_RESPONSE = h(WEBKIT, "webkit_download_get_response",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle REQUEST_GET_URI = h(WEBKIT, "webkit_uri_request_get_uri",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle DOWNLOAD_SET_ALLOW_OVERWRITE = h(WEBKIT, "webkit_download_set_allow_overwrite",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle DOWNLOAD_SET_DESTINATION = h(WEBKIT, "webkit_download_set_destination",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle DOWNLOAD_CANCEL = h(WEBKIT, "webkit_download_cancel", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle TERMINATE_WEB_PROCESS = h(WEBKIT, "webkit_web_view_terminate_web_process",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle RELOAD = h(WEBKIT, "webkit_web_view_reload", FunctionDescriptor.ofVoid(ADDRESS));

    private final WindowEvents events;
    /** The current load has failed (or was turned into something else), so its end is not a success. */
    private boolean failed;
    /** The address whose load just failed, for which WebKit is about to show an error page of its own; null otherwise. */
    private String errorPage;
    /** The address of the document being shown; the web view itself forgets it when its process ends. */
    private String committed;
    /** Downloads waiting for the handler's answer, each as the step that refuses it. */
    private final Set<Runnable> waitingDownloads = new LinkedHashSet<>();

    GtkPageEvents(WindowEvents events) {
        this.events = events;
    }

    private static MethodHandle h(SymbolLookup lib, String name, FunctionDescriptor fd) {
        return Native.downcall(lib, name, fd);
    }

    private static Object call(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static MemorySegment pointer(MethodHandle handle, Object... args) {
        return (MemorySegment) call(handle, args);
    }

    private static String text(MethodHandle handle, Object... args) {
        return Gtk.readUtf8(pointer(handle, args));
    }

    static void reload(MemorySegment webView) {
        call(RELOAD, webView);
    }

    /** Ends the page's process the way a crash would. For tests. */
    static void terminateWebProcess(MemorySegment webView) {
        call(TERMINATE_WEB_PROCESS, webView);
    }

    // ---- loading ----

    void loadChanged(MemorySegment webView, int event) {
        if (event == LOAD_STARTED) {
            // the error page is a load too, of the same address; its end must not count as that address having loaded
            failed = errorPage != null && errorPage.equals(Gtk.webViewUri(webView));
            errorPage = null;
        } else if (event == LOAD_COMMITTED) {
            committed = Gtk.webViewUri(webView);
        } else if (event == LOAD_FINISHED && !failed) {
            String uri = Gtk.webViewUri(webView);
            if (uri != null) {
                events.loaded(uri, status(webView));
            }
        }
    }

    private static int status(MemorySegment webView) {
        MemorySegment resource = pointer(GET_MAIN_RESOURCE, webView);
        if (resource.equals(MemorySegment.NULL)) {
            return 0;
        }
        MemorySegment response = pointer(RESOURCE_GET_RESPONSE, resource);
        return response.equals(MemorySegment.NULL) ? 0 : (int) call(RESPONSE_STATUS, response);
    }

    /** The application is about to load something itself: no error page comes before it. */
    void applicationLoads() {
        errorPage = null;
    }

    /** The address of the last document the web view started showing, or null. */
    String committedUri() {
        return committed;
    }

    /** @param error a {@code GError*}: domain (a GQuark), code, message */
    void loadFailed(String uri, MemorySegment error) {
        failed = true;
        MemorySegment e = error.reinterpret(16);
        String domain = text(G_QUARK_TO_STRING, e.get(JAVA_INT, 0));
        int code = e.get(JAVA_INT, 4);
        String message = Gtk.readUtf8(e.get(ADDRESS, 8));
        if ("WebKitNetworkError".equals(domain) && code == NETWORK_ERROR_CANCELLED
                || "WebKitPolicyError".equals(domain) && code == POLICY_ERROR_INTERRUPTED) {
            return; // another load took over, the policy blocked it, or it became a download
        }
        errorPage = uri;
        events.failed(new LoadFailure(uri == null ? "" : uri, reasonOf(domain, code), 0, domain + " " + code + ": " + message));
    }

    private static LoadFailure.Reason reasonOf(String domain, int code) {
        return switch (String.valueOf(domain)) {
            case "g-resolver-error-quark" -> LoadFailure.Reason.HOST_NOT_FOUND;
            case "g-tls-error-quark" -> LoadFailure.Reason.CERTIFICATE;
            case "g-io-error-quark" -> switch (code) {
                case IO_ERROR_TIMED_OUT -> LoadFailure.Reason.TIMEOUT;
                case IO_ERROR_HOST_NOT_FOUND -> LoadFailure.Reason.HOST_NOT_FOUND;
                // host unreachable, network unreachable, connection refused, connection closed, not connected
                case 37, 38, 39, 44, 45 -> LoadFailure.Reason.CONNECTION;
                default -> LoadFailure.Reason.OTHER;
            };
            case "WebKitNetworkError" -> code == NETWORK_ERROR_TRANSPORT ? LoadFailure.Reason.CONNECTION : LoadFailure.Reason.OTHER;
            default -> LoadFailure.Reason.OTHER;
        };
    }

    // ---- the web process ----

    /** @return whether the page is to be loaded again */
    boolean processTerminated(int reason) {
        failed = true; // whatever was loading did not succeed
        return events.crashed(reason == EXCEEDED_MEMORY_LIMIT ? CrashEvent.Reason.OUT_OF_MEMORY : CrashEvent.Reason.PROCESS_EXITED);
    }

    // ---- downloads ----

    /**
     * A {@code decide-policy} for a response. A browser saves an answer that is sent as an attachment, or that it cannot
     * show; WebKitGTK leaves that to the application, so it is done here and the download is then decided like any other.
     *
     * @return whether the decision was taken here
     */
    static boolean downloadInstead(MemorySegment decision) {
        if ((int) call(DECISION_MAIN_FRAME, decision) == 0) {
            return false;
        }
        MemorySegment response = pointer(DECISION_GET_RESPONSE, decision);
        int status = (int) call(RESPONSE_STATUS, response);
        if (status != 0 && (status < 200 || status > 299)) {
            return false; // an error page is shown (and reported), not saved
        }
        boolean attachment = false;
        MemorySegment headers = pointer(RESPONSE_HEADERS, response);
        if (!headers.equals(MemorySegment.NULL)) {
            try (Arena arena = Arena.ofConfined()) {
                String disposition = text(HEADERS_GET_ONE, headers, Gtk.utf8(arena, "Content-Disposition"));
                attachment = disposition != null && disposition.strip().toLowerCase(Locale.ROOT).startsWith("attachment");
            }
        }
        if (!attachment && (int) call(DECISION_MIME_SUPPORTED, decision) != 0) {
            return false;
        }
        call(DECISION_DOWNLOAD, decision);
        return true;
    }

    /** The address of the web view that started {@code download}, 0 if none. */
    static long webViewOf(MemorySegment download) {
        return pointer(DOWNLOAD_GET_WEB_VIEW, download).address();
    }

    static void cancel(MemorySegment download) {
        call(DOWNLOAD_CANCEL, download);
    }

    /**
     * WebKit wants to know where a download goes.
     *
     * @return false to let it choose (the user's download folder), true if that is taken care of here
     */
    boolean decideDestination(MemorySegment download, String suggestedName) {
        MemorySegment webRequest = pointer(DOWNLOAD_GET_REQUEST, download);
        String url = webRequest.equals(MemorySegment.NULL) ? "" : text(REQUEST_GET_URI, webRequest);
        MemorySegment response = pointer(DOWNLOAD_GET_RESPONSE, download);
        String mime = "";
        long total = -1;
        if (!response.equals(MemorySegment.NULL)) {
            mime = text(RESPONSE_MIME, response);
            total = (long) call(RESPONSE_LENGTH, response);
            if (total == 0) {
                total = -1; // WebKit's "unknown"
            }
        }
        Held held = new Held();
        switch (events.download(new DownloadRequest(url, suggestedName, mime, total), held::answer)) {
            case DENY -> cancel(download);
            // The answer is queued behind this signal (see WindowEvents), so there is time to put it on hold here
            case ASK -> held.hold(download);
            case WEB_VIEW_DEFAULT -> {
                return false;
            }
        }
        return true;
    }

    /** One download waiting for the application to say where it goes. */
    private final class Held {
        private MemorySegment download = MemorySegment.NULL;
        private final Runnable refuse = () -> answer(Optional.empty());

        void hold(MemorySegment waiting) {
            call(G_OBJECT_REF, waiting); // it must outlive this signal
            download = waiting;
            waitingDownloads.add(refuse);
        }

        void answer(Optional<Path> destination) {
            if (!waitingDownloads.remove(refuse)) {
                return; // already answered, or the window is gone
            }
            try (Arena arena = Arena.ofConfined()) {
                if (destination.isEmpty()) {
                    cancel(download);
                } else {
                    call(DOWNLOAD_SET_ALLOW_OVERWRITE, download, 1);
                    // WebKitGTK 4.1 takes the destination as an address
                    call(DOWNLOAD_SET_DESTINATION, download, Gtk.utf8(arena, destination.get().toUri().toString()));
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "starting a download failed; it is refused", e);
                cancel(download);
            } finally {
                Gtk.unref(download);
            }
        }
    }

    /** The window is closing: downloads still waiting for an answer are refused. */
    void windowClosing() {
        for (Runnable refuse : new ArrayList<>(waitingDownloads)) {
            refuse.run();
        }
    }
}
