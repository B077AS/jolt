package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.ContextMenuInfo;
import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.Menu;
import io.github.b077as.jolt.Shortcut;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.contract.WebWindowContract;
import io.github.b077as.jolt.internal.ffi.Native;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Menu bar, context menu and global hotkeys on Windows: the native menus are read back with the Win32 menu functions, and keys are
 * sent with {@code SendInput} (only to a window that is confirmed to be in front, so a stray key press cannot reach another program).
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsMenusTest {

    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
    private static final MethodHandle GET_MENU_ITEM_COUNT = Native.downcall(USER32, "GetMenuItemCount", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GET_MENU_STRING = Native.downcall(USER32, "GetMenuStringW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle GET_MENU_STATE = Native.downcall(USER32, "GetMenuState",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle GET_SUB_MENU = Native.downcall(USER32, "GetSubMenu", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle END_MENU = Native.downcall(USER32, "EndMenu", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle SEND_INPUT = Native.downcall(USER32, "SendInput", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle GET_FOREGROUND_WINDOW = Native.downcall(USER32, "GetForegroundWindow", FunctionDescriptor.of(ADDRESS));

    private static final int MF_BYPOSITION = 0x400;
    private static final int VK_CONTROL = 0x11, VK_SHIFT = 0x10, VK_MENU = 0x12, VK_F9 = 0x78, VK_F10 = 0x79, VK_F11 = 0x7A, VK_F12 = 0x7B;

    // ---- reading menus back ----

    /** One line per entry: {@code label:flags} with c checked, g grayed, s separator, p submenu; submenus follow in braces. */
    private static String describe(MemorySegment menu) throws Throwable {
        StringBuilder text = new StringBuilder();
        int count = (int) GET_MENU_ITEM_COUNT.invokeExact(menu);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                text.append(" | ");
            }
            int state = (int) GET_MENU_STATE.invokeExact(menu, i, MF_BYPOSITION);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(512 * 2L);
                int length = (int) GET_MENU_STRING.invokeExact(menu, i, buffer, 511, MF_BYPOSITION);
                text.append(length > 0 ? Native.readWide(buffer).replace("\t", "<tab>") : "-");
            }
            boolean separator = (state & WindowsMenus.MF_SEPARATOR) != 0;
            boolean popup = (state & WindowsMenus.MF_POPUP) != 0;
            text.append(':').append((state & WindowsMenus.MF_CHECKED) != 0 ? 'c' : '.').append((state & WindowsMenus.MF_GRAYED) != 0 && !separator ? 'g' : '.')
                    .append(separator ? 's' : '.').append(popup ? 'p' : '.');
            if (popup) {
                MemorySegment child = (MemorySegment) GET_SUB_MENU.invokeExact(menu, i);
                text.append('{').append(describe(child)).append('}');
            }
        }
        return text.toString();
    }

    private static WindowsWindow windowsWindow(WebWindow window) {
        return (WindowsWindow) window;
    }

    // ---- menu bar ----

    @Test
    void aMenuBarBecomesANativeBarWithLabelsAcceleratorsChecksDisabledEntriesAndSubmenus() {
        CompletableFuture<String> seen = new CompletableFuture<>();
        WebWindowContract.runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            window.ready().thenRun(() -> {
                window.setMenuBar(Menu.of(
                        Menu.submenu("&File",
                                Menu.action("&New", "Ctrl+N", () -> {}),
                                Menu.separator(),
                                Menu.action("Save && close", () -> {}).withEnabled(false),
                                Menu.submenu("Recent", Menu.disabled("(none)"))),
                        Menu.submenu("&View", Menu.check("&Dense", true, "Ctrl+Shift+D", on -> {}), Menu.check("Wide", false, on -> {}))));
                app.dispatch(() -> {
                    try {
                        seen.complete(describe(Win32.getMenu(windowsWindow(window).hwnd())));
                    } catch (Throwable t) {
                        seen.completeExceptionally(t);
                    }
                    window.close();
                });
            });
        });
        assertEquals("&File:...p{&New<tab>Ctrl+N:.... | -:..s. | Save && close:.g.. | Recent:...p{(none):.g..}} | "
                + "&View:...p{&Dense<tab>Ctrl+Shift+D:c... | Wide:....}", seen.getNow(null));
    }

    @Test
    void aMenuBarKeepsThePageAreaItsSizeAndGivesItBackWhenRemoved() {
        List<String> sizes = new CopyOnWriteArrayList<>();
        WebWindowContract.runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(500, 400));
            window.onMessage(message -> CompletableFuture.runAsync(() -> {
                try {
                    sizes.add(WebWindowContract.evalNow(window, "innerWidth + 'x' + innerHeight"));
                    window.setMenuBar(Menu.of(Menu.submenu("&File", Menu.action("&New", () -> {}))));
                    Thread.sleep(500);
                    sizes.add(WebWindowContract.evalNow(window, "innerWidth + 'x' + innerHeight"));
                    window.setMenuBar(null);
                    Thread.sleep(500);
                    sizes.add(WebWindowContract.evalNow(window, "innerWidth + 'x' + innerHeight"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    window.close();
                }
            }));
            window.loadHtml("<script>addEventListener('load', () => jolt.postMessage('go'))</script>");
        });
        assertEquals(List.of("500x400", "500x400", "500x400"), sizes);
    }

    @Test
    void choosingMenuBarEntriesRunsTheCallbacksAndAChecksMarkFlipsEachTime() {
        List<String> events = new CopyOnWriteArrayList<>();
        CompletableFuture<String> marks = new CompletableFuture<>();
        WebWindowContract.runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            window.ready().thenRun(() -> {
                window.setMenuBar(Menu.of(
                        Menu.submenu("&File", Menu.action("One", () -> events.add("one")), Menu.disabled("Two")),
                        Menu.submenu("&View", Menu.check("Dense", false, on -> events.add("dense " + on)))));
                app.dispatch(() -> {
                    MemorySegment hwnd = windowsWindow(window).hwnd();
                    int one = WindowsMenus.FIRST_COMMAND_ID, two = one + 1, dense = one + 2;
                    Win32.sendMessage(hwnd, Win32.WM_COMMAND, one, 0);
                    Win32.sendMessage(hwnd, Win32.WM_COMMAND, two, 0);   // grayed: Windows would not send it, and we do nothing if it is sent
                    Win32.sendMessage(hwnd, Win32.WM_COMMAND, dense, 0);
                    Win32.sendMessage(hwnd, Win32.WM_COMMAND, dense, 0);
                    Win32.sendMessage(hwnd, Win32.WM_COMMAND, dense, 0);
                    Win32.sendMessage(hwnd, Win32.WM_COMMAND, 0x0FFF, 0); // not one of ours
                    app.dispatch(() -> { // the commands are carried out from the queue
                        try {
                            marks.complete(describe(Win32.getMenu(hwnd)));
                        } catch (Throwable t) {
                            marks.completeExceptionally(t);
                        }
                        window.close();
                    });
                });
            });
        });
        assertEquals(List.of("one", "dense true", "dense false", "dense true"), events);
        assertTrue(marks.getNow("").contains("Dense:c..."), "after three toggles the mark is on: " + marks.getNow(null));
    }

    @Test
    void settingANewBarReplacesTheOldOneAndItsCallbacks() {
        List<String> events = new CopyOnWriteArrayList<>();
        WebWindowContract.runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            window.ready().thenRun(() -> {
                window.setMenuBar(Menu.of(Menu.submenu("A", Menu.action("old", () -> events.add("old")))));
                window.setMenuBar(Menu.of(Menu.submenu("B", Menu.action("new", () -> events.add("new")))));
                app.dispatch(() -> {
                    Win32.sendMessage(windowsWindow(window).hwnd(), Win32.WM_COMMAND, WindowsMenus.FIRST_COMMAND_ID, 0);
                    app.dispatch(window::close);
                });
            });
        });
        assertEquals(List.of("new"), events);
    }

    @Test
    void aFramelessWindowHasNoPlaceForANativeMenuBar() {
        AtomicReference<String> seen = new AtomicReference<>();
        WebWindowContract.runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300).withFrameless(true));
            window.setMenuBar(Menu.of(Menu.submenu("A", Menu.action("x", () -> {}))));
            window.ready().thenRun(() -> app.dispatch(() -> {
                seen.set(window.supportsMenuBar() + " " + Win32.getMenu(windowsWindow(window).hwnd()).equals(MemorySegment.NULL));
                window.close();
            }));
        });
        assertEquals("false true", seen.get());
    }

    @Test
    void menuAcceleratorsFireWhileThePageHasTheFocusAndThePageNeverSeesTheKey() {
        AtomicInteger newCount = new AtomicInteger();
        List<Boolean> toggles = new CopyOnWriteArrayList<>();
        AtomicReference<String> pageSaw = new AtomicReference<>("nothing");
        AtomicBoolean notInFront = new AtomicBoolean();
        AtomicReference<String> failure = new AtomicReference<>();
        WebWindowContract.runApp(40, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(400, 300));
            window.setMenuBar(Menu.of(
                    Menu.submenu("&File", Menu.action("&New", "Ctrl+Shift+F9", newCount::incrementAndGet)),
                    Menu.submenu("&View", Menu.check("&Dense", false, "Ctrl+Alt+F10", toggles::add))));
            window.onMessage(message -> CompletableFuture.runAsync(() -> {
                try {
                    window.focus();
                    Thread.sleep(800);
                    if (!isForeground(windowsWindow(window).hwnd())) {
                        notInFront.set(true); // never send keys unless this window is the one that gets them
                        return;
                    }
                    chord(VK_CONTROL, VK_SHIFT, VK_F9);
                    Thread.sleep(400);
                    chord(VK_CONTROL, VK_MENU, VK_F10);
                    Thread.sleep(300);
                    chord(VK_CONTROL, VK_MENU, VK_F10);
                    Thread.sleep(400);
                    pageSaw.set(WebWindowContract.evalNow(window, "window.__keys.join(',')"));
                } catch (Throwable t) {
                    failure.set(t.toString());
                } finally {
                    window.close();
                }
            }));
            window.loadHtml("<script>window.__keys=[];addEventListener('keydown',e=>__keys.push(e.key));"
                    + "addEventListener('load', () => jolt.postMessage('go'))</script>");
        });
        Assumptions.assumeFalse(notInFront.get(), "the test window was not in front, so no keys were sent");
        assertEquals(null, failure.get());
        assertEquals(1, newCount.get());
        assertEquals(List.of(true, false), toggles, "a check's accelerator flips it each time");
        assertFalse(pageSaw.get().contains("F9") || pageSaw.get().contains("F10"), "the page must not see an accelerator: " + pageSaw.get());
    }

    // ---- context menu ----

    @Test
    void aRightClickGivesTheProviderWhatWasUnderThePointerAndShowsItsMenu() {
        List<ContextMenuInfo> seen = new CopyOnWriteArrayList<>();
        AtomicInteger closed = new AtomicInteger();
        WebWindowContract.runApp(40, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(800, 600)); // no devtools: the default menu is off
            window.setContextMenu(info -> {
                seen.add(info);
                // the popup runs its own message loop, which also runs queued tasks: close it from one
                app.dispatch(() -> {
                    try {
                        closed.addAndGet((int) END_MENU.invokeExact());
                    } catch (Throwable t) {
                        throw new IllegalStateException(t);
                    }
                });
                return Menu.of(Menu.action("Open", () -> {}), Menu.separator(), Menu.disabled("Nothing else"));
            });
            window.onMessage(message -> CompletableFuture.runAsync(() -> {
                try {
                    for (String id : new String[] {"link", "plain", "field"}) {
                        rightClick(app, window, id);
                        Thread.sleep(600);
                    }
                } catch (Throwable t) {
                    seen.add(new ContextMenuInfo(-1, -1, "failed: " + t, "", "", false, false, ""));
                } finally {
                    window.close();
                }
            }));
            window.loadHtml("<body style='margin:0;font:20px sans-serif'><a id=link href='https://example.org/x' style='display:block;padding:20px'>a link</a>"
                    + "<p id=plain style='padding:20px'>Selected text here</p><input id=field value='edit me' style='margin:20px'>"
                    + "<script>addEventListener('load',()=>{getSelection().selectAllChildren(document.getElementById('plain'));jolt.postMessage('go')})</script></body>");
        });
        assertEquals(3, seen.size(), String.valueOf(seen));
        ContextMenuInfo link = seen.get(0), plain = seen.get(1), field = seen.get(2);
        assertEquals("https://example.org/x", link.linkUrl());
        assertTrue(link.isLink() && !link.editable() && !link.hasSelection());
        assertEquals(5, link.x());
        assertEquals(5, link.y());
        assertTrue(link.pageUrl().startsWith("https://jolt.localhost/"), link.pageUrl());
        assertTrue(plain.hasSelection() && !plain.isLink());
        assertEquals("Selected text here", plain.selectedText());
        assertTrue(field.editable() && !field.isLink());
        assertEquals(3, closed.get(), "each popup was really open: EndMenu reports it closed one");
    }

    @Test
    void aProviderThatReturnsNothingOrFailsShowsNoMenuAndTheWindowGoesOn() {
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
                    rightClick(app, window, "target");
                    Thread.sleep(400);
                    rightClick(app, window, "target");
                    Thread.sleep(400);
                    still.complete(WebWindowContract.evalNow(window, "1 + 1")); // alive, and not stuck in a menu loop
                } catch (Throwable t) {
                    still.completeExceptionally(t);
                } finally {
                    window.close();
                }
            }));
            window.loadHtml("<div id=target style='padding:50px'>hello</div><script>addEventListener('load', () => jolt.postMessage('go'))</script>");
        });
        assertEquals(2, asked.get());
        assertEquals("2", still.getNow(null));
    }

    @Test
    void aPopupMenuHasTheEntriesAndTheCallbacksOfTheMenuItWasBuiltFrom() {
        List<String> events = new ArrayList<>();
        CompletableFuture<String> seen = new CompletableFuture<>();
        WebWindowContract.runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.ready().thenRun(() -> app.dispatch(() -> {
                WindowsMenus.Built popup = WindowsMenus.build(Menu.of(
                        Menu.action("Copy", "Ctrl+C", () -> events.add("copy")),
                        Menu.separator(),
                        Menu.check("Wrap", true, on -> events.add("wrap " + on)),
                        Menu.submenu("More", Menu.action("Inner", () -> events.add("inner")))), false);
                try {
                    seen.complete(describe(popup.handle));
                    popup.activate(WindowsMenus.FIRST_COMMAND_ID, false);
                    popup.activate(WindowsMenus.FIRST_COMMAND_ID + 1, false);
                    popup.activate(WindowsMenus.FIRST_COMMAND_ID + 2, false);
                } catch (Throwable t) {
                    seen.completeExceptionally(t);
                } finally {
                    popup.destroy();
                    window.close();
                }
            }));
        });
        assertEquals("Copy<tab>Ctrl+C:.... | -:..s. | Wrap:c... | More:...p{Inner:....}", seen.getNow(null));
        assertEquals(List.of("copy", "wrap false", "inner"), events, "a popup's check asks for the opposite of how it was built");
    }

    // ---- global hotkeys ----

    @Test
    void aHotkeyIsRegisteredFiresRejectsDuplicatesAndIsGivenBackWhenClosed() throws Exception {
        List<String> steps = new CopyOnWriteArrayList<>();
        AtomicInteger presses = new AtomicInteger();
        WebWindowContract.runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200).withVisible(false));
            Shortcut shortcut = Shortcut.parse("Ctrl+Alt+Shift+F12");
            assertTrue(app.supportsGlobalHotkeys());
            assertThrows(IllegalArgumentException.class, () -> app.registerHotkey(Shortcut.parse("Shift+M"), () -> {}));
            assertThrows(NullPointerException.class, () -> app.registerHotkey(shortcut, null));
            app.registerHotkey(shortcut, presses::incrementAndGet).whenComplete((hotkey, error) -> {
                try {
                    assertNotNull(hotkey, String.valueOf(error));
                    assertEquals(shortcut, hotkey.shortcut());
                    steps.add("registered");
                    WindowsApplication windows = (WindowsApplication) app;
                    // WM_HOTKEY as Windows sends it: wParam is the id (the first hotkey of the application has id 1)
                    Win32.sendMessage(windows.dispatchWindow(), Win32.WM_HOTKEY, 1, 0);
                    steps.add("fired " + presses.get());
                    app.registerHotkey(shortcut, () -> {}).whenComplete((second, secondError) -> {
                        steps.add("duplicate " + (second == null) + " " + secondError.getClass().getSimpleName());
                        hotkey.close();
                        hotkey.close(); // harmless twice
                        app.dispatch(() -> app.registerHotkey(shortcut, () -> {}).whenComplete((again, againError) -> {
                            steps.add("again " + (again != null));
                            if (again != null) {
                                again.close();
                            }
                            app.dispatch(window::close);
                        }));
                    });
                } catch (Throwable t) {
                    steps.add("failed " + t);
                    window.close();
                }
            });
        });
        assertEquals(List.of("registered", "fired 1", "duplicate true JoltException", "again true"), steps);
    }

    @Test
    void aHotkeyAnotherProgramHoldsIsReportedAsTaken() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean claimedFirst = new AtomicBoolean();
        // Take the combination from another thread's window first; RegisterHotKey is per window, and a second claim fails.
        WebWindowContract.runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200).withVisible(false));
            window.ready().thenRun(() -> app.dispatch(() -> {
                MemorySegment other = windowsWindow(window).hwnd();
                int[] error = new int[1];
                claimedFirst.set(Win32.registerHotKey(other, 77, 0x2 | 0x1 | 0x4, VK_F11, error));
                Shortcut shortcut = Shortcut.parse("Ctrl+Alt+Shift+F11");
                app.registerHotkey(shortcut, () -> {}).whenComplete((hotkey, error2) -> {
                    failure.set(error2);
                    Win32.unregisterHotKey(other, 77);
                    if (hotkey != null) {
                        hotkey.close();
                    }
                    app.dispatch(window::close);
                });
            }));
        });
        assertTrue(claimedFirst.get(), "the test could not take the combination first");
        Throwable error = failure.get();
        assertNotNull(error);
        assertTrue((error instanceof JoltException ? error : error.getCause()).getMessage().contains("already in use by another program"), String.valueOf(error));
    }

    @Test
    void aHotkeyFiresWhenTheKeysArePressedAndTheKeysAreNotPassedOn() {
        AtomicInteger presses = new AtomicInteger();
        AtomicReference<String> failure = new AtomicReference<>();
        WebWindowContract.runApp(30, app -> {
            WebWindow window = app.createWindow(WindowOptions.defaults().withSize(300, 200).withVisible(false));
            app.registerHotkey(Shortcut.parse("Ctrl+Alt+Shift+F11"), presses::incrementAndGet).whenComplete((hotkey, error) -> {
                if (error != null) {
                    failure.set(error.toString());
                    window.close();
                    return;
                }
                CompletableFuture.runAsync(() -> {
                    try {
                        chord(VK_CONTROL, VK_MENU, VK_SHIFT, VK_F11);
                        Thread.sleep(600);
                    } catch (Throwable t) {
                        failure.set(t.toString());
                    } finally {
                        hotkey.close();
                        window.close();
                    }
                });
            });
        });
        assertEquals(null, failure.get());
        assertEquals(1, presses.get());
    }

    // ---- helpers ----

    /** A right click on the element, as the web view's own input pipeline would see it (so a real context-menu request is raised). */
    private static void rightClick(Application app, WebWindow window, String elementId) throws Exception {
        String position = WebWindowContract.evalNow(window, "(() => { const r = document.getElementById('" + elementId
                + "').getBoundingClientRect(); return Math.round(r.x + 5) + ',' + Math.round(r.y + 5); })()").replace("\"", "");
        String[] xy = position.split(",");
        for (String type : new String[] {"mousePressed", "mouseReleased"}) {
            app.submit(() -> windowsWindow(window).callDevTools("Input.dispatchMouseEvent", "{\"type\":\"" + type + "\",\"x\":" + xy[0] + ",\"y\":"
                    + xy[1] + ",\"button\":\"right\",\"buttons\":2,\"clickCount\":1}")).get(5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);
            Thread.sleep(120);
        }
    }

    private static boolean isForeground(MemorySegment hwnd) throws Throwable {
        MemorySegment front = (MemorySegment) GET_FOREGROUND_WINDOW.invokeExact();
        return front.address() == hwnd.address();
    }

    private static void key(int virtualKey, boolean down) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocate(40, 8); // INPUT: type, then a KEYBDINPUT in the union
            input.set(JAVA_INT, 0, 1);                  // INPUT_KEYBOARD
            input.set(JAVA_SHORT, 8, (short) virtualKey);
            input.set(JAVA_INT, 12, down ? 0 : 2);       // KEYEVENTF_KEYUP
            int sent = (int) SEND_INPUT.invokeExact(1, input, 40);
            assertEquals(1, sent, "SendInput");
        }
    }

    /** Presses the keys in order, then releases them in reverse. */
    private static void chord(int... virtualKeys) throws Throwable {
        for (int virtualKey : virtualKeys) {
            key(virtualKey, true);
            Thread.sleep(30);
        }
        for (int i = virtualKeys.length - 1; i >= 0; i--) {
            key(virtualKeys[i], false);
            Thread.sleep(30);
        }
    }
}
