package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.internal.ResourceService;
import io.github.b077as.jolt.internal.ffi.Native;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Serving the app's own files from inside WebKitGTK: a custom URI scheme whose requests arrive here and are answered from a
 * {@link ResourceService}, so nothing listens on the network.
 *
 * <p>It needs WebKitGTK 2.40 or newer (the request body API) and libsoup 3, which a WebKitGTK 4.1 installation always has.
 * These bindings live apart from {@link Gtk} so that an older system loses only this feature, not the whole backend:
 * {@link #available()} says whether every symbol was found.
 */
final class GtkResources {

    /** The scheme and the origin the app's pages get. WebKitGTK cannot take over {@code https}, so it is a scheme of our own. */
    static final String SCHEME = "jolt";
    static final String ORIGIN = SCHEME + "://app";

    private static final int SOUP_MESSAGE_HEADERS_RESPONSE = 1;
    private static final int READ_CHUNK = 64 * 1024;
    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.linux");

    private record Handles(
            MethodHandle objectRef, MethodHandle bytesNew, MethodHandle bytesUnref, MethodHandle memoryStreamFromBytes,
            MethodHandle streamRead, MethodHandle streamClose,
            MethodHandle headersNew, MethodHandle headersAppend, MethodHandle headersIterInit, MethodHandle headersIterNext,
            MethodHandle contextRegister, MethodHandle contextSecurityManager, MethodHandle registerSecure, MethodHandle registerCors,
            MethodHandle requestMethod, MethodHandle requestUri, MethodHandle requestHeaders, MethodHandle requestBody,
            MethodHandle requestWebView, MethodHandle requestFinish,
            MethodHandle responseNew, MethodHandle responseStatus, MethodHandle responseContentType, MethodHandle responseHeaders) {}

    /** {@code null} when this system lacks something. */
    private static final Handles H = load();

    private GtkResources() {}

    private static Handles load() {
        try {
            Arena libs = Arena.global();
            SymbolLookup glib = SymbolLookup.libraryLookup("libglib-2.0.so.0", libs);
            SymbolLookup gobject = SymbolLookup.libraryLookup("libgobject-2.0.so.0", libs);
            SymbolLookup gio = SymbolLookup.libraryLookup("libgio-2.0.so.0", libs);
            SymbolLookup soup = SymbolLookup.libraryLookup("libsoup-3.0.so.0", libs);
            SymbolLookup webkit = SymbolLookup.libraryLookup("libwebkit2gtk-4.1.so.0", libs);
            return new Handles(
                    Native.downcall(gobject, "g_object_ref", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                    Native.downcall(glib, "g_bytes_new", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG)),
                    Native.downcall(glib, "g_bytes_unref", FunctionDescriptor.ofVoid(ADDRESS)),
                    Native.downcall(gio, "g_memory_input_stream_new_from_bytes", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                    Native.downcall(gio, "g_input_stream_read", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS)),
                    Native.downcall(gio, "g_input_stream_close", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)),
                    Native.downcall(soup, "soup_message_headers_new", FunctionDescriptor.of(ADDRESS, JAVA_INT)),
                    Native.downcall(soup, "soup_message_headers_append", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)),
                    Native.downcall(soup, "soup_message_headers_iter_init", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                    Native.downcall(soup, "soup_message_headers_iter_next", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)),
                    Native.downcall(webkit, "webkit_web_context_register_uri_scheme",
                            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
                    Native.downcall(webkit, "webkit_web_context_get_security_manager", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                    Native.downcall(webkit, "webkit_security_manager_register_uri_scheme_as_secure", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                    Native.downcall(webkit, "webkit_security_manager_register_uri_scheme_as_cors_enabled", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                    Native.downcall(webkit, "webkit_uri_scheme_request_get_http_method", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                    Native.downcall(webkit, "webkit_uri_scheme_request_get_uri", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                    Native.downcall(webkit, "webkit_uri_scheme_request_get_http_headers", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                    Native.downcall(webkit, "webkit_uri_scheme_request_get_http_body", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                    Native.downcall(webkit, "webkit_uri_scheme_request_get_web_view", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                    Native.downcall(webkit, "webkit_uri_scheme_request_finish_with_response", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                    Native.downcall(webkit, "webkit_uri_scheme_response_new", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG)),
                    Native.downcall(webkit, "webkit_uri_scheme_response_set_status", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS)),
                    Native.downcall(webkit, "webkit_uri_scheme_response_set_content_type", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                    Native.downcall(webkit, "webkit_uri_scheme_response_set_http_headers", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)));
        } catch (LinkageError | IllegalArgumentException e) {
            LOG.log(System.Logger.Level.DEBUG, "serving files from inside WebKitGTK is not available: " + e.getMessage());
            return null;
        }
    }

    /** Whether this system can serve the app's files from inside the web view. */
    static boolean available() {
        return H != null;
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

    /** Registers {@link #SCHEME} on the web context, as a secure and CORS-enabled scheme so modules and {@code fetch} work. */
    static void registerScheme(MemorySegment context, MemorySegment callback, MemorySegment userData) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment scheme = Gtk.utf8(arena, SCHEME);
            MemorySegment security = (MemorySegment) call(H.contextSecurityManager, context); // owned by the context
            call(H.registerSecure, security, scheme);
            call(H.registerCors, security, scheme);
            call(H.contextRegister, context, scheme, callback, userData, MemorySegment.NULL);
        }
    }

    /**
     * A request as the page made it.
     *
     * @param webView the view that made it, as an address (0 if none), to find the window
     * @param body    the request body, empty if none, or {@code null} if it is larger than {@link ResourceService#MAX_BODY}
     */
    record Incoming(String method, String uri, Map<String, String> headers, byte[] body, long webView) {}

    /** Reads method, address, headers and body from a {@code WebKitURISchemeRequest}. */
    static Incoming read(MemorySegment request) {
        String method = Gtk.readUtf8((MemorySegment) call(H.requestMethod, request));
        String uri = Gtk.readUtf8((MemorySegment) call(H.requestUri, request));
        MemorySegment view = (MemorySegment) call(H.requestWebView, request);
        try (Arena arena = Arena.ofConfined()) {
            Map<String, String> headers = new LinkedHashMap<>();
            MemorySegment soup = (MemorySegment) call(H.requestHeaders, request); // owned by the request
            if (!soup.equals(MemorySegment.NULL)) {
                MemorySegment iter = arena.allocate(3 * ADDRESS.byteSize(), ADDRESS.byteAlignment()); // SoupMessageHeadersIter
                MemorySegment name = arena.allocate(ADDRESS);
                MemorySegment value = arena.allocate(ADDRESS);
                call(H.headersIterInit, iter, soup);
                while ((int) call(H.headersIterNext, iter, name, value) != 0) {
                    headers.merge(Gtk.readUtf8(name.get(ADDRESS, 0)).toLowerCase(Locale.ROOT), Gtk.readUtf8(value.get(ADDRESS, 0)),
                            (a, b) -> a + ", " + b);
                }
            }
            return new Incoming(method == null ? "GET" : method.toUpperCase(Locale.ROOT), uri, headers, body(request, arena),
                    view.address());
        }
    }

    private static byte[] body(MemorySegment request, Arena arena) {
        MemorySegment stream = (MemorySegment) call(H.requestBody, request); // transfer full
        if (stream.equals(MemorySegment.NULL)) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            MemorySegment chunk = arena.allocate(READ_CHUNK);
            while (true) {
                long read = (long) call(H.streamRead, stream, chunk, (long) READ_CHUNK, MemorySegment.NULL, MemorySegment.NULL);
                if (read <= 0) {
                    break;
                }
                bytes.write(chunk.asSlice(0, read).toArray(JAVA_BYTE), 0, (int) read);
                if (bytes.size() > ResourceService.MAX_BODY) {
                    return null;
                }
            }
            return bytes.toByteArray();
        } finally {
            call(H.streamClose, stream, MemorySegment.NULL, MemorySegment.NULL);
            Gtk.unref(stream);
        }
    }

    /** Keeps the request alive past the end of the scheme callback, for an answer that comes later. */
    static void ref(MemorySegment request) {
        call(H.objectRef, request);
    }

    /** Finishes a request with this answer. The caller still owns its own reference to the request. */
    static void respond(MemorySegment request, int status, Map<String, String> headers, byte[] body) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(Math.max(1, body.length));
            data.copyFrom(MemorySegment.ofArray(body));
            MemorySegment bytes = (MemorySegment) call(H.bytesNew, data, (long) body.length); // copies
            MemorySegment stream = (MemorySegment) call(H.memoryStreamFromBytes, bytes);
            call(H.bytesUnref, bytes);
            MemorySegment response = (MemorySegment) call(H.responseNew, stream, (long) body.length);
            Gtk.unref(stream); // the response holds its own reference

            call(H.responseStatus, response, status, Gtk.utf8(arena, ResourceService.reasonPhrase(status)));
            MemorySegment soup = (MemorySegment) call(H.headersNew, SOUP_MESSAGE_HEADERS_RESPONSE);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String name = header.getKey();
                if (name.equalsIgnoreCase("Content-Type")) {
                    call(H.responseContentType, response, Gtk.utf8(arena, header.getValue()));
                }
                // set_http_headers replaces every header set before it, the content type included, so it goes in here as well
                if (!name.equalsIgnoreCase("Content-Length")) {
                    call(H.headersAppend, soup, Gtk.utf8(arena, name), Gtk.utf8(arena, header.getValue()));
                }
            }
            call(H.responseHeaders, response, soup); // takes ownership of the headers
            call(H.requestFinish, request, response);
            Gtk.unref(response);
        }
    }
}
