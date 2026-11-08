package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The few GTK 3 drag-and-drop calls Jolt needs, all on the GTK thread: reading what is being dropped on the web view (a
 * {@code text/uri-list} from the file manager) and starting a drag of files out of it.
 */
final class GtkDragDrop {

    static final String URI_LIST = "text/uri-list";
    private static final int GDK_ACTION_COPY = 1 << 1;
    private static final int URI_LIST_INFO = 1;

    private static final SymbolLookup GTK = SymbolLookup.libraryLookup("libgtk-3.so.0", Arena.global());
    private static final SymbolLookup GDK = SymbolLookup.libraryLookup("libgdk-3.so.0", Arena.global());

    private static final MethodHandle ATOM_INTERN = h("gdk_atom_intern", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle ATOM_NAME = h("gdk_atom_name", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle SELECTION_GET_TARGET = h("gtk_selection_data_get_target", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle SELECTION_GET_LENGTH = h("gtk_selection_data_get_length", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SELECTION_GET_DATA = h("gtk_selection_data_get_data_with_length",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle SELECTION_SET = h("gtk_selection_data_set",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle DRAG_GET_SOURCE_WIDGET = h("gtk_drag_get_source_widget", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle TARGET_LIST_NEW = h("gtk_target_list_new", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle TARGET_LIST_ADD = h("gtk_target_list_add",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle TARGET_LIST_UNREF = h("gtk_target_list_unref", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle WIDGET_GET_WINDOW = h("gtk_widget_get_window", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle DISPLAY_GET_DEFAULT = Native.downcall(GDK, "gdk_display_get_default", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle DISPLAY_GET_DEFAULT_SEAT = Native.downcall(GDK, "gdk_display_get_default_seat",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle SEAT_GET_POINTER = Native.downcall(GDK, "gdk_seat_get_pointer", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle WINDOW_GET_DEVICE_POSITION = Native.downcall(GDK, "gdk_window_get_device_position",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle DRAG_BEGIN = h("gtk_drag_begin_with_coordinates",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

    private GtkDragDrop() {}

    private static MethodHandle h(String name, FunctionDescriptor descriptor, Linker.Option... options) {
        return Native.downcall(GTK, name, descriptor, options);
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

    private static MemorySegment atom(String name) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) invoke(ATOM_INTERN, Gtk.utf8(arena, name), 0);
        }
    }

    /** Whether the drag comes from another program: inside this one (a link dragged within the page, or our own drag out) it does not. */
    static boolean isExternal(MemorySegment context) {
        return ((MemorySegment) invoke(DRAG_GET_SOURCE_WIDGET, context)).equals(MemorySegment.NULL);
    }

    /** The name of the target the {@code GtkSelectionData} was fetched for, for example {@code text/uri-list}; {@code null} if it has none. */
    static String targetName(MemorySegment selectionData) {
        MemorySegment target = (MemorySegment) invoke(SELECTION_GET_TARGET, selectionData);
        if (target.equals(MemorySegment.NULL)) {
            return null;
        }
        MemorySegment name = (MemorySegment) invoke(ATOM_NAME, target);
        if (name.equals(MemorySegment.NULL)) {
            return null;
        }
        try {
            return Gtk.readUtf8(name);
        } finally {
            Gtk.free(name);
        }
    }

    /** The bytes in {@code selectionData}, or {@code null} if there are none or more than {@code max}. */
    static byte[] data(MemorySegment selectionData, int max) {
        int length = (int) invoke(SELECTION_GET_LENGTH, selectionData);
        if (length <= 0 || length > max) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = (MemorySegment) invoke(SELECTION_GET_DATA, selectionData, arena.allocate(JAVA_INT));
            return bytes.equals(MemorySegment.NULL) ? null : bytes.reinterpret(length).toArray(JAVA_BYTE);
        }
    }

    /** Fills {@code selectionData} (in a {@code drag-data-get} handler) with {@code uriList}. */
    static void setUriList(MemorySegment selectionData, byte[] uriList) {
        try (Arena arena = Arena.ofConfined()) {
            invoke(SELECTION_SET, selectionData, atom(URI_LIST), 8, arena.allocateFrom(JAVA_BYTE, uriList), uriList.length);
        }
    }

    private static final int GDK_BUTTON1_MASK = 1 << 8;

    /** Whether the primary pointer button is down over {@code widget}'s window right now. */
    static boolean primaryButtonDown(MemorySegment widget) {
        MemorySegment window = (MemorySegment) invoke(WIDGET_GET_WINDOW, widget);
        MemorySegment display = (MemorySegment) invoke(DISPLAY_GET_DEFAULT);
        if (window.equals(MemorySegment.NULL) || display.equals(MemorySegment.NULL)) {
            return false;
        }
        MemorySegment seat = (MemorySegment) invoke(DISPLAY_GET_DEFAULT_SEAT, display);
        MemorySegment pointer = seat.equals(MemorySegment.NULL) ? seat : (MemorySegment) invoke(SEAT_GET_POINTER, seat);
        if (pointer.equals(MemorySegment.NULL)) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mask = arena.allocate(JAVA_INT);
            invoke(WINDOW_GET_DEVICE_POSITION, window, pointer, MemorySegment.NULL, MemorySegment.NULL, mask);
            return (mask.get(JAVA_INT, 0) & GDK_BUTTON1_MASK) != 0;
        }
    }

    /**
     * Starts a drag from {@code widget} offering a {@code text/uri-list}, copy only, with the primary button that is down now.
     * GTK asks for the list through the widget's {@code drag-data-get} signal.
     *
     * @return whether GTK started a drag
     */
    static boolean begin(MemorySegment widget) {
        if (!primaryButtonDown(widget)) {
            return false; // a drag needs a button to hold; without one GTK would wait for a click with the pointer grabbed
        }
        MemorySegment targets = (MemorySegment) invoke(TARGET_LIST_NEW, MemorySegment.NULL, 0);
        try {
            invoke(TARGET_LIST_ADD, targets, atom(URI_LIST), 0, URI_LIST_INFO);
            MemorySegment context = (MemorySegment) invoke(DRAG_BEGIN, widget, targets, GDK_ACTION_COPY, 1, MemorySegment.NULL, -1, -1);
            return !context.equals(MemorySegment.NULL);
        } finally {
            invoke(TARGET_LIST_UNREF, targets);
        }
    }
}
