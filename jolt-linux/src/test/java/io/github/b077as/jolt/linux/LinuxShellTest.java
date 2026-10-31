package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.CloseBehavior;
import io.github.b077as.jolt.TrayMenu;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.contract.WebWindowContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Close-to-tray, minimizing, frameless windows and the tray icon, read back from GTK and from the session bus. The tray tests need
 * the stand-in tray that {@code run-tray-tests.sh} starts ({@code JOLT_TEST_TRAY_MOCK=1}); without it there is no tray at all.
 */
@EnabledOnOs(OS.LINUX)
class LinuxShellTest {

    private static final Path REGISTERED = Path.of(System.getenv().getOrDefault("MOCK_SNI_LOG", "/tmp/sni-registered.log"));

    @Test
    void theCloseButtonOnlyHidesAWindowWhoseCloseBehaviorIsHideAndFocusBringsItBack() {
        StringBuffer log = new StringBuffer();
        WebWindowContract.runApp(30, app -> {
            LinuxWindow window = (LinuxWindow) app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.setCloseBehavior(CloseBehavior.HIDE);
            window.ready().thenRun(() -> app.dispatch(() -> {
                Gtk.windowClose(window.nativeWindow()); // what the close button does: delete-event, handled a moment later
                WebWindowContract.eventually(() -> !window.isVisible(), 5000).thenAccept(hidden ->
                        window.eval("1 + 1").whenComplete((value, error) -> {   // never block the UI thread on a future
                            boolean alive = error == null && value.equals("2");
                            window.focus();
                            WebWindowContract.eventually(window::isVisible, 5000).thenAccept(back -> app.dispatch(() -> {
                                log.append(hidden).append(',').append(alive).append(',').append(back);
                                window.setCloseBehavior(CloseBehavior.CLOSE);
                                Gtk.windowClose(window.nativeWindow());
                            }));
                        }));
            }));
            window.onClosed(() -> log.append(",closed"));
        });
        assertEquals("true,true,true,closed", log.toString());
    }

    @Test
    void closingFromCodeStillClosesAWindowThatHidesOnTheCloseButton() {
        AtomicInteger closed = new AtomicInteger();
        WebWindowContract.runApp(30, app -> {
            LinuxWindow window = (LinuxWindow) app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.setCloseBehavior(CloseBehavior.HIDE);
            window.onClosed(closed::incrementAndGet);
            window.ready().thenRun(window::close);
        });
        assertEquals(1, closed.get());
    }

    @Test
    void aFramelessWindowHasNoDecorationsHasItsResizeHandlesAndKeepsFrameMessagesAwayFromTheApplication() {
        CompletableFuture<String> result = new CompletableFuture<>();
        List<String> heard = new CopyOnWriteArrayList<>();
        WebWindowContract.runApp(40, app -> {
            LinuxWindow window = (LinuxWindow) app.createWindow(WindowOptions.defaults().withSize(400, 300).withFrameless(true));
            LinuxWindow ordinary = (LinuxWindow) app.createWindow(WindowOptions.defaults().withSize(400, 300));
            window.onMessage(message -> {
                heard.add(message);
                if (message.equals("hello")) {
                    window.eval("document.querySelectorAll('[data-jolt-handle]').length").thenAccept(handles -> app.dispatch(() -> {
                        result.complete(handles + "," + Gtk.windowGetDecorated(window.nativeWindow()) + ","
                                + Gtk.windowGetDecorated(ordinary.nativeWindow()));
                        ordinary.close();
                        window.close();
                    }));
                }
            });
            window.loadHtml("<script>addEventListener('load', () => { jolt.postMessage('hello'); jolt.frame.toggleMaximize(); })</script>");
            ordinary.loadHtml("<p>ordinary</p>");
        });
        assertEquals("8,false,true", result.getNow(null));
        assertEquals(List.of("hello"), heard, "frame messages must not reach the application's listeners");
    }

