package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.Autostart;
import io.github.b077as.jolt.CloseBehavior;
import io.github.b077as.jolt.Tray;
import io.github.b077as.jolt.TrayMenu;
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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Windows shell sees of the tray, close-to-tray, minimizing, frameless windows and autostart, read back from Windows itself.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsShellTest {

    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
    private static final SymbolLookup SHELL32 = SymbolLookup.libraryLookup("shell32.dll", Arena.global());
    private static final MethodHandle GET_MENU_ITEM_COUNT = Native.downcall(USER32, "GetMenuItemCount", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GET_MENU_STATE = Native.downcall(USER32, "GetMenuState", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle GET_MENU_STRING = Native.downcall(USER32, "GetMenuStringW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle DESTROY_MENU = Native.downcall(USER32, "DestroyMenu", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle NOTIFY_ICON_GET_RECT = Native.downcall(SHELL32, "Shell_NotifyIconGetRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    private static final int MF_BYPOSITION = 0x400, MF_GRAYED = 0x1, MF_CHECKED = 0x8, MF_SEPARATOR = 0x800;

    @Test
    void theCloseButtonOnlyHidesAWindowWhoseCloseBehaviorIsHideAndFocusBringsItBack() {
        StringBuffer log = new StringBuffer();
        WebWindowContract.runApp(30, app -> {
            WindowsWindow window = (WindowsWindow) app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.setCloseBehavior(CloseBehavior.HIDE);
            window.ready().thenRun(() -> app.dispatch(() -> {
                Win32.sendMessage(window.hwnd(), Win32.WM_CLOSE, 0, 0); // what the close button and Alt+F4 send
                boolean hidden = !window.isVisible();
                window.eval("1 + 1").whenComplete((value, error) -> {   // never block the UI thread on a future
                    boolean alive = error == null && value.equals("2");
                    window.focus();
                    app.dispatch(() -> {
                        boolean back = window.isVisible();
                        log.append(hidden).append(',').append(alive).append(',').append(back);
                        window.setCloseBehavior(CloseBehavior.CLOSE);
                        Win32.sendMessage(window.hwnd(), Win32.WM_CLOSE, 0, 0);
                    });
                });
            }));
            // reaching this means the second WM_CLOSE really destroyed the window and the loop is ending
            window.onClosed(() -> log.append(",closed"));
        });
        assertEquals("true,true,true,closed", log.toString());
    }

    @Test
    void closingFromCodeStillClosesAWindowThatHidesOnTheCloseButton() {
        AtomicInteger closed = new AtomicInteger();
        WebWindowContract.runApp(30, app -> {
            WindowsWindow window = (WindowsWindow) app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.setCloseBehavior(CloseBehavior.HIDE);
            window.onClosed(closed::incrementAndGet);
            window.ready().thenRun(window::close);
        });
        assertEquals(1, closed.get());
    }

    @Test
    void minimizeIsReportedAndFocusRestores() {
        CompletableFuture<String> result = new CompletableFuture<>();
        WebWindowContract.runApp(30, app -> {
            WindowsWindow window = (WindowsWindow) app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.ready().thenRun(() -> {
                window.minimize();
                WebWindowContract.eventually(window::isMinimized, 5000).thenAccept(minimized -> {
                    window.focus();
                    WebWindowContract.eventually(() -> !window.isMinimized(), 5000).thenAccept(restored -> {
                        result.complete(minimized + "," + restored);
                        window.close();
                    });
                });
            });
        });
        assertEquals("true,true", result.getNow(null));
    }

    @Test
    void aWindowIsFocusedOnlyWhileItIsTheOneInFrontAndShown() {
        CompletableFuture<String> result = new CompletableFuture<>();
        WebWindowContract.runApp(30, app -> {
            WindowsWindow window = (WindowsWindow) app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.ready().thenRun(() -> {
                window.focus();
                WebWindowContract.eventually(window::isFocused, 5000).thenAccept(focused -> {
                    window.minimize();
                    WebWindowContract.eventually(() -> !window.isFocused(), 5000).thenAccept(notWhileMinimized -> {
                        window.focus();
                        WebWindowContract.eventually(window::isFocused, 5000).thenAccept(back -> {
                            window.hide();
                            WebWindowContract.eventually(() -> !window.isFocused(), 5000).thenAccept(notWhileHidden -> {
                                result.complete(focused + "," + notWhileMinimized + "," + back + "," + notWhileHidden);
                                window.close();
                            });
                        });
                    });
                });
            });
        });
        String[] seen = result.getNow("").split(",");
        assertEquals("true", seen[1], "a minimized window is not focused");
        assertEquals("true", seen[3], "a hidden window is not focused");
        // Windows only lets the foreground process take the foreground; a test started from a background shell may be refused.
        org.junit.jupiter.api.Assumptions.assumeTrue(seen[0].equals("true") && seen[2].equals("true"),
                "Windows did not give this test process the foreground, so 'focused' could not be shown to be true");
    }

    @Test
    void aFramelessWindowIsAllClientAreaHasItsResizeHandlesAndObeysFrameMessagesWithoutLeakingThem() {
        CompletableFuture<String> result = new CompletableFuture<>();
        CompletableFuture<Boolean> minimized = new CompletableFuture<>();
        java.util.List<String> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        WebWindowContract.runApp(40, app -> {
            WindowsWindow window = (WindowsWindow) app.createWindow(WindowOptions.defaults().withSize(400, 300).withFrameless(true));
            window.onMessage(message -> {
                heard.add(message);
                if (message.equals("hello")) {
                    window.eval("document.querySelectorAll('[data-jolt-handle]').length").thenAccept(handles -> app.dispatch(() -> {
                        try (Arena arena = Arena.ofConfined()) {
                            MemorySegment client = arena.allocate(Win32.RECT);
                            Win32.getClientRect(window.hwnd(), client);
                            int[] outer = window.outerBounds();
                            result.complete(handles + "," + (client.get(JAVA_INT, 8) == outer[2] - outer[0])
                                    + "," + (client.get(JAVA_INT, 12) == outer[3] - outer[1]));
                        }
                        window.eval("jolt.frame.minimize(); 'sent'");
                        WebWindowContract.eventually(window::isMinimized, 5000).thenAccept(m -> {
                            minimized.complete(m);
                            window.close();
                        });
                    }));
                }
            });
            window.loadHtml("<script>addEventListener('load', () => jolt.postMessage('hello'))</script>");
        });
        assertEquals("8,true,true", result.getNow(null));
        assertTrue(minimized.getNow(false), "jolt.frame.minimize() must minimize the window");
        assertEquals(List.of("hello"), heard, "frame messages must not reach the application's listeners");
    }

    @Test
    void aTrayIconAppearsInTheNotificationAreaReportsClicksAndGoesAway() {
        CompletableFuture<String> result = new CompletableFuture<>();
        AtomicInteger clicks = new AtomicInteger();
        WebWindowContract.runApp(30, app -> {
            WindowsWindow window = (WindowsWindow) app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.ready().thenRun(() -> app.dispatch(() -> {
                WindowsTray tray = null;
                try {
                    tray = (WindowsTray) app.createTray(WebWindowContract.sampleIco(), "Parsel: 3 unread");
                    tray.onClick(clicks::incrementAndGet);
                    tray.setTooltip("Parsel: 4 unread");
                    tray.setIcon(WebWindowContract.samplePng());
                    boolean shown = iconRectKnown(tray.hwnd());
                    Win32.sendMessage(tray.hwnd(), WindowsTray.WM_TRAY, 1, 0x0202); // the shell's message for a left click
                    app.dispatch(() -> {});
                    tray.close();
                    app.dispatch(() -> {
                        result.complete("shown=" + shown);
                        window.close();
                    });
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                    window.close();
                }
            }));
        });
        assertEquals("shown=true", result.getNow(null));
        assertEquals(1, clicks.get());
    }

    @Test
    void aTrayMenuBecomesANativeMenuWithChecksDisabledEntriesSeparatorsAndSubmenus() {
        CompletableFuture<String> result = new CompletableFuture<>();
        WebWindowContract.runApp(30, app -> {
            WindowsWindow window = (WindowsWindow) app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.ready().thenRun(() -> app.dispatch(() -> {
                try (Arena arena = Arena.ofConfined()) {
                    WindowsTray tray = (WindowsTray) app.createTray(WebWindowContract.sampleIco(), null);
                    TrayMenu menu = TrayMenu.of(
                            TrayMenu.action("Open Parsel", () -> {}),
                            TrayMenu.separator(),
                            TrayMenu.check("Close to tray", true, on -> {}),
                            TrayMenu.check("Start with Windows", false, on -> {}),
                            TrayMenu.disabled("3 unread & more"),
                            TrayMenu.submenu("Status", TrayMenu.action("Online", () -> {})));
                    MemorySegment popup = tray.buildMenu(menu.items(), new HashMap<>(), new int[] {1});
                    StringBuilder seen = new StringBuilder();
                    int count = (int) GET_MENU_ITEM_COUNT.invokeExact(popup);
                    seen.append(count);
                    for (int i = 0; i < count; i++) {
                        int state = (int) GET_MENU_STATE.invokeExact(popup, i, MF_BYPOSITION);
                        MemorySegment text = arena.allocate(256 * 2L);
                        int length = (int) GET_MENU_STRING.invokeExact(popup, i, text, 255, MF_BYPOSITION);
                        String label = length > 0 ? Native.readWide(text) : "-";
                        seen.append('|').append(label).append(':')
                                .append((state & MF_CHECKED) != 0 ? 'c' : '.').append((state & MF_GRAYED) != 0 ? 'g' : '.')
                                .append((state & MF_SEPARATOR) != 0 ? 's' : '.');
                    }
                    int ignored = (int) DESTROY_MENU.invokeExact(popup);
                    tray.close();
                    result.complete(seen.toString());
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
                window.close();
            }));
        });
        // Windows reports a separator as grayed as well. An ampersand is doubled in the menu text so Windows draws it as written instead of underlining the next letter
        assertEquals("6|Open Parsel:...|-:.gs|Close to tray:c..|Start with Windows:...|3 unread && more:.g.|Status:...", result.getNow(null));
    }

    @Test
    void autostartWritesAReadableRunEntryAndRemovesIt() throws Exception {
        String appId = "io.github.b077as.jolt.test-" + UUID.randomUUID();
        Autostart autostart = new WindowsAutostart(appId);
        try {
            assertFalse(autostart.isEnabled());
            autostart.enable(List.of("C:\\Program Files\\Parsel\\Parsel.exe", "--minimized", "say \"hi\""));
            assertTrue(autostart.isEnabled());
            String stored = registryValue(appId);
            assertEquals("\"C:\\Program Files\\Parsel\\Parsel.exe\" --minimized \"say \\\"hi\\\"\"", stored);
            autostart.enable(List.of("C:\\Parsel.exe")); // replaces
            assertEquals("C:\\Parsel.exe", registryValue(appId));
            autostart.disable();
            assertFalse(autostart.isEnabled());
            autostart.disable(); // nothing to remove is not an error
        } finally {
            new ProcessBuilder("reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", appId, "/f")
                    .redirectErrorStream(true).start().waitFor();
        }
    }

    // ---- helpers ----

    /** Whether Windows can say where the icon sits, which it can only for an icon that was really added. */
    private static boolean iconRectKnown(MemorySegment trayWindow) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment id = arena.allocate(40, 8); // NOTIFYICONIDENTIFIER: cbSize, hWnd, uID, guidItem
            id.set(JAVA_INT, 0, 40);
            id.set(ADDRESS, 8, trayWindow);
            id.set(JAVA_INT, 16, 1);
            MemorySegment rect = arena.allocate(16, 4);
            return (int) NOTIFY_ICON_GET_RECT.invokeExact(id, rect) >= 0;
        }
    }

    private static String registryValue(String name) throws Exception {
        Process process = new ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", name)
                .redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        for (String line : out.split("\\R")) {
            int at = line.indexOf("REG_SZ");
            if (line.trim().startsWith(name) && at >= 0) {
                return line.substring(at + "REG_SZ".length()).trim();
            }
        }
        throw new AssertionError("no value " + name + " in: " + out);
    }
}
