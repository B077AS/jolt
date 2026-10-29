package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * {@code GtkFileChooserNative}: the file dialog that GTK shows itself, or hands to the desktop portal when the app is sandboxed
 * (Flatpak, Snap) so the dialog is the desktop's own. GTK is used from the UI thread only.
 */
final class GtkDialogs {

    static final int ACTION_OPEN = 0, ACTION_SAVE = 1, ACTION_SELECT_FOLDER = 2;
    static final int RESPONSE_ACCEPT = -3, RESPONSE_CANCEL = -6;

    private static final SymbolLookup GTK = SymbolLookup.libraryLookup("libgtk-3.so.0", Arena.global());
    private static final SymbolLookup GOBJECT = SymbolLookup.libraryLookup("libgobject-2.0.so.0", Arena.global());
    private static final SymbolLookup GLIB = SymbolLookup.libraryLookup("libglib-2.0.so.0", Arena.global());

    private static final MethodHandle NATIVE_NEW = Native.downcall(GTK, "gtk_file_chooser_native_new",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle NATIVE_RUN = Native.downcall(GTK, "gtk_native_dialog_run", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle NATIVE_SET_MODAL = Native.downcall(GTK, "gtk_native_dialog_set_modal",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle SET_SELECT_MULTIPLE = Native.downcall(GTK, "gtk_file_chooser_set_select_multiple",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle SET_OVERWRITE = Native.downcall(GTK, "gtk_file_chooser_set_do_overwrite_confirmation",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle SET_CURRENT_NAME = Native.downcall(GTK, "gtk_file_chooser_set_current_name",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle SET_CURRENT_FOLDER = Native.downcall(GTK, "gtk_file_chooser_set_current_folder",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle SET_FILENAME = Native.downcall(GTK, "gtk_file_chooser_set_filename",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle FILTER_NEW = Native.downcall(GTK, "gtk_file_filter_new", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle FILTER_SET_NAME = Native.downcall(GTK, "gtk_file_filter_set_name",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle FILTER_ADD_PATTERN = Native.downcall(GTK, "gtk_file_filter_add_pattern",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle ADD_FILTER = Native.downcall(GTK, "gtk_file_chooser_add_filter",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle SET_FILTER = Native.downcall(GTK, "gtk_file_chooser_set_filter",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle GET_FILENAME = Native.downcall(GTK, "gtk_file_chooser_get_filename",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GET_FILENAMES = Native.downcall(GTK, "gtk_file_chooser_get_filenames",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle SLIST_FREE = Native.downcall(GLIB, "g_slist_free", FunctionDescriptor.ofVoid(ADDRESS));
    /** {@code g_signal_emit_by_name(instance, "response", int)}: what a button press does, for a native dialog. */
    private static final MethodHandle EMIT_RESPONSE = Native.downcall(GOBJECT, "g_signal_emit_by_name",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT), Linker.Option.firstVariadicArg(2));

    // GtkFileChooserDialog: an ordinary window, so it can be given a size (the native dialog cannot)
    private static final MethodHandle DIALOG_NEW = Native.downcall(GTK, "gtk_file_chooser_dialog_new",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
            Linker.Option.firstVariadicArg(3));
    private static final MethodHandle DIALOG_RUN = Native.downcall(GTK, "gtk_dialog_run", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle DIALOG_RESPONSE = Native.downcall(GTK, "gtk_dialog_response", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle WINDOW_SET_DEFAULT_SIZE = Native.downcall(GTK, "gtk_window_set_default_size",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle WINDOW_SET_MODAL = Native.downcall(GTK, "gtk_window_set_modal", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle WIDGET_DESTROY = Native.downcall(GTK, "gtk_widget_destroy", FunctionDescriptor.ofVoid(ADDRESS));

    /**
     * A file chooser: {@code plain} is a {@code GtkFileChooserDialog} (a window), otherwise a {@code GtkFileChooserNative}. Both
     * implement {@code GtkFileChooser}, so the chooser calls take {@link #handle()} either way.
     */
    record Dialog(MemorySegment handle, boolean plain) {}

    private GtkDialogs() {}

    private static Object call(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /**
     * A new dialog owned by {@code parent}; give it back with {@link #release}. With a {@code width} or {@code height} (logical pixels) it is GTK's
     * own chooser window, sized as asked; without, the native one, which uses the desktop portal when the app is sandboxed.
     */
    static Dialog create(Arena arena, String title, MemorySegment parent, int action, String acceptLabel, int width, int height) {
        if (width > 0 || height > 0) {
            MemorySegment dialog = (MemorySegment) call(DIALOG_NEW, Gtk.utf8(arena, title), parent, action,
                    Gtk.utf8(arena, "_Cancel"), RESPONSE_CANCEL, Gtk.utf8(arena, acceptLabel), RESPONSE_ACCEPT, MemorySegment.NULL);
            call(WINDOW_SET_MODAL, dialog, 1);
            call(WINDOW_SET_DEFAULT_SIZE, dialog, width > 0 ? width : -1, height > 0 ? height : -1);
            return new Dialog(dialog, true);
        }
        MemorySegment dialog = (MemorySegment) call(NATIVE_NEW, Gtk.utf8(arena, title), parent, action,
                Gtk.utf8(arena, acceptLabel), Gtk.utf8(arena, "_Cancel"));
        call(NATIVE_SET_MODAL, dialog, 1);
        return new Dialog(dialog, false);
    }

    static void release(Dialog dialog) {
        if (dialog.plain()) {
            call(WIDGET_DESTROY, dialog.handle());
        } else {
            Gtk.unref(dialog.handle());
        }
    }

    static void setSelectMultiple(MemorySegment dialog, boolean multiple) {
        call(SET_SELECT_MULTIPLE, dialog, multiple ? 1 : 0);
    }

    static void setOverwriteConfirmation(MemorySegment dialog) {
        call(SET_OVERWRITE, dialog, 1);
    }

    static void setCurrentName(Arena arena, MemorySegment dialog, String name) {
        call(SET_CURRENT_NAME, dialog, Gtk.utf8(arena, name));
    }

    static void setCurrentFolder(Arena arena, MemorySegment dialog, String folder) {
        call(SET_CURRENT_FOLDER, dialog, Gtk.utf8(arena, folder));
    }

    static void setFilename(Arena arena, MemorySegment dialog, String path) {
        call(SET_FILENAME, dialog, Gtk.utf8(arena, path));
    }

    /** Adds a file type to the dialog. GTK patterns are case sensitive, so each extension is offered in lower and upper case. */
    static MemorySegment addFilter(Arena arena, MemorySegment dialog, String name, List<String> extensions) {
        MemorySegment filter = (MemorySegment) call(FILTER_NEW);
        call(FILTER_SET_NAME, filter, Gtk.utf8(arena, name));
        for (String extension : extensions) {
            call(FILTER_ADD_PATTERN, filter, Gtk.utf8(arena, "*." + extension.toLowerCase(java.util.Locale.ROOT)));
            call(FILTER_ADD_PATTERN, filter, Gtk.utf8(arena, "*." + extension.toUpperCase(java.util.Locale.ROOT)));
        }
        call(ADD_FILTER, dialog, filter); // the chooser takes over the floating reference
        return filter;
    }

    static void setFilter(MemorySegment dialog, MemorySegment filter) {
        call(SET_FILTER, dialog, filter);
    }

    /** Shows the dialog and runs a nested main loop until it is answered; returns the response id. */
    static int run(Dialog dialog) {
        return (int) call(dialog.plain() ? DIALOG_RUN : NATIVE_RUN, dialog.handle());
    }

    /** Answers a dialog that is running, as if the user had pressed its button. */
    static void respond(Arena arena, Dialog dialog, int response) {
        if (dialog.plain()) {
            call(DIALOG_RESPONSE, dialog.handle(), response);
        } else {
            call(EMIT_RESPONSE, dialog.handle(), Gtk.utf8(arena, "response"), response);
        }
    }

    static String filename(MemorySegment dialog) {
        MemorySegment name = (MemorySegment) call(GET_FILENAME, dialog);
        try {
            return Gtk.readUtf8(name);
        } finally {
            if (!name.equals(MemorySegment.NULL)) {
                Gtk.free(name);
            }
        }
    }

    static List<String> filenames(MemorySegment dialog) {
        List<String> names = new ArrayList<>();
        MemorySegment list = (MemorySegment) call(GET_FILENAMES, dialog);
        for (MemorySegment node = list; !node.equals(MemorySegment.NULL); ) {
            MemorySegment cell = node.reinterpret(2 * ADDRESS.byteSize()); // GSList: data, next
            MemorySegment name = cell.get(ADDRESS, 0);
            names.add(Gtk.readUtf8(name));
            Gtk.free(name);
            node = cell.get(ADDRESS, ADDRESS.byteSize());
        }
        if (!list.equals(MemorySegment.NULL)) {
            call(SLIST_FREE, list);
        }
        return names;
    }
}