    @Test
    void aShownWindowThatTheWindowManagerFocusedIsFocusedAndAHiddenOneIsNot() {
        assumeTrue(System.getenv("JOLT_TEST_NO_WINDOW_MANAGER") == null && System.getenv("JOLT_TEST_KIOSK_COMPOSITOR") == null,
                "focus is given by a window manager");
        CompletableFuture<String> result = new CompletableFuture<>();
        WebWindowContract.runApp(30, app -> {
            LinuxWindow window = (LinuxWindow) app.createWindow(WindowOptions.defaults().withSize(300, 200));
            window.ready().thenRun(() -> {
                window.focus();
                WebWindowContract.eventually(window::isFocused, 5000).thenAccept(focused -> {
                    window.hide();
                    WebWindowContract.eventually(() -> !window.isFocused(), 5000).thenAccept(notWhileHidden -> {
                        result.complete(focused + "," + notWhileHidden);
                        window.close();
                    });
                });
            });
        });
        assertEquals("true,true", result.getNow(null));
    }

    @Test
    void aFramelessPagesMinimizeButtonMinimizesTheWindow() {
        assumeTrue(System.getenv("JOLT_TEST_NO_WINDOW_MANAGER") == null && System.getenv("JOLT_TEST_KIOSK_COMPOSITOR") == null,
                "minimizing needs a window manager that honours it");
        CompletableFuture<Boolean> minimized = new CompletableFuture<>();
        WebWindowContract.runApp(40, app -> {
            LinuxWindow window = (LinuxWindow) app.createWindow(WindowOptions.defaults().withSize(400, 300).withFrameless(true));
            window.onMessage(message -> {
                window.eval("jolt.frame.minimize(); 'sent'");
                WebWindowContract.eventually(window::isMinimized, 5000).thenAccept(m -> {
                    minimized.complete(m);
                    window.close();
                });
            });
            window.loadHtml("<script>addEventListener('load', () => jolt.postMessage('go'))</script>");
        });
        assertTrue(minimized.getNow(false));
    }

    // ---- the tray ----

