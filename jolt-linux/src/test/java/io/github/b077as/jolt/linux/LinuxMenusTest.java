package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.ContextMenuInfo;
import io.github.b077as.jolt.Menu;
import io.github.b077as.jolt.Shortcut;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.contract.WebWindowContract;
import io.github.b077as.jolt.internal.ffi.Native;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Menu bar, popup menus and the context menu on Linux. The native menus are read back with GTK's own accessors; items are activated
 * with {@code gtk_menu_item_activate}, accelerators with {@code gtk_accel_groups_activate}, and a right click is a synthesized GDK
 * button event put on the queue (so it works the same on X11 and Wayland).
 */
@EnabledOnOs(OS.LINUX)
class LinuxMenusTest {

    private static final SymbolLookup GTK = SymbolLookup.libraryLookup("libgtk-3.so.0", Arena.global());
    private static final SymbolLookup GDK = SymbolLookup.libraryLookup("libgdk-3.so.0", Arena.global());
    private static final SymbolLookup GOBJECT = SymbolLookup.libraryLookup("libgobject-2.0.so.0", Arena.global());

    private static final MethodHandle GET_CHILDREN = Native.downcall(GTK, "gtk_container_get_children", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GET_BIN_CHILD = Native.downcall(GTK, "gtk_bin_get_child", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle ITEM_LABEL = Native.downcall(GTK, "gtk_menu_item_get_label", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle ITEM_SUBMENU = Native.downcall(GTK, "gtk_menu_item_get_submenu", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle ITEM_ACTIVATE = Native.downcall(GTK, "gtk_menu_item_activate", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle SENSITIVE = Native.downcall(GTK, "gtk_widget_get_sensitive", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CHECK_ACTIVE = Native.downcall(GTK, "gtk_check_menu_item_get_active", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle ACCEL_ACTIVATE = Native.downcall(GTK, "gtk_accel_groups_activate",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle MENUS_OF = Native.downcall(GTK, "gtk_menu_get_for_attach_widget", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle MENU_POPDOWN = Native.downcall(GTK, "gtk_menu_shell_cancel", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle MAPPED = Native.downcall(GTK, "gtk_widget_get_mapped", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle WIDGET_WINDOW = Native.downcall(GTK, "gtk_widget_get_window", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle TYPE_NAME = Native.downcall(GOBJECT, "g_type_name", FunctionDescriptor.of(ADDRESS, JAVA_LONG));
    private static final MethodHandle OBJECT_REF = Native.downcall(GOBJECT, "g_object_ref", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle EVENT_NEW = Native.downcall(GDK, "gdk_event_new", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle EVENT_PUT = Native.downcall(GDK, "gdk_event_put", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle EVENT_FREE = Native.downcall(GDK, "gdk_event_free", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle EVENT_SET_DEVICE = Native.downcall(GDK, "gdk_event_set_device", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle DEFAULT_DISPLAY = Native.downcall(GDK, "gdk_display_get_default", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle DEFAULT_SEAT = Native.downcall(GDK, "gdk_display_get_default_seat", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle SEAT_POINTER = Native.downcall(GDK, "gdk_seat_get_pointer", FunctionDescriptor.of(ADDRESS, ADDRESS));

    private static final int GDK_BUTTON_PRESS = 4, GDK_BUTTON_RELEASE = 7;
    private static final int CONTROL = 4, SHIFT = 1, MOD1 = 8;

    // ---- helpers ----

    private static Object call(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Runs on the UI thread and waits. */
    private static <T> T onUi(Application app, Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        app.dispatch(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private static List<MemorySegment> children(MemorySegment container) {
        List<MemorySegment> items = new ArrayList<>();
        MemorySegment node = (MemorySegment) call(GET_CHILDREN, container);
        while (!node.equals(MemorySegment.NULL)) {
            MemorySegment cell = node.reinterpret(24);
            items.add(cell.get(ADDRESS, 0));
            node = cell.get(ADDRESS, 8);
        }
        return items;
    }

    private static String typeName(MemorySegment object) {
        MemorySegment instance = object.reinterpret(8);
        long gType = instance.get(ADDRESS, 0).reinterpret(8).get(JAVA_LONG, 0); // GTypeInstance.g_class -> GTypeClass.g_type
        return Gtk.readUtf8((MemorySegment) call(TYPE_NAME, gType));
    }

    /** One entry per item: {@code label:flags}, flags c checked, g disabled, s separator, p submenu (followed by its items in braces). */
    private static String describe(MemorySegment shell) {
        StringBuilder text = new StringBuilder();
        for (MemorySegment item : children(shell)) {
            if (text.length() > 0) {
                text.append(" | ");
            }
            String type = typeName(item);
            boolean separator = type.equals("GtkSeparatorMenuItem");
            MemorySegment sub = separator ? MemorySegment.NULL : (MemorySegment) call(ITEM_SUBMENU, item);
            text.append(separator ? "-" : Gtk.readUtf8((MemorySegment) call(ITEM_LABEL, item))).append(':')
                    .append(type.equals("GtkCheckMenuItem") && (int) call(CHECK_ACTIVE, item) != 0 ? 'c' : '.')
                    .append((int) call(SENSITIVE, item) == 0 && !separator ? 'g' : '.')
                    .append(separator ? 's' : '.')
                    .append(sub.equals(MemorySegment.NULL) ? '.' : 'p');
            if (!sub.equals(MemorySegment.NULL)) {
                text.append('{').append(describe(sub)).append('}');
            }
        }
        return text.toString();
    }

    /** The menu bar of a window: window -> box -> [bar, web view]. */
    private static MemorySegment barOf(WebWindow window) {
        MemorySegment box = (MemorySegment) call(GET_BIN_CHILD, ((LinuxWindow) window).nativeWindow());
        assertEquals("GtkBox", typeName(box), "with a bar the window holds a box");
        MemorySegment bar = children(box).get(0);
        assertEquals("GtkMenuBar", typeName(bar));
        return bar;
    }

    /** The {@code index}th item of a shell, for activation. */
    private static MemorySegment item(MemorySegment shell, int... path) {
        MemorySegment current = shell;
        for (int i = 0; i < path.length; i++) {
            MemorySegment item = children(current).get(path[i]);
            if (i == path.length - 1) {
                return item;
            }
            current = (MemorySegment) call(ITEM_SUBMENU, item);
        }
        throw new IllegalArgumentException();
    }

    /** The menus attached to the web view. WebKit keeps an (empty) one of its own there once it has raised a context-menu request. */
    private static List<MemorySegment> attached(WebWindow window) {
        MemorySegment list = (MemorySegment) call(MENUS_OF, ((LinuxWindow) window).nativeWebView());
        List<MemorySegment> menus = new ArrayList<>();
        while (!list.equals(MemorySegment.NULL)) {
            MemorySegment cell = list.reinterpret(24);
            menus.add(cell.get(ADDRESS, 0));
            list = cell.get(ADDRESS, 8);
        }
        return menus;
    }

    private static boolean accelerator(WebWindow window, int keyval, int mods) {
        return (int) call(ACCEL_ACTIVATE, ((LinuxWindow) window).nativeWindow(), keyval, mods) != 0;
    }

    /** A real button event on the web view's GdkWindow, queued like one from the pointer (works on X11 and Wayland). */
    private static void click(WebWindow window, int button, int x, int y) {
        MemorySegment gdkWindow = (MemorySegment) call(WIDGET_WINDOW, ((LinuxWindow) window).nativeWebView());
        MemorySegment display = (MemorySegment) call(DEFAULT_DISPLAY);
        MemorySegment device = (MemorySegment) call(SEAT_POINTER, call(DEFAULT_SEAT, display));
        for (int type : new int[] {GDK_BUTTON_PRESS, GDK_BUTTON_RELEASE}) {
            MemorySegment event = ((MemorySegment) call(EVENT_NEW, type)).reinterpret(96);
            event.set(ADDRESS, 8, (MemorySegment) call(OBJECT_REF, gdkWindow)); // GdkEventButton.window, owned by the event
            event.set(JAVA_BYTE, 16, (byte) 1);                                  // send_event
            event.set(JAVA_DOUBLE, 24, (double) x);
            event.set(JAVA_DOUBLE, 32, (double) y);
            event.set(JAVA_INT, 52, button);
            event.set(JAVA_DOUBLE, 64, (double) x);
            event.set(JAVA_DOUBLE, 72, (double) y);
            call(EVENT_SET_DEVICE, event, device);
            call(EVENT_PUT, event);
            call(EVENT_FREE, event);
        }
    }

    // ---- menu bar ----

    @Test
    void aMenuBarBecomesAGtkBarWithLabelsChecksDisabledEntriesAndSubmenus() throws Exception {
        CompletableFuture<String> seen = new CompletableFuture<>();
        WebWindowContract.runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            window.ready().thenRunAsync(() -> {
                assertTrue(window.supportsMenuBar());
                window.setMenuBar(Menu.of(
                        Menu.submenu("&File", Menu.action("&New", "Ctrl+N", () -> {}), Menu.separator(), Menu.disabled("Save"),
                                Menu.submenu("Recent", Menu.action("a_b", () -> {}))),
                        Menu.submenu("&View", Menu.check("&Dense", true, on -> {}), Menu.check("Wrap", false, on -> {}))));
                try {
                    seen.complete(onUi(app, () -> describe(barOf(window))));
                } catch (Exception e) {
                    seen.completeExceptionally(e);
                }
                window.close();
            });
        });
        assertEquals("_File:...p{_New:.... | -:..s. | Save:.g.. | Recent:...p{a__b:....}} | _View:...p{_Dense:c... | Wrap:....}", seen.getNow(null));
    }

    @Test
    void choosingBarEntriesRunsTheCallbacksAndAChecksMarkFlipsEachTime() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CompletableFuture<String> marks = new CompletableFuture<>();
        WebWindowContract.runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            window.ready().thenRunAsync(() -> {
                window.setMenuBar(Menu.of(
                        Menu.submenu("File", Menu.action("One", () -> events.add("one"))),
                        Menu.submenu("View", Menu.check("Dense", false, on -> events.add("dense " + on)))));
                try {
                    onUi(app, () -> {
                        MemorySegment bar = barOf(window);
                        call(ITEM_ACTIVATE, item(bar, 0, 0));
                        call(ITEM_ACTIVATE, item(bar, 1, 0));
                        call(ITEM_ACTIVATE, item(bar, 1, 0));
                        call(ITEM_ACTIVATE, item(bar, 1, 0));
                        marks.complete(describe(bar));
                        return null;
                    });
                } catch (Exception e) {
                    marks.completeExceptionally(e);
                }
                window.close();
            });
        });
        assertEquals(List.of("one", "dense true", "dense false", "dense true"), events);
        assertTrue(marks.getNow("").contains("Dense:c..."), "after three toggles the mark is on: " + marks.getNow(null));
    }

    @Test
    void anAcceleratorOfTheBarRunsItsEntryAndADisabledOneDoesNot() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CompletableFuture<List<Boolean>> handled = new CompletableFuture<>();
        WebWindowContract.runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            window.ready().thenRunAsync(() -> {
                window.setMenuBar(Menu.of(Menu.submenu("File",
                        Menu.action("New", "Ctrl+Shift+N", () -> events.add("new")),
                        Menu.check("Dense", false, "Ctrl+Alt+F10", on -> events.add("dense " + on)),
                        Menu.action("Off", Shortcut.parse("Ctrl+K"), () -> events.add("off")))));
                try {
                    handled.complete(onUi(app, () -> List.of(
                            accelerator(window, 'n', CONTROL | SHIFT),
                            accelerator(window, 0xffbe + 9, CONTROL | MOD1),   // F10
                            accelerator(window, 0xffbe + 9, CONTROL | MOD1),
                            accelerator(window, 'q', CONTROL))));               // bound to nothing
                } catch (Exception e) {
                    handled.completeExceptionally(e);
                }
                window.close();
            });
        });
        assertEquals(List.of(true, true, true, false), handled.getNow(null));
        assertEquals(List.of("new", "dense true", "dense false"), events);
    }

    @Test
    void theBarTakesNoRoomFromThePageAreaAndSettingNullGivesTheRoomBack() throws Exception {
        CompletableFuture<List<String>> sizes = new CompletableFuture<>();
        WebWindowContract.runApp(40, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(500, 400));
            window.setMenuBar(Menu.of(Menu.submenu("File", Menu.action("New", () -> {}))));  // before the window is shown
            window.onMessage(message -> CompletableFuture.runAsync(() -> {
                try {
                    List<String> seen = new ArrayList<>();
                    seen.add(WebWindowContract.evalNow(window, "innerWidth + 'x' + innerHeight"));
                    window.setMenuBar(Menu.of(Menu.submenu("A", Menu.action("x", () -> {})))); // replaced while shown
                    Thread.sleep(500);
                    seen.add(WebWindowContract.evalNow(window, "innerWidth + 'x' + innerHeight"));
                    window.setMenuBar(null);
                    Thread.sleep(500);
                    seen.add(WebWindowContract.evalNow(window, "innerWidth + 'x' + innerHeight"));
                    sizes.complete(seen);
                } catch (Throwable t) {
                    sizes.completeExceptionally(t);
                } finally {
                    window.close();
                }
            }));
            window.loadHtml("<script>addEventListener('load', () => jolt.postMessage('go'))</script>");
        });
        assertEquals(List.of("500x400", "500x400", "500x400"), sizes.getNow(null));
    }

    @Test
    void settingANewBarReplacesTheOldOneAndItsCallbacks() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CompletableFuture<Integer> bars = new CompletableFuture<>();
        WebWindowContract.runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            window.ready().thenRunAsync(() -> {
                window.setMenuBar(Menu.of(Menu.submenu("A", Menu.action("old", () -> events.add("old")))));
                window.setMenuBar(Menu.of(Menu.submenu("B", Menu.action("new", () -> events.add("new")))));
                try {
                    bars.complete(onUi(app, () -> {
                        MemorySegment box = (MemorySegment) call(GET_BIN_CHILD, ((LinuxWindow) window).nativeWindow());
                        call(ITEM_ACTIVATE, item(barOf(window), 0, 0));
                        return (int) children(box).stream().filter(w -> typeName(w).equals("GtkMenuBar")).count();
                    }));
                } catch (Exception e) {
                    bars.completeExceptionally(e);
                }
                window.close();
            });
        });
        assertEquals(1, bars.getNow(-1), "exactly one bar is left");
        assertEquals(List.of("new"), events);
    }

    @Test
    void aFramelessWindowHasNoPlaceForANativeMenuBar() throws Exception {
        CompletableFuture<Boolean> supports = new CompletableFuture<>();
        WebWindowContract.runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300).withFrameless(true));
            window.ready().thenRunAsync(() -> {
                window.setMenuBar(Menu.of(Menu.submenu("A", Menu.action("x", () -> {}))));
                supports.complete(window.supportsMenuBar());
                window.close();
            });
        });
        assertFalse(supports.getNow(true));
    }

    // ---- popups ----

    @Test
    void aPopupMenuHasTheEntriesAndTheCallbacksOfTheMenuItWasBuiltFrom() throws Exception {
        List<String> events = new ArrayList<>();
        CompletableFuture<String> seen = new CompletableFuture<>();
        WebWindowContract.runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.ready().thenRun(() -> app.dispatch(() -> {
                GtkMenus.Built popup = GtkMenus.build(Menu.of(
                        Menu.action("Copy", "Ctrl+C", () -> events.add("copy")),
                        Menu.separator(),
                        Menu.check("Wrap", true, on -> events.add("wrap " + on)),
                        Menu.submenu("More", Menu.action("Inner", () -> events.add("inner")))), false, MemorySegment.NULL);
                try {
                    seen.complete(describe(popup.root));
                    call(ITEM_ACTIVATE, item(popup.root, 0));
                    call(ITEM_ACTIVATE, item(popup.root, 2));
                    call(ITEM_ACTIVATE, item(popup.root, 3, 0));
                } catch (Throwable t) {
                    seen.completeExceptionally(t);
                } finally {
                    popup.destroy();
                    window.close();
                }
            }));
        });
        assertEquals("Copy:.... | -:..s. | Wrap:c... | More:...p{Inner:....}", seen.getNow(null));
        assertEquals(List.of("copy", "wrap false", "inner"), events);
    }

    // ---- context menu ----

    @Test
    void aRightClickGivesTheProviderWhatWasUnderThePointerAndShowsItsMenu() throws Exception {
        List<ContextMenuInfo> seen = new CopyOnWriteArrayList<>();
        List<String> opened = new CopyOnWriteArrayList<>();
        WebWindowContract.runApp(40, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(800, 600));
            window.setContextMenu(info -> {
                seen.add(info);
                return Menu.of(Menu.action("Open", () -> {}), Menu.separator(), Menu.disabled("Nothing else"));
            });
            window.onMessage(message -> CompletableFuture.runAsync(() -> {
                try {
                    // the selected text first: a click elsewhere would clear the selection
                    for (int[] at : new int[][] {{50, 100}, {50, 30}, {50, 190}}) {
                        onUi(app, () -> { click(window, 3, at[0], at[1]); return null; });
                        Thread.sleep(900);
                        // our popup is attached to the web view and mapped (on the screen) while it is open: read it, then close it
                        opened.add(onUi(app, () -> {
                            List<String> shown = new ArrayList<>();
                            for (MemorySegment menu : attached(window)) {
                                if ((int) call(MAPPED, menu) != 0) {
                                    shown.add(describe(menu));
                                    call(MENU_POPDOWN, menu);
                                }
                            }
                            return String.join(";", shown);
                        }));
                        Thread.sleep(400);
                        opened.add(onUi(app, () -> attached(window).stream().noneMatch(m -> !children(m).isEmpty()) ? "gone" : "still attached: " + attached(window).stream().map(m -> describe(m)).toList()));
                    }
                } catch (Throwable t) {
                    seen.add(new ContextMenuInfo(-1, -1, "failed: " + t, "", "", false, false, ""));
                } finally {
                    window.close();
                }
            }));
            window.loadHtml("<body style='margin:0;font:20px sans-serif'><a id=link href='https://example.org/x' style='display:block;height:60px;line-height:60px'>a link</a>"
                    + "<p id=plain style='margin:0;height:80px;line-height:80px'>Selected text here</p>"
                    + "<input id=field value='edit me' style='display:block;margin:0;width:400px;height:60px;font-size:20px'>"
                    + "<script>addEventListener('load',()=>{getSelection().selectAllChildren(document.getElementById('plain'));jolt.postMessage('go')})</script></body>");
        });
        assertEquals(3, seen.size(), seen + " " + opened);
        ContextMenuInfo plain = seen.get(0), link = seen.get(1), field = seen.get(2);
        assertTrue(plain.hasSelection() && !plain.isLink(), String.valueOf(plain));
        assertEquals("https://example.org/x", link.linkUrl());
        assertTrue(link.isLink() && !link.editable(), String.valueOf(link));
        assertEquals(50, link.x());
        assertEquals(30, link.y());
        assertEquals("https://jolt.localhost/", link.pageUrl());
        assertTrue(field.editable() && !field.isLink(), String.valueOf(field));
        String expected = "Open:.... | -:..s. | Nothing else:.g..";
        assertEquals(List.of(expected, "gone", expected, "gone", expected, "gone"), opened,
                "each popup really opened on the screen with the menu's entries, and was destroyed when it closed");
    }

    @Test
    void aProviderThatReturnsNothingOrFailsShowsNoMenuAndTheWindowGoesOn() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        CompletableFuture<String> still = new CompletableFuture<>();
        WebWindowContract.runApp(40, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            window.setContextMenu(info -> {
                if (asked.incrementAndGet() == 1) {
                    return null;
                }
                throw new IllegalStateException("the provider is broken");
            });
            window.onMessage(message -> CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 0; i < 2; i++) {
                        onUi(app, () -> { click(window, 3, 60, 60); return null; });
                        Thread.sleep(700);
                    }
                    String shown = onUi(app, () -> attached(window).stream().anyMatch(m -> (int) call(MAPPED, m) != 0) ? "menu" : "none");
                    still.complete(WebWindowContract.evalNow(window, "1 + 1") + "," + shown);
                } catch (Throwable t) {
                    still.completeExceptionally(t);
                } finally {
                    window.close();
                }
            }));
            window.loadHtml("<div id=target style='padding:50px'>hello</div><script>addEventListener('load', () => jolt.postMessage('go'))</script>");
        });
        assertEquals(2, asked.get());
        assertEquals("2,none", still.getNow(null));
    }

    @Test
    void aWindowWithoutAProviderShowsNoMenuOfItsOwn() throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        WebWindowContract.runApp(40, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            window.onMessage(message -> CompletableFuture.runAsync(() -> {
                try {
                    onUi(app, () -> { click(window, 3, 60, 60); return null; });
                    Thread.sleep(700);
                    result.complete(onUi(app, () -> attached(window).stream().anyMatch(m -> (int) call(MAPPED, m) != 0) ? "menu" : "none"));
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                } finally {
                    window.close();
                }
            }));
            window.loadHtml("<div style='padding:50px'>hello</div><script>addEventListener('load', () => jolt.postMessage('go'))</script>");
        });
        assertEquals("none", result.getNow(null));
    }
}
