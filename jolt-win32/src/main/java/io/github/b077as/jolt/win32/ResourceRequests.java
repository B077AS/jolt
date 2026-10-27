package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.internal.ResourceService;
import io.github.b077as.jolt.internal.ffi.Native;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Reads the request out of a {@code WebResourceRequested} event and writes a response back into it. UI thread only: every
 * call here touches COM objects that belong to it.
 */
final class ResourceRequests {

    /** {@code IStream::Read} is slot 3 ({@code ISequentialStream::Read(void* pv, ULONG cb, ULONG* pcbRead)}). */
    private static final int STREAM_READ = 3;
    private static final int READ_CHUNK = 64 * 1024;

    private ResourceRequests() {}

    /**
     * A request as the page made it.
     *
     * @param body the request body, empty if none, or {@code null} if it is larger than {@link ResourceService#MAX_BODY}
     */
    record Incoming(String method, String uri, Map<String, String> headers, byte[] body) {}

    /** Reads method, address, headers and body, or returns {@code null} if the event does not hold a readable request. */
    static Incoming read(MemorySegment args) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            if (Com.call(args, WebView2Abi.ICoreWebView2WebResourceRequestedEventArgs.GET_REQUEST, Com.HR_PTR, out) < 0) {
                return null;
            }
            MemorySegment request = Native.readPointer(out);
            try {
                String uri = wideString(request, WebView2Abi.ICoreWebView2WebResourceRequest.GET_URI, arena);
                String method = wideString(request, WebView2Abi.ICoreWebView2WebResourceRequest.GET_METHOD, arena);
                if (uri == null || method == null) {
                    return null;
                }
                return new Incoming(method.toUpperCase(Locale.ROOT), uri, headers(request, arena), body(request, arena));
            } finally {
                Com.release(request);
            }
        }
    }

    /** Puts a response into the event. */
    static void respond(MemorySegment environment, MemorySegment args, int status, Map<String, String> headers, byte[] body) {
        StringBuilder headerText = new StringBuilder();
        headers.forEach((name, value) -> headerText.append(name).append(": ").append(value).append("\r\n"));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocateFrom(JAVA_BYTE, body);
            MemorySegment stream = Win32.createMemStream(data, body.length);
            try {
                MemorySegment out = arena.allocate(ADDRESS);
                Com.callChecked("CreateWebResourceResponse", environment,
                        WebView2Abi.ICoreWebView2Environment.CREATE_WEB_RESOURCE_RESPONSE, Com.HR_RESPONSE,
                        stream, status, Native.allocWide(arena, ResourceService.reasonPhrase(status)),
                        Native.allocWide(arena, headerText.toString()), out);
                MemorySegment response = Native.readPointer(out);
                try {
                    Com.callChecked("put_Response", args,
                            WebView2Abi.ICoreWebView2WebResourceRequestedEventArgs.PUT_RESPONSE, Com.HR_PTR, response);
                } finally {
                    Com.release(response);
                }
            } finally {
                Com.release(stream); // the response holds its own reference
            }
        }
    }

    /** Asks for a deferral, which keeps the request open until {@link #complete} so a response can be given later. */
    static MemorySegment deferral(MemorySegment args) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            return Com.call(args, WebView2Abi.ICoreWebView2WebResourceRequestedEventArgs.GET_DEFERRAL, Com.HR_PTR, out) < 0
                    ? MemorySegment.NULL : Native.readPointer(out);
        }
    }

    /** Tells WebView2 the response is in place, and drops the deferral. */
    static void complete(MemorySegment deferral) {
        try {
            Com.call(deferral, WebView2Abi.ICoreWebView2Deferral.COMPLETE, Com.HR_VOID);
        } finally {
            Com.release(deferral);
        }
    }

    private static String wideString(MemorySegment object, int slot, Arena arena) {
        MemorySegment out = arena.allocate(ADDRESS);
        if (Com.call(object, slot, Com.HR_PTR, out) < 0) {
            return null;
        }
        MemorySegment raw = Native.readPointer(out);
        if (raw.equals(MemorySegment.NULL)) {
            return null;
        }
        String text = Native.readWide(raw);
        Win32.coTaskMemFree(raw);
        return text;
    }

    /** Header values by lower-case name; a header sent twice has its values joined with {@code ", "}. */
    private static Map<String, String> headers(MemorySegment request, Arena arena) {
        Map<String, String> result = new LinkedHashMap<>();
        MemorySegment out = arena.allocate(ADDRESS);
        if (Com.call(request, WebView2Abi.ICoreWebView2WebResourceRequest.GET_HEADERS, Com.HR_PTR, out) < 0) {
            return result;
        }
        MemorySegment headers = Native.readPointer(out);
        try {
            MemorySegment iteratorOut = arena.allocate(ADDRESS);
            if (Com.call(headers, WebView2Abi.ICoreWebView2HttpRequestHeaders.GET_ITERATOR, Com.HR_PTR, iteratorOut) < 0) {
                return result;
            }
            MemorySegment iterator = Native.readPointer(iteratorOut);
            try {
                MemorySegment flag = arena.allocate(JAVA_INT);
                MemorySegment name = arena.allocate(ADDRESS);
                MemorySegment value = arena.allocate(ADDRESS);
                while (Com.call(iterator, WebView2Abi.ICoreWebView2HttpHeadersCollectionIterator.GET_HAS_CURRENT_HEADER,
                        Com.HR_PTR, flag) >= 0 && flag.get(JAVA_INT, 0) != 0) {
                    if (Com.call(iterator, WebView2Abi.ICoreWebView2HttpHeadersCollectionIterator.GET_CURRENT_HEADER,
                            Com.HR_PTR_PTR, name, value) >= 0) {
                        MemorySegment rawName = Native.readPointer(name);
                        MemorySegment rawValue = Native.readPointer(value);
                        result.merge(Native.readWide(rawName).toLowerCase(Locale.ROOT), Native.readWide(rawValue), (a, b) -> a + ", " + b);
                        Win32.coTaskMemFree(rawName);
                        Win32.coTaskMemFree(rawValue);
                    }
                    if (Com.call(iterator, WebView2Abi.ICoreWebView2HttpHeadersCollectionIterator.MOVE_NEXT, Com.HR_PTR, flag) < 0
                            || flag.get(JAVA_INT, 0) == 0) {
                        break;
                    }
                }
            } finally {
                Com.release(iterator);
            }
        } finally {
            Com.release(headers);
        }
        return result;
    }

    /** The request body, empty if there is none, {@code null} if it is too large. */
    private static byte[] body(MemorySegment request, Arena arena) {
        MemorySegment out = arena.allocate(ADDRESS);
        if (Com.call(request, WebView2Abi.ICoreWebView2WebResourceRequest.GET_CONTENT, Com.HR_PTR, out) < 0) {
            return new byte[0];
        }
        MemorySegment stream = Native.readPointer(out);
        if (stream.equals(MemorySegment.NULL)) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            MemorySegment chunk = arena.allocate(READ_CHUNK);
            MemorySegment count = arena.allocate(JAVA_INT);
            while (true) {
                count.set(JAVA_INT, 0, 0);
                int hr = Com.call(stream, STREAM_READ, Com.HR_STREAM_READ, chunk, READ_CHUNK, count);
                int read = count.get(JAVA_INT, 0);
                if (read > 0) {
                    bytes.write(chunk.asSlice(0, read).toArray(JAVA_BYTE), 0, read);
                    if (bytes.size() > ResourceService.MAX_BODY) {
                        return null;
                    }
                }
                if (hr < 0 || read == 0) {
                    break;
                }
            }
            return bytes.toByteArray();
        } finally {
            Com.release(stream);
        }
    }
}
