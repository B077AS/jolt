package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.Clipboard;
import io.github.b077as.jolt.ClipboardContent;
import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.internal.ClipboardData;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
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
 * The CLIPBOARD selection through GTK 3, all on the GTK thread.
 *
 * <p>Reading uses the synchronous {@code gtk_clipboard_wait_*} calls, which run a nested main loop while the owning program
 * answers. Writing offers the content with {@code gtk_clipboard_set_with_data}: GTK keeps no copy, it asks us for the bytes when
 * a program pastes, so the content is held in a {@link Owned} registered under an id (see {@link Registry}) and a callback that
 * arrives after it was replaced finds nothing.
 *
 * <p>Targets offered: the text targets; {@code text/html}; {@code image/png}; {@code text/uri-list} plus
 * {@code x-special/gnome-copied-files} for files (the latter is what Nautilus, Nemo and Thunar paste as a copy). A sensitive value
 * also offers {@code x-kde-passwordManagerHint = secret}, which KDE's Klipper and other managers honour by not recording it.
 *
 * <p>The clipboard of an X11 or Wayland session belongs to the program that copied: when this process ends, its content
 * goes with it unless a clipboard manager took a copy.
 */
final class LinuxClipboard implements Clipboard {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.linux");

    private static final SymbolLookup GTK = SymbolLookup.libraryLookup("libgtk-3.so.0", Arena.global());
    private static final SymbolLookup PIXBUF = SymbolLookup.libraryLookup("libgdk_pixbuf-2.0.so.0", Arena.global());