    @Test
    @DisabledIfEnvironmentVariable(named = "JOLT_TEST_TRAY_MOCK", matches = "1")
    void withoutATrayHostTheTrayIsNotSupportedAndCreatingOneFails() {
        assumeFalse(GtkTray.hostAvailable(), "this session bus has a real tray host");
        List<String> seen = new ArrayList<>();
        WebWindowContract.runApp(30, app -> {
            seen.add(String.valueOf(app.isTraySupported()));
            try {
                app.createTray(WebWindowContract.samplePng(), "x");
                seen.add("created");
            } catch (UnsupportedOperationException e) {
                seen.add("unsupported");
            }
            app.quit();
        });
        assertEquals(List.of("false", "unsupported"), seen);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JOLT_TEST_TRAY_MOCK", matches = "1")
    void aTrayIconIsRegisteredAndExportsItsPropertiesMenuAndClicks() throws Exception {
        AtomicInteger clicks = new AtomicInteger();
        AtomicInteger opened = new AtomicInteger();
        List<Boolean> toggles = new CopyOnWriteArrayList<>();
        List<String> outputs = new CopyOnWriteArrayList<>();
        String[] busName = new String[1];

        WebWindowContract.runApp(60, app -> {
            GtkTray tray = (GtkTray) app.createTray(WebWindowContract.sampleIco(), "Parsel: 3 unread");
            busName[0] = tray.busName();
            tray.onClick(clicks::incrementAndGet);
            tray.setMenu(TrayMenu.of(
                    TrayMenu.action("Open Parsel", opened::incrementAndGet),   // id 1
                    TrayMenu.separator(),                                       // id 2
                    TrayMenu.check("Close to tray", false, toggles::add),       // id 3
                    TrayMenu.disabled("3 unread_mail"),                         // id 4
                    TrayMenu.submenu("Status", TrayMenu.action("Online", () -> {}))));  // ids 5, 6
            CompletableFuture.runAsync(() -> {
                try {
                    String name = busName[0];
                    outputs.add(gdbus(name, "/StatusNotifierItem", "org.freedesktop.DBus.Properties.GetAll", "org.kde.StatusNotifierItem"));
                    outputs.add(waitForLayout(name, "Open Parsel"));
                    gdbus(name, "/MenuBar", "com.canonical.dbusmenu.Event", "1", "clicked", "<int32 0>", "0");
                    gdbus(name, "/MenuBar", "com.canonical.dbusmenu.Event", "3", "clicked", "<int32 0>", "0");
                    gdbus(name, "/StatusNotifierItem", "org.kde.StatusNotifierItem.Activate", "0", "0");
                    tray.setMenu(TrayMenu.of(TrayMenu.check("Close to tray", true, on -> { }), TrayMenu.action("Quit", () -> { })));
                    outputs.add(waitForLayout(name, "Quit"));
                    outputs.add(gdbus(name, "/MenuBar", "com.canonical.dbusmenu.GetGroupProperties", "[1]", "[]"));
                } catch (Exception e) {
                    outputs.add("error: " + e);
                } finally {
                    app.dispatch(() -> {
                        tray.close();
                        app.quit();
                    });
                }
            });
        });

        String properties = outputs.get(0);
        assertTrue(properties.contains("'Id': <'io.github.b077as.jolt.test'>"), properties);
        assertTrue(properties.contains("'Status': <'Active'>"), properties);
        assertTrue(properties.contains("'Menu': <objectpath '/MenuBar'>"), properties);
        assertTrue(properties.contains("Parsel: 3 unread"), properties);
        assertTrue(properties.contains("'IconPixmap': <[(24, 24, ["), "the icon must be offered as pixmaps: " + properties);
        assertTrue(properties.contains("(48, 48, ["), properties);

        String layout = outputs.get(1);
        assertTrue(layout.contains("'label': <'Open Parsel'>"), layout);
        assertTrue(layout.contains("'type': <'separator'>"), layout);
        assertTrue(layout.contains("'toggle-type': <'checkmark'>") && layout.contains("'toggle-state': <0>"), layout);
        assertTrue(layout.contains("'enabled': <false>") && layout.contains("3 unread__mail"), "disabled entry, underscore doubled: " + layout);
        assertTrue(layout.contains("'children-display': <'submenu'>") && layout.contains("'label': <'Online'>"), layout);

        assertEquals(1, opened.get(), "a click on the first entry must run its action");
        assertEquals(List.of(true), toggles, "a click on an unchecked check must ask for it to be checked");
        assertEquals(1, clicks.get(), "Activate is the primary click");
        assertTrue(outputs.get(2).contains("'label': <'Quit'>") && outputs.get(2).contains("'toggle-state': <1>"), outputs.get(2));

        assertTrue(Files.readString(REGISTERED, StandardCharsets.UTF_8).contains(busName[0]), "the watcher must have been told about the icon");
        assertFalse(gdbusOk(busName[0], "/StatusNotifierItem", "org.freedesktop.DBus.Properties.GetAll", "org.kde.StatusNotifierItem"),
                "the icon must be gone from the bus after close");
    }

    // ---- helpers ----

    /** Polls the menu layout until it mentions {@code expected} (a menu change is applied on the UI thread, a moment after setMenu). */
    private static String waitForLayout(String name, String expected) throws Exception {
        String last = "";
        for (int i = 0; i < 60; i++) {
            last = gdbus(name, "/MenuBar", "com.canonical.dbusmenu.GetLayout", "0", "int32 -1", "[]");
            if (last.contains(expected)) {
                return last;
            }
            Thread.sleep(50);
        }
        return last;
    }

    private static String gdbus(String name, String path, String method, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("gdbus", "call", "--session", "--dest", name, "--object-path", path, "--method", method));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("gdbus timed out: " + command);
        }
        if (process.exitValue() != 0) {
            throw new IOException("gdbus failed (" + process.exitValue() + "): " + command + "\n" + output);
        }
        return output;
    }

    private static boolean gdbusOk(String name, String path, String method, String... arguments) {
        try {
            gdbus(name, path, method, arguments);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
