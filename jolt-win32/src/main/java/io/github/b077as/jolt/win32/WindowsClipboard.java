package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.Clipboard;
import io.github.b077as.jolt.ClipboardContent;
import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.internal.ClipboardData;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The Windows clipboard: Win32 clipboard functions called from the UI thread, with the hidden dispatch window as owner.
 *
 * <p>Formats written: {@code CF_UNICODETEXT}; "HTML Format"; "PNG" plus {@code CF_DIBV5} (Windows derives {@code CF_DIB} from
 * it) for pictures, so programs that only know bitmaps still work; {@code CF_HDROP} for files. A sensitive value also carries the
 * three marker formats Windows documents for clipboard history, cloud clipboard and monitor programs.
 *
 * <p>The clipboard can be held open by another program for a moment, so opening retries briefly before giving up.
 */
final class WindowsClipboard implements Clipboard {

    static final int WM_CLIPBOARDUPDATE = 0x031D;

    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());

    private static final MethodHandle OPEN_CLIPBOARD = Native.downcall(USER32, "OpenClipboard", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CLOSE_CLIPBOARD = Native.downcall(USER32, "CloseClipboard", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle EMPTY_CLIPBOARD = Native.downcall(USER32, "EmptyClipboard", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle GET_CLIPBOARD_DATA = Native.downcall(USER32, "GetClipboardData", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle SET_CLIPBOARD_DATA = Native.downcall(USER32, "SetClipboardData",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle IS_FORMAT_AVAILABLE = Native.downcall(USER32, "IsClipboardFormatAvailable",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle REGISTER_FORMAT = Native.downcall(USER32, "RegisterClipboardFormatW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SEQUENCE_NUMBER = Native.downcall(USER32, "GetClipboardSequenceNumber", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle ADD_LISTENER = Native.downcall(USER32, "AddClipboardFormatListener", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle REMOVE_LISTENER = Native.downcall(USER32, "RemoveClipboardFormatListener",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GLOBAL_ALLOC = Native.downcall(KERNEL32, "GlobalAlloc", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_LONG));
    private static final MethodHandle GLOBAL_LOCK = Native.downcall(KERNEL32, "GlobalLock", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GLOBAL_UNLOCK = Native.downcall(KERNEL32, "GlobalUnlock", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GLOBAL_SIZE = Native.downcall(KERNEL32, "GlobalSize", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle GLOBAL_FREE = Native.downcall(KERNEL32, "GlobalFree", FunctionDescriptor.of(ADDRESS, ADDRESS));

    private static final int CF_UNICODETEXT = 13, CF_DIB = 8, CF_HDROP = 15, CF_DIBV5 = 17;
    private static final int GMEM_MOVEABLE = 0x2;
    private static final int OPEN_ATTEMPTS = 10, OPEN_WAIT_MS = 10;

    private static final int HTML_FORMAT = register("HTML Format");
    private static final int PNG_FORMAT = register("PNG");
    private static final int EXCLUDE_FROM_MONITORS = register("ExcludeClipboardContentFromMonitorProcessing");
    private static final int CAN_INCLUDE_IN_HISTORY = register("CanIncludeInClipboardHistory");
    private static final int CAN_UPLOAD_TO_CLOUD = register("CanUploadToCloudClipboard");

    private final WindowsApplication app;
    private final List<Consumer<Set<Format>>> listeners = new CopyOnWriteArrayList<>();
    /** UI thread only. */
    private boolean listening;
    /** Sequence number right after the last write that asked to be cleared; UI thread only. 0 when there is none. */
    private int pendingSequence;
    private boolean hasPending;

    WindowsClipboard(WindowsApplication app) {
        this.app = app;
    }

    // ---- Clipboard ----

    @Override
    public CompletableFuture<Set<Format>> formats() {
        return app.submit(this::currentFormats);
    }

    @Override
    public CompletableFuture<Optional<String>> readText() {
        return app.submit(() -> {
            byte[] data = read(CF_UNICODETEXT);
            if (data == null) {
                return Optional.empty();
            }
            return Optional.of(ClipboardData.untilNul(new String(data, 0, data.length & ~1, StandardCharsets.UTF_16LE)));
        });
    }

    @Override
    public CompletableFuture<Optional<String>> readHtml() {
        return app.submit(() -> {
            byte[] data = read(HTML_FORMAT);
            return data == null ? Optional.<String>empty() : Optional.ofNullable(ClipboardData.cfHtmlFragment(data));
        });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> readImage() {
        return app.submit(() -> {
            if (available(PNG_FORMAT)) {
                byte[] png = read(PNG_FORMAT);
                byte[] valid = png == null ? null : ClipboardData.trimPng(png);
                if (valid != null) {
                    return Optional.of(valid);
                }
            }
            if (available(CF_DIB)) {
                byte[] dib = read(CF_DIB);
                ClipboardData.Image image = dib == null ? null : ClipboardData.dibToRgba(dib, dib.length);
                if (image != null) {
                    return Optional.of(ClipboardData.encodePng(image));
                }
            }
            return Optional.<byte[]>empty();
        });
    }

    @Override
    public CompletableFuture<List<Path>> readFiles() {
        return app.submit(() -> {
            byte[] data = read(CF_HDROP);
            return data == null ? List.<Path>of() : parseDropFiles(data);
        });
    }

    @Override
    public CompletableFuture<Void> write(ClipboardContent content) {
        java.util.Objects.requireNonNull(content, "content");
        return app.submit(() -> {
            writeNow(content);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> clear() {
        return app.submit(() -> {
            openOrThrow();
            try {
                if ((int) invoke(EMPTY_CLIPBOARD) == 0) {
                    throw new JoltException("Emptying the clipboard failed");
                }
            } finally {
                closeQuietly();
            }
            return null;
        });
    }

    @Override
    public Subscription onChange(Consumer<Set<Format>> listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        app.dispatch(this::ensureListening);
        return () -> listeners.remove(listener);
    }

    // ---- change notifications (UI thread) ----

    private void ensureListening() {
        MemorySegment window = app.dispatchWindow();
        if (!listening && window != null && !listeners.isEmpty()) {
            listening = (int) invoke(ADD_LISTENER, window) != 0;
        }
    }

    /** {@code WM_CLIPBOARDUPDATE}: the content changed (also because of our own write). */
    void onUpdate() {
        if (listeners.isEmpty()) {
            return;
        }
        Set<Format> formats = currentFormats();
        for (Consumer<Set<Format>> listener : listeners) {
            try {
                listener.accept(formats);
            } catch (RuntimeException e) {
                System.getLogger("io.github.b077as.jolt.win32").log(System.Logger.Level.ERROR, "clipboard listener failed", e);
            }
        }
    }

    /** Application ending: a secret that is still on the clipboard goes with it. */
    void shutdown() {
        MemorySegment window = app.dispatchWindow();
        if (listening && window != null) {
            int ignored = (int) invoke(REMOVE_LISTENER, window);
            listening = false;
        }
        listeners.clear();
        clearIfStillOurs();
    }

    // ---- writing ----

    private void writeNow(ClipboardContent content) {
        openOrThrow();
        try {
            if ((int) invoke(EMPTY_CLIPBOARD) == 0) {
                throw new JoltException("Emptying the clipboard failed");
            }
            if (content.text() != null) {
                set(CF_UNICODETEXT, (content.text() + "\0").getBytes(StandardCharsets.UTF_16LE));
            }
            if (content.html() != null) {
                set(HTML_FORMAT, ClipboardData.cfHtml(content.html()));
            }
            if (content.png() != null) {
                set(PNG_FORMAT, content.png());
                ClipboardData.Image image = ClipboardData.decodePng(content.png());
                if (image != null) {
                    set(CF_DIBV5, ClipboardData.rgbaToDibV5(image)); // Windows derives CF_DIB and CF_BITMAP from it
                }
            }
            if (!content.files().isEmpty()) {
                set(CF_HDROP, dropFiles(content.files()));
            }
            if (content.sensitive()) {
                byte[] zero = new byte[4];
                set(EXCLUDE_FROM_MONITORS, zero);
                set(CAN_INCLUDE_IN_HISTORY, zero);
                set(CAN_UPLOAD_TO_CLOUD, zero);
            }
        } finally {
            closeQuietly();
        }
        if (content.clearAfter() != null) {
            int sequence = (int) invoke(SEQUENCE_NUMBER);
            pendingSequence = sequence;
            hasPending = true;
            CompletableFuture.delayedExecutor(content.clearAfter().toMillis(), TimeUnit.MILLISECONDS)
                    .execute(() -> app.dispatch(() -> {
                        if (hasPending && pendingSequence == sequence) {
                            clearIfStillOurs();
                        }
                    }));
        } else {
            hasPending = false; // this write replaced whatever was waiting to be cleared
        }
    }

    /** Empties the clipboard if nothing has been copied since the last write that asked for it. */
    private void clearIfStillOurs() {
        if (!hasPending) {
            return;
        }
        boolean ours = pendingSequence == (int) invoke(SEQUENCE_NUMBER);
        hasPending = false;
        if (!ours) {
            return;
        }
        try {
            openOrThrow();
            try {
                int ignored = (int) invoke(EMPTY_CLIPBOARD);
            } finally {
                closeQuietly();
            }
        } catch (JoltException e) {
            System.getLogger("io.github.b077as.jolt.win32").log(System.Logger.Level.WARNING,
                    "could not remove the secret from the clipboard: " + e.getMessage());
        }
    }

    /** A DROPFILES structure: 20-byte header (offset of the list, point, non-client flag, wide flag) and the NUL-separated, double-NUL-ended wide paths. */
    private static byte[] dropFiles(List<Path> files) {
        StringBuilder list = new StringBuilder();
        for (Path file : files) {
            list.append(file.toString()).append('\0');
        }
        list.append('\0');
        byte[] names = list.toString().getBytes(StandardCharsets.UTF_16LE);
        ByteBuffer b = ByteBuffer.allocate(20 + names.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(20).putInt(0).putInt(0).putInt(0).putInt(1);
        b.put(names);
        return b.array();
    }

    // ---- reading ----

    private Set<Format> currentFormats() {
        Set<Format> formats = EnumSet.noneOf(Format.class);
        if (available(CF_UNICODETEXT)) {
            formats.add(Format.TEXT);
        }
        if (available(HTML_FORMAT)) {
            formats.add(Format.HTML);
        }
        if (available(PNG_FORMAT) || available(CF_DIB) || available(CF_DIBV5)) {
            formats.add(Format.IMAGE);
        }
        if (available(CF_HDROP)) {
            formats.add(Format.FILES);
        }
        return formats;
    }

    /** The local files in a DROPFILES block. Everything is bounds checked: the block comes from another program. */
    private static List<Path> parseDropFiles(byte[] data) {
        if (data.length < 20) {
            return List.of();
        }
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long start = b.getInt(0) & 0xFFFFFFFFL;
        boolean wide = b.getInt(16) != 0;
        if (start < 20 || start > data.length) {
            return List.of();
        }
        Charset charset = wide ? StandardCharsets.UTF_16LE : nativeCharset();
        int unit = wide ? 2 : 1;
        List<Path> files = new ArrayList<>();
        int at = (int) start;
        while (at + unit <= data.length && files.size() < ClipboardData.MAX_FILES) {
            int end = at;
            while (end + unit <= data.length && !isNul(data, end, unit)) {
                end += unit;
            }
            if (end == at) {
                break; // an empty name ends the list
            }
            try {
                Path path = Path.of(new String(data, at, end - at, charset)).normalize();
                if (ClipboardData.isLocalAbsolutePath(path)) {
                    files.add(path);
                }
            } catch (InvalidPathException e) {
                // not a path this system can name: skip it
            }
            at = end + unit;
        }
        return files;
    }

    private static boolean isNul(byte[] data, int at, int unit) {
        return data[at] == 0 && (unit == 1 || data[at + 1] == 0);
    }

    private static Charset nativeCharset() {
        try {
            return Charset.forName(System.getProperty("native.encoding", "windows-1252"));
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    // ---- native helpers ----

    private static int register(String name) {
        try (Arena arena = Arena.ofConfined()) {
            int format = (int) invoke(REGISTER_FORMAT, Native.allocWide(arena, name));
            if (format == 0) {
                throw new JoltException("Registering the clipboard format " + name + " failed");
            }
            return format;
        }
    }

    private static boolean available(int format) {
        return (int) invoke(IS_FORMAT_AVAILABLE, format) != 0;
    }

    private void openOrThrow() {
        MemorySegment owner = app.dispatchWindow();
        for (int attempt = 0; attempt < OPEN_ATTEMPTS; attempt++) {
            if ((int) invoke(OPEN_CLIPBOARD, owner == null ? MemorySegment.NULL : owner) != 0) {
                return;
            }
            try {
                Thread.sleep(OPEN_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new JoltException("The clipboard is in use by another program");
    }

    private static void closeQuietly() {
        int ignored = (int) invoke(CLOSE_CLIPBOARD);
    }

    /** Puts {@code data} on the open clipboard as {@code format}; Windows owns the memory afterwards. */
    private static void set(int format, byte[] data) {
        MemorySegment memory = (MemorySegment) invoke(GLOBAL_ALLOC, GMEM_MOVEABLE, (long) Math.max(1, data.length));
        if (memory.equals(MemorySegment.NULL)) {
            throw new JoltException("Out of memory for the clipboard");
        }
        MemorySegment target = (MemorySegment) invoke(GLOBAL_LOCK, memory);
        if (target.equals(MemorySegment.NULL)) {
            MemorySegment ignored = (MemorySegment) invoke(GLOBAL_FREE, memory);
            throw new JoltException("Locking clipboard memory failed");
        }
        target.reinterpret(Math.max(1, data.length)).copyFrom(MemorySegment.ofArray(data));
        int ignored = (int) invoke(GLOBAL_UNLOCK, memory);
        MemorySegment result = (MemorySegment) invoke(SET_CLIPBOARD_DATA, format, memory);
        if (result.equals(MemorySegment.NULL)) {
            MemorySegment freed = (MemorySegment) invoke(GLOBAL_FREE, memory); // not taken over by Windows
            throw new JoltException("Putting data on the clipboard failed");
        }
    }

    /** The bytes of {@code format}, or {@code null} if it is not there. Opens and closes the clipboard. Fails if it is over the size limit. */
    private byte[] read(int format) {
        if (!available(format)) {
            return null;
        }
        openOrThrow();
        try {
            MemorySegment handle = (MemorySegment) invoke(GET_CLIPBOARD_DATA, format);
            if (handle.equals(MemorySegment.NULL)) {
                return null;
            }
            long size = (long) invoke(GLOBAL_SIZE, handle);
            if (size > ClipboardData.MAX_BYTES) {
                throw new JoltException("The clipboard content is larger than " + ClipboardData.MAX_BYTES + " bytes");
            }
            MemorySegment source = (MemorySegment) invoke(GLOBAL_LOCK, handle);
            if (source.equals(MemorySegment.NULL)) {
                return null;
            }
            try {
                return source.reinterpret(size).toArray(JAVA_BYTE);
            } finally {
                int ignored = (int) invoke(GLOBAL_UNLOCK, handle);
            }
        } finally {
            closeQuietly();
        }
    }

    private static Object invoke(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