    private static final MethodHandle ATOM_INTERN = h("gdk_atom_intern", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle CLIPBOARD_GET = h("gtk_clipboard_get", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle WAIT_FOR_CONTENTS = h("gtk_clipboard_wait_for_contents", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle WAIT_FOR_TEXT = h("gtk_clipboard_wait_for_text", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle WAIT_FOR_IMAGE = h("gtk_clipboard_wait_for_image", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle WAIT_IS_TARGET_AVAILABLE = h("gtk_clipboard_wait_is_target_available",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle WAIT_IS_TEXT_AVAILABLE = h("gtk_clipboard_wait_is_text_available", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle WAIT_IS_IMAGE_AVAILABLE = h("gtk_clipboard_wait_is_image_available", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SET_WITH_DATA = h("gtk_clipboard_set_with_data",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CLEAR = h("gtk_clipboard_clear", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle SELECTION_DATA_GET_LENGTH = h("gtk_selection_data_get_length", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SELECTION_DATA_GET_DATA = h("gtk_selection_data_get_data_with_length",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle SELECTION_DATA_GET_TARGET = h("gtk_selection_data_get_target", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle SELECTION_DATA_SET = h("gtk_selection_data_set",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle SELECTION_DATA_SET_TEXT = h("gtk_selection_data_set_text",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle SELECTION_DATA_FREE = h("gtk_selection_data_free", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle PIXBUF_SAVE_TO_BUFFER = Native.downcall(PIXBUF, "gdk_pixbuf_save_to_bufferv",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    private static final SymbolLookup GDK = SymbolLookup.libraryLookup("libgdk-3.so.0", Arena.global());
    private static final MethodHandle DISPLAY_GET_DEFAULT = Native.downcall(GDK, "gdk_display_get_default", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle DISPLAY_SUPPORTS_SELECTION_NOTIFICATION = Native.downcall(GDK,
            "gdk_display_supports_selection_notification", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static MethodHandle h(String name, FunctionDescriptor descriptor, Linker.Option... options) {
        return Native.downcall(GTK, name, descriptor, options);
    }

    // what the get callback is asked for: GtkTargetEntry.info
    private static final int INFO_TEXT = 0, INFO_HTML = 1, INFO_PNG = 2, INFO_URI_LIST = 3, INFO_GNOME_FILES = 4, INFO_HINT = 5;
    private static final String[] TEXT_TARGETS = {"UTF8_STRING", "text/plain;charset=utf-8", "text/plain", "STRING"};

    /** The bytes we offer while we own the clipboard. */
    record Owned(byte[] text, byte[] html, byte[] png, byte[] uriList, byte[] gnomeFiles, long clipboardId) {}

    private final LinuxApplication app;
    private final long id = Registry.add(this);
    private final List<Consumer<Set<Format>>> listeners = new CopyOnWriteArrayList<>();
    /** UI thread only. */
    private MemorySegment clipboard = MemorySegment.NULL;
    private boolean connected;
    private long ownedId;
    private long secretId;

    LinuxClipboard(LinuxApplication app) {
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
            // the raw bytes first: gtk_clipboard_wait_for_text rewrites line breaks (\r\n becomes \n)
            byte[] raw = contents("UTF8_STRING");
            if (raw != null) {
                return Optional.of(new String(raw, StandardCharsets.UTF_8));
            }
            MemorySegment text = (MemorySegment) invoke(WAIT_FOR_TEXT, board());
            if (text.equals(MemorySegment.NULL)) {
                return Optional.<String>empty();
            }
            try {
                String value = Gtk.readUtf8(text);
                return value.getBytes(StandardCharsets.UTF_8).length > ClipboardData.MAX_BYTES
                        ? failTooLarge() : Optional.of(value);
            } finally {
                Gtk.free(text);
            }
        });
    }

    @Override
    public CompletableFuture<Optional<String>> readHtml() {
        return app.submit(() -> {
            byte[] data = contents("text/html");
            return data == null ? Optional.<String>empty() : Optional.of(ClipboardData.decodeHtmlBytes(data));
        });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> readImage() {
        return app.submit(() -> {
            byte[] png = contents("image/png");
            byte[] valid = png == null ? null : ClipboardData.trimPng(png);
            if (valid != null) {
                return Optional.of(valid);
            }
            // some other picture format: let gdk-pixbuf convert it
            if ((int) invoke(WAIT_IS_IMAGE_AVAILABLE, board()) != 0) {
                byte[] converted = pixbufAsPng();
                if (converted != null) {
                    return Optional.of(converted);
                }
            }
            return Optional.<byte[]>empty();
        });
    }

    @Override
    public CompletableFuture<List<Path>> readFiles() {
        return app.submit(() -> {
            byte[] data = contents("text/uri-list");
            return data == null ? List.<Path>of() : ClipboardData.parseUriList(new String(data, StandardCharsets.UTF_8));
        });
    }

    @Override
    public CompletableFuture<Void> write(ClipboardContent content) {
        java.util.Objects.requireNonNull(content, "content");
        return app.submit(() -> {
            writeNow(content);
            notifyOwnChange();
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> clear() {
        return app.submit(() -> {
            invoke(CLEAR, board());
            notifyOwnChange();
            return null;
        });
    }

    @Override
    public Subscription onChange(Consumer<Set<Format>> listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        app.dispatch(this::ensureConnected);
        return () -> listeners.remove(listener);
    }

    // ---- change notifications (UI thread) ----

    private void ensureConnected() {
        if (!connected && !listeners.isEmpty()) {
            Gtk.connect(board(), "owner-change", Callbacks.CLIPBOARD_OWNER_CHANGE, MemorySegment.ofAddress(id));
            connected = true;
        }
    }

    /**
     * GTK 3 on Wayland never reports a change of the clipboard owner ({@code gdk_display_supports_selection_notification} is
     * false there), not even for our own writes. So our own writes and clears are announced here; what other programs copy
     * cannot be seen on such a display.
     */
    private void notifyOwnChange() {
        if (listeners.isEmpty() || selectionNotificationSupported()) {
            return; // the owner-change signal does it
        }
        onOwnerChange();
    }

    private static boolean selectionNotificationSupported() {
        try {
            MemorySegment display = (MemorySegment) DISPLAY_GET_DEFAULT.invokeExact();
            return !display.equals(MemorySegment.NULL) && (int) DISPLAY_SUPPORTS_SELECTION_NOTIFICATION.invokeExact(display) != 0;
        } catch (Throwable e) {
            return true; // unknown: rely on the signal
        }
    }

    /** The owner of the clipboard changed. Answered from the queue, never inside the signal: reading runs a nested main loop. */
    void onOwnerChange() {
        if (listeners.isEmpty()) {
            return;
        }
        app.dispatch(() -> {
            Set<Format> formats = currentFormats();
            for (Consumer<Set<Format>> listener : listeners) {
                try {
                    listener.accept(formats);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "clipboard listener failed", e);
                }
            }
        });
    }

    /** Application ending: a secret that is still offered is withdrawn. */
    void shutdown() {
        if (secretId != 0 && ownedId == secretId && !clipboard.equals(MemorySegment.NULL)) {
            invoke(CLEAR, clipboard);
        }
        listeners.clear();
        Registry.remove(id);
    }

    // ---- GTK callbacks (UI thread) ----

    /** A program asked for the content in the form {@code info} stands for. */
    static void provide(long ownedId, MemorySegment selectionData, int info) {
        Owned owned = Registry.get(ownedId, Owned.class);
        if (owned == null) {
            return; // replaced or cleared: the request is answered with nothing
        }
        try (Arena arena = Arena.ofConfined()) {
            switch (info) {
                case INFO_TEXT -> {
                    if (owned.text() != null) {
                        MemorySegment text = Gtk.utf8(arena, new String(owned.text(), StandardCharsets.UTF_8));
                        int ignored = (int) invoke(SELECTION_DATA_SET_TEXT, selectionData, text, -1);
                    }
                }
                case INFO_HTML -> set(arena, selectionData, owned.html());
                case INFO_PNG -> set(arena, selectionData, owned.png());
                case INFO_URI_LIST -> set(arena, selectionData, owned.uriList());
                case INFO_GNOME_FILES -> set(arena, selectionData, owned.gnomeFiles());
                case INFO_HINT -> set(arena, selectionData, "secret".getBytes(StandardCharsets.US_ASCII));
                default -> { }
            }
        }
    }

    /** We lost the clipboard (or it was replaced or cleared): whoever copied last owns it now. */
    static void cleared(long clearedId) {
        Owned owned = Registry.take(clearedId, Owned.class);
        LinuxClipboard board = owned == null ? null : Registry.get(owned.clipboardId(), LinuxClipboard.class);
        if (board != null) {
            board.onCleared(clearedId);
        }
    }

    private void onCleared(long clearedId) {
        if (ownedId == clearedId) {
            ownedId = 0;
        }
        if (secretId == clearedId) {
            secretId = 0;
        }
    }

    private static void set(Arena arena, MemorySegment selectionData, byte[] data) {
        if (data == null) {
            return;
        }
        MemorySegment target = (MemorySegment) invoke(SELECTION_DATA_GET_TARGET, selectionData);
        invoke(SELECTION_DATA_SET, selectionData, target, 8, arena.allocateFrom(JAVA_BYTE, data), data.length);
    }

    // ---- writing ----

    private void writeNow(ClipboardContent content) {
        List<String> targets = new ArrayList<>();
        List<Integer> infos = new ArrayList<>();
        byte[] text = null, html = null, png = null, uriList = null, gnomeFiles = null;
        if (content.text() != null) {
            text = content.text().getBytes(StandardCharsets.UTF_8);
            for (String target : TEXT_TARGETS) {
                targets.add(target);
                infos.add(INFO_TEXT);
            }
        }
        if (content.html() != null) {
            html = content.html().getBytes(StandardCharsets.UTF_8);
            targets.add("text/html");
            infos.add(INFO_HTML);
        }
        if (content.png() != null) {
            png = content.png();
            targets.add("image/png");
            infos.add(INFO_PNG);
        }
        if (!content.files().isEmpty()) {
            uriList = ClipboardData.uriList(content.files()).getBytes(StandardCharsets.UTF_8);
            StringBuilder gnome = new StringBuilder("copy");
            for (Path file : content.files()) {
                gnome.append('\n').append(file.toUri().toASCIIString());
            }
            gnomeFiles = gnome.toString().getBytes(StandardCharsets.UTF_8);
            targets.add("text/uri-list");
            infos.add(INFO_URI_LIST);
            targets.add("x-special/gnome-copied-files");
            infos.add(INFO_GNOME_FILES);
        }
        if (content.sensitive()) {
            targets.add("x-kde-passwordManagerHint");
            infos.add(INFO_HINT);
        }

        long newId = Registry.add(new Owned(text, html, png, uriList, gnomeFiles, id));
        boolean accepted;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(16L * targets.size(), 8); // GtkTargetEntry { char* target; guint flags; guint info; }
            for (int i = 0; i < targets.size(); i++) {
                table.set(ADDRESS, 16L * i, Gtk.utf8(arena, targets.get(i)));
                table.set(JAVA_INT, 16L * i + 8, 0);
                table.set(JAVA_INT, 16L * i + 12, infos.get(i));
            }
            accepted = (int) invoke(SET_WITH_DATA, board(), table, targets.size(), Callbacks.CLIPBOARD_GET, Callbacks.CLIPBOARD_CLEAR,
                    MemorySegment.ofAddress(newId)) != 0;
        }
        if (!accepted) {
            Registry.remove(newId);
            throw new JoltException("Putting data on the clipboard failed");
        }
        // the previous content's clear callback (run inside the call above) has already reset these
        ownedId = newId;
        secretId = 0;
        if (content.clearAfter() != null) {
            secretId = newId;
            CompletableFuture.delayedExecutor(content.clearAfter().toMillis(), TimeUnit.MILLISECONDS)
                    .execute(() -> app.dispatch(() -> {
                        if (ownedId == newId && !clipboard.equals(MemorySegment.NULL)) {
                            invoke(CLEAR, clipboard); // still ours: nothing was copied since
                        }
                    }));
        }
    }

    // ---- reading ----

    private Set<Format> currentFormats() {
        MemorySegment board = board();
        Set<Format> formats = EnumSet.noneOf(Format.class);
        if ((int) invoke(WAIT_IS_TEXT_AVAILABLE, board) != 0) {
            formats.add(Format.TEXT);
        }
        if (targetAvailable("text/html")) {
            formats.add(Format.HTML);
        }
        if (targetAvailable("image/png") || (int) invoke(WAIT_IS_IMAGE_AVAILABLE, board) != 0) {
            formats.add(Format.IMAGE);
        }
        if (targetAvailable("text/uri-list")) {
            formats.add(Format.FILES);
        }
        return formats;
    }

    private boolean targetAvailable(String target) {
        return (int) invoke(WAIT_IS_TARGET_AVAILABLE, board(), atom(target)) != 0;
    }

    /** The bytes offered for {@code target}, or {@code null} if it is not offered. Fails over the size limit. */
    private byte[] contents(String target) {
        MemorySegment data = (MemorySegment) invoke(WAIT_FOR_CONTENTS, board(), atom(target));
        if (data.equals(MemorySegment.NULL)) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            int length = (int) invoke(SELECTION_DATA_GET_LENGTH, data);
            if (length < 0) {
                return null;
            }
            if (length > ClipboardData.MAX_BYTES) {
                return failTooLarge();
            }
            MemorySegment lengthOut = arena.allocate(JAVA_INT);
            MemorySegment bytes = (MemorySegment) invoke(SELECTION_DATA_GET_DATA, data, lengthOut);
            if (bytes.equals(MemorySegment.NULL)) {
                return null;
            }
            return bytes.reinterpret(length).toArray(JAVA_BYTE);
        } finally {
            invoke(SELECTION_DATA_FREE, data);
        }
    }

    /** The clipboard picture, decoded by gdk-pixbuf (whatever format it was in) and written out as PNG; {@code null} on any failure. */
    private byte[] pixbufAsPng() {
        MemorySegment pixbuf = (MemorySegment) invoke(WAIT_FOR_IMAGE, board());
        if (pixbuf.equals(MemorySegment.NULL)) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bufferOut = arena.allocate(ADDRESS);
            MemorySegment sizeOut = arena.allocate(JAVA_LONG);
            MemorySegment errorOut = arena.allocate(ADDRESS);
            errorOut.set(ADDRESS, 0, MemorySegment.NULL);
            boolean ok = (int) invoke(PIXBUF_SAVE_TO_BUFFER, pixbuf, bufferOut, sizeOut, Gtk.utf8(arena, "png"), MemorySegment.NULL,
                    MemorySegment.NULL, errorOut) != 0;
            if (!ok) {
                MemorySegment error = Native.readPointer(errorOut);
                if (!error.equals(MemorySegment.NULL)) {
                    Gtk.errorFree(error);
                }
                return null;
            }
            MemorySegment buffer = Native.readPointer(bufferOut);
            long size = sizeOut.get(JAVA_LONG, 0);
            try {
                return size > ClipboardData.MAX_BYTES ? null : buffer.reinterpret(size).toArray(JAVA_BYTE);
            } finally {
                Gtk.free(buffer);
            }
        } finally {
            Gtk.unref(pixbuf);
        }
    }

    private static <T> T failTooLarge() {
        throw new JoltException("The clipboard content is larger than " + ClipboardData.MAX_BYTES + " bytes");
    }

    // ---- native helpers ----

    private MemorySegment board() {
        if (clipboard.equals(MemorySegment.NULL)) {
            clipboard = (MemorySegment) invoke(CLIPBOARD_GET, atom("CLIPBOARD"));
        }
        return clipboard;
    }

    private static MemorySegment atom(String name) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) invoke(ATOM_INTERN, Gtk.utf8(arena, name), 0);
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
