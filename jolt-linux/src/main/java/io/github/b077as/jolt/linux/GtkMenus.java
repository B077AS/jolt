package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.ContextMenuInfo;
import io.github.b077as.jolt.Menu;
import io.github.b077as.jolt.Shortcut;
import io.github.b077as.jolt.internal.MenuLabels;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * GTK menus for {@link Menu}: a window's menu bar and the popup of a right click, plus the web view's editing commands and the
 * hit-test data of a context-menu request. UI thread only.
 *
 * <p>Kept apart from {@link Gtk} (like {@link GtkResources}) so that a system where something is missing loses only this feature:
 * {@link #available()} says whether every symbol was found. <b>This code has been compiled but not run</b>: no Linux session was
 * available when it was written.
 */
final class GtkMenus {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.linux");

    private static final int GTK_ACCEL_VISIBLE = 1;
    private static final int SHIFT_MASK = 1, CONTROL_MASK = 4, MOD1_MASK = 8, SUPER_MASK = 1 << 26;

    private record Handles(
            MethodHandle menuBarNew, MethodHandle menuNew, MethodHandle menuItemNew, MethodHandle checkMenuItemNew,
            MethodHandle separatorNew, MethodHandle setSubmenu, MethodHandle shellAppend, MethodHandle setSensitive,
            MethodHandle checkSetActive, MethodHandle checkGetActive, MethodHandle accelGroupNew, MethodHandle windowAddAccelGroup,
            MethodHandle windowRemoveAccelGroup, MethodHandle addAccelerator, MethodHandle popupAtPointer, MethodHandle attachToWidget,
            MethodHandle boxNew, MethodHandle boxPackStart, MethodHandle boxReorderChild, MethodHandle containerRemove,
            MethodHandle objectRef, MethodHandle objectRefSink, MethodHandle showAll, MethodHandle eventGetCoords,
            MethodHandle hitIsLink, MethodHandle hitIsImage, MethodHandle hitIsMedia, MethodHandle hitIsEditable, MethodHandle hitIsSelection,
            MethodHandle hitLinkUri, MethodHandle hitImageUri, MethodHandle hitMediaUri, MethodHandle executeEditingCommand,
            MethodHandle preferredHeight, MethodHandle widgetDestroy, MethodHandle objectUnref) {}

    /** {@code null} when this system lacks something. */
    private static final Handles H = load();
    private static final MemorySegment ACTIVATE = H == null ? null : stub("onActivate", MethodType.methodType(void.class, MemorySegment.class,
            MemorySegment.class), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MemorySegment DEACTIVATE = H == null ? null : stub("onDeactivate", MethodType.methodType(void.class,
            MemorySegment.class, MemorySegment.class), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    private GtkMenus() {}

    static boolean available() {
        return H != null;
    }

    private static Handles load() {
        try {
            Arena libs = Arena.global();
            SymbolLookup gobject = SymbolLookup.libraryLookup("libgobject-2.0.so.0", libs);
            SymbolLookup gtk = SymbolLookup.libraryLookup("libgtk-3.so.0", libs);
            SymbolLookup webkit = SymbolLookup.libraryLookup("libwebkit2gtk-4.1.so.0", libs);
            FunctionDescriptor newWidget = FunctionDescriptor.of(ADDRESS);
            FunctionDescriptor newLabelled = FunctionDescriptor.of(ADDRESS, ADDRESS);
            FunctionDescriptor hit = FunctionDescriptor.of(JAVA_INT, ADDRESS);
            FunctionDescriptor hitUri = FunctionDescriptor.of(ADDRESS, ADDRESS);
            return new Handles(
                    Native.downcall(gtk, "gtk_menu_bar_new", newWidget),
                    Native.downcall(gtk, "gtk_menu_new", newWidget),
                    Native.downcall(gtk, "gtk_menu_item_new_with_mnemonic", newLabelled),
                    Native.downcall(gtk, "gtk_check_menu_item_new_with_mnemonic", newLabelled),
                    Native.downcall(gtk, "gtk_separator_menu_item_new", newWidget),
                    Native.downcall(gtk, "gtk_menu_item_set_submenu", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                    Native.downcall(gtk, "gtk_menu_shell_append", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                    Native.downcall(gtk, "gtk_widget_set_sensitive", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT)),
                    Native.downcall(gtk, "gtk_check_menu_item_set_active", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT)),
                    Native.downcall(gtk, "gtk_check_menu_item_get_active", hit),
                    Native.downcall(gtk, "gtk_accel_group_new", newWidget),
                    Native.downcall(gtk, "gtk_window_add_accel_group", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                    Native.downcall(gtk, "gtk_window_remove_accel_group", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                    Native.downcall(gtk, "gtk_widget_add_accelerator",
                            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)),
                    Native.downcall(gtk, "gtk_menu_popup_at_pointer", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                    Native.downcall(gtk, "gtk_menu_attach_to_widget", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)),
                    Native.downcall(gtk, "gtk_box_new", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT)),
                    Native.downcall(gtk, "gtk_box_pack_start", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)),
                    Native.downcall(gtk, "gtk_box_reorder_child", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)),
                    Native.downcall(gtk, "gtk_container_remove", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                    Native.downcall(gobject, "g_object_ref", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                    Native.downcall(gobject, "g_object_ref_sink", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                    Native.downcall(gtk, "gtk_widget_show_all", FunctionDescriptor.ofVoid(ADDRESS)),
                    Native.downcall(gtk, "gdk_event_get_coords", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)),
                    Native.downcall(webkit, "webkit_hit_test_result_context_is_link", hit),
                    Native.downcall(webkit, "webkit_hit_test_result_context_is_image", hit),
                    Native.downcall(webkit, "webkit_hit_test_result_context_is_media", hit),
                    Native.downcall(webkit, "webkit_hit_test_result_context_is_editable", hit),
                    Native.downcall(webkit, "webkit_hit_test_result_context_is_selection", hit),
                    Native.downcall(webkit, "webkit_hit_test_result_get_link_uri", hitUri),
                    Native.downcall(webkit, "webkit_hit_test_result_get_image_uri", hitUri),
                    Native.downcall(webkit, "webkit_hit_test_result_get_media_uri", hitUri),
                    Native.downcall(webkit, "webkit_web_view_execute_editing_command", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                    Native.downcall(gtk, "gtk_widget_get_preferred_height", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)),
                    Native.downcall(gtk, "gtk_widget_destroy", FunctionDescriptor.ofVoid(ADDRESS)),
                    Native.downcall(gobject, "g_object_unref", FunctionDescriptor.ofVoid(ADDRESS)));
        } catch (RuntimeException | Error e) { // a missing library or symbol
            LOG.log(System.Logger.Level.WARNING, "Native menus are not available on this system: {0}", e.toString());
            return null;
        }
    }

    private static MemorySegment stub(String method, MethodType type, FunctionDescriptor descriptor) {
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(GtkMenus.class, method, type);
            return Native.LINKER.upcallStub(Native.guarded(target, 0), descriptor, Arena.global());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ---- what the menus remember ----

    /** What a menu item stands for; its registry id is the {@code user_data} of the item's {@code activate} signal. */
    private record Entry(Menu.Item item) {}

    /** A popup waiting for its {@code deactivate} signal, after which it is destroyed (not before: the item's activation comes later). */
    private record Popup(LinuxApplication app, Built built) {}

    /** A built GTK menu. */
    static final class Built {
        final MemorySegment root;
        /** A popup keeps its own reference (the menu is floating until something owns it); a bar belongs to the box it is in. */
        private boolean ownsReference;
        private final List<Long> ids = new ArrayList<>();

        private Built(MemorySegment root) {
            this.root = root;
        }

        /** Forgets the callbacks (the window is going away together with its widgets). */
        void release() {
            for (long id : ids) {
                Registry.remove(id);
            }
            ids.clear();
        }

        /** Removes the callbacks and destroys the widgets. */
        void destroy() {
            release();
            call(H.widgetDestroy, root);
            if (ownsReference) {
                call(H.objectUnref, root);
            }
        }
    }

    // ---- building ----

    /** A new accelerator group; add it to the window with {@link #addAccelGroup}. */
    static MemorySegment newAccelGroup() {
        return (MemorySegment) call(H.accelGroupNew);
    }

    static void addAccelGroup(MemorySegment window, MemorySegment group) {
        call(H.windowAddAccelGroup, window, group);
    }

    static void removeAccelGroup(MemorySegment window, MemorySegment group) {
        call(H.windowRemoveAccelGroup, window, group);
        call(H.objectUnref, group);
    }

    /** Builds a menu bar ({@code bar}, accelerators go to {@code accelGroup}) or a popup menu. The bar is not shown yet. */
    static Built build(Menu menu, boolean bar, MemorySegment accelGroup) {
        MemorySegment root = (MemorySegment) call(bar ? H.menuBarNew : H.menuNew);
        Built built = new Built(root);
        try {
            fill(root, menu.items(), built, accelGroup);
        } catch (RuntimeException | Error e) {
            built.destroy();
            throw e;
        }
        return built;
    }

    private static void fill(MemorySegment shell, List<Menu.Item> items, Built built, MemorySegment accelGroup) {
        try (Arena arena = Arena.ofConfined()) {
            for (Menu.Item item : items) {
                MemorySegment widget;
                switch (item) {
                    case Menu.Separator separator -> widget = (MemorySegment) call(H.separatorNew);
                    case Menu.Action action -> {
                        widget = (MemorySegment) call(H.menuItemNew, Gtk.utf8(arena, MenuLabels.forGtk(action.label())));
                        finishEntry(widget, item, action.enabled(), action.accelerator(), built, accelGroup);
                    }
                    case Menu.Check check -> {
                        widget = (MemorySegment) call(H.checkMenuItemNew, Gtk.utf8(arena, MenuLabels.forGtk(check.label())));
                        call(H.checkSetActive, widget, check.checked() ? 1 : 0); // before the signal is connected: it only emits "toggled"
                        finishEntry(widget, item, check.enabled(), check.accelerator(), built, accelGroup);
                    }
                    case Menu.Submenu submenu -> {
                        widget = (MemorySegment) call(H.menuItemNew, Gtk.utf8(arena, MenuLabels.forGtk(submenu.label())));
                        MemorySegment child = (MemorySegment) call(H.menuNew);
                        fill(child, submenu.items(), built, accelGroup);
                        call(H.setSubmenu, widget, child);
                        call(H.setSensitive, widget, submenu.enabled() ? 1 : 0);
                    }
                }
                call(H.shellAppend, shell, widget);
            }
        }
    }

    private static void finishEntry(MemorySegment widget, Menu.Item item, boolean enabled, Shortcut accelerator, Built built,
                                    MemorySegment accelGroup) {
        long id = Registry.add(new Entry(item));
        built.ids.add(id);
        Gtk.connect(widget, "activate", ACTIVATE, MemorySegment.ofAddress(id));
        call(H.setSensitive, widget, enabled ? 1 : 0);
        if (accelerator != null && enabled && !accelGroup.equals(MemorySegment.NULL)) {
            try (Arena arena = Arena.ofConfined()) {
                int keyval = GtkKeys.keyval(accelerator);
                int mods = (accelerator.ctrl() ? CONTROL_MASK : 0) | (accelerator.shift() ? SHIFT_MASK : 0)
                        | (accelerator.alt() ? MOD1_MASK : 0) | (accelerator.meta() ? SUPER_MASK : 0);
                call(H.addAccelerator, widget, Gtk.utf8(arena, "activate"), accelGroup, keyval, mods, GTK_ACCEL_VISIBLE);
            }
        }
    }

    /** {@code activate} on an item, by a click or by its accelerator. A check item has already flipped itself by now. */
    private static void onActivate(MemorySegment item, MemorySegment data) {
        Entry entry = Registry.get(data.address(), Entry.class);
        if (entry == null) {
            return;
        }
        try {
            if (entry.item() instanceof Menu.Action action) {
                action.onSelect().run();
            } else if (entry.item() instanceof Menu.Check check) {
                check.onToggle().accept((int) call(H.checkGetActive, item) != 0);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "a menu callback failed", e);
        }
    }

    // ---- popups ----

    /** Pops {@code menu} up at the pointer for {@code event} (the event of the context-menu request). Returns at once; the menu is destroyed when it closes. */
    static void popup(LinuxApplication app, MemorySegment webView, Menu menu, MemorySegment event) {
        Built built = build(menu, false, MemorySegment.NULL);
        MemorySegment root = built.root;
        call(H.objectRefSink, root); // own the floating reference, so the menu outlives the signal that closes it
        built.ownsReference = true;
        call(H.attachToWidget, root, webView, MemorySegment.NULL);
        long popupId = Registry.add(new Popup(app, built));
        Gtk.connect(root, "deactivate", DEACTIVATE, MemorySegment.ofAddress(popupId));
        call(H.showAll, root);
        call(H.popupAtPointer, root, event);
    }

    private static void onDeactivate(MemorySegment menu, MemorySegment data) {
        Popup popup = Registry.take(data.address(), Popup.class);
        if (popup != null) {
            popup.app().dispatch(popup.built()::destroy); // after the chosen item has been activated
        }
    }

    // ---- the menu bar's place in the window ----

    /** Puts a vertical box where the web view was, with the web view in it, and returns the box. */
    static MemorySegment makeRoomForBar(MemorySegment window, MemorySegment webView) {
        call(H.objectRef, webView); // removing it from the window would otherwise drop the last reference
        call(H.containerRemove, window, webView);
        MemorySegment box = (MemorySegment) call(H.boxNew, 1 /* GTK_ORIENTATION_VERTICAL */, 0);
        Gtk.containerAdd(window, box);
        call(H.boxPackStart, box, webView, 1, 1, 0);
        call(H.objectUnref, webView);
        Gtk.widgetShow(box);
        return box;
    }

    /** Puts the bar above the web view and shows it; returns the height it takes. */
    static int placeBar(MemorySegment box, MemorySegment bar) {
        call(H.boxPackStart, box, bar, 0, 0, 0);
        call(H.boxReorderChild, box, bar, 0);
        call(H.showAll, bar);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment minimum = arena.allocate(JAVA_INT);
            MemorySegment natural = arena.allocate(JAVA_INT);
            call(H.preferredHeight, bar, minimum, natural);
            return natural.get(JAVA_INT, 0);
        }
    }

    // ---- context menu requests ----

    /** What the hit-test result of a context-menu request says is under the pointer. */
    static ContextMenuInfo describe(MemorySegment hit, MemorySegment event, String pageUrl) {
        boolean link = (int) call(H.hitIsLink, hit) != 0;
        boolean image = (int) call(H.hitIsImage, hit) != 0;
        boolean media = (int) call(H.hitIsMedia, hit) != 0;
        String linkUri = link ? Gtk.readUtf8((MemorySegment) call(H.hitLinkUri, hit)) : "";
        String source = image ? Gtk.readUtf8((MemorySegment) call(H.hitImageUri, hit)) : media ? Gtk.readUtf8((MemorySegment) call(H.hitMediaUri, hit)) : "";
        int x = 0, y = 0;
        if (!event.equals(MemorySegment.NULL)) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment px = arena.allocate(JAVA_DOUBLE);
                MemorySegment py = arena.allocate(JAVA_DOUBLE);
                if ((int) call(H.eventGetCoords, event, px, py) != 0) {
                    x = (int) Math.round(px.get(JAVA_DOUBLE, 0));
                    y = (int) Math.round(py.get(JAVA_DOUBLE, 0));
                }
            }
        }
        // WebKitGTK does not tell the selected text here, only that there is a selection
        return new ContextMenuInfo(x, y, pageUrl, linkUri, source, (int) call(H.hitIsEditable, hit) != 0,
                (int) call(H.hitIsSelection, hit) != 0, "");
    }

    // ---- editing ----

    /** Runs a WebKit editing command ({@code "Cut"}, {@code "Copy"}, {@code "Paste"}, {@code "Undo"}, {@code "Redo"}, {@code "SelectAll"}). */
    static void execute(MemorySegment webView, String command) {
        try (Arena arena = Arena.ofConfined()) {
            call(H.executeEditingCommand, webView, Gtk.utf8(arena, command));
        }
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
}
