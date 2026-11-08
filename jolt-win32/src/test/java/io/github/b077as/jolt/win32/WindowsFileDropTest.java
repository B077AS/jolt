package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.FileDrop;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.contract.WebWindowContract;
import io.github.b077as.jolt.internal.ffi.Native;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real operating-system drag and drop, in and out of a Jolt window, with injected mouse input: a drag of a real file is started
 * with the shell's own drag loop ({@link WindowsFileDrag}, the code that drags files out of a page) and let go over a drop zone of
 * the same window, where WebView2 receives it as a drop of files from Explorer and the page script reports it.
 *
 * <p>These tests move the real mouse pointer for about two seconds and need the window to be visible on an unlocked desktop; set
 * {@code JOLT_TEST_NO_POINTER_INPUT} to skip them.
 */
@EnabledOnOs(OS.WINDOWS)
@DisabledIfEnvironmentVariable(named = "JOLT_TEST_NO_POINTER_INPUT", matches = ".+")
class WindowsFileDropTest {

    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
    private static final MethodHandle SEND_INPUT = Native.downcall(USER32, "SendInput", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle GET_WINDOW_RECT = Native.downcall(USER32, "GetWindowRect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle SET_WINDOW_POS = Native.downcall(USER32, "SetWindowPos",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle GET_SYSTEM_METRICS = Native.downcall(USER32, "GetSystemMetrics", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    private static final int MOUSEEVENTF_MOVE = 0x1, MOUSEEVENTF_LEFTDOWN = 0x2, MOUSEEVENTF_LEFTUP = 0x4;
    private static final int MOUSEEVENTF_VIRTUALDESK = 0x4000, MOUSEEVENTF_ABSOLUTE = 0x8000;
    private static final int SM_XVIRTUALSCREEN = 76, SM_YVIRTUALSCREEN = 77, SM_CXVIRTUALSCREEN = 78, SM_CYVIRTUALSCREEN = 79;
    private static final int SWP_NOSIZE = 0x1, SWP_NOMOVE = 0x2, SWP_SHOWWINDOW = 0x40;

    private static final String PAGE = """
            <body style="margin:0">
            <div data-jolt-drop="docs" style="position:fixed;left:0;top:0;right:0;bottom:0"></div>
            <script>

              jolt.postMessage('ready');
            </script>
            """;

    /** Moves the pointer (physical pixels on the virtual desktop) and/or presses and releases the left button. */
    private static void mouse(int flags, int x, int y) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocate(40, 8); // INPUT: type(4) pad(4) MOUSEINPUT{dx dy data flags time pad extra}
            int left = metric(SM_XVIRTUALSCREEN), top = metric(SM_YVIRTUALSCREEN);
            int width = metric(SM_CXVIRTUALSCREEN), height = metric(SM_CYVIRTUALSCREEN);
            input.set(JAVA_INT, 0, 0); // INPUT_MOUSE
            input.set(JAVA_INT, 8, (int) ((x - left) * 65535L / (width - 1)));
            input.set(JAVA_INT, 12, (int) ((y - top) * 65535L / (height - 1)));
            input.set(JAVA_INT, 20, flags | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK);
            input.set(JAVA_LONG, 32, 0L);
            int sent = (int) SEND_INPUT.invokeExact(1, input, 40);
            assertEquals(1, sent, "SendInput was blocked (is the desktop locked or an elevated window in front?)");
        } catch (Throwable t) {
            throw t instanceof RuntimeException r ? r : new IllegalStateException(t);
        }
    }

    private static int metric(int index) {
        try {
            return (int) GET_SYSTEM_METRICS.invokeExact(index);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** {left, top, right, bottom} of the window in physical pixels. */
    private static int[] rect(WindowsWindow window) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rect = arena.allocate(16, 4);
            int ok = (int) GET_WINDOW_RECT.invokeExact(window.hwnd(), rect);
            assertTrue(ok != 0, "GetWindowRect");
            return new int[] {rect.get(JAVA_INT, 0), rect.get(JAVA_INT, 4), rect.get(JAVA_INT, 8), rect.get(JAVA_INT, 12)};
        } catch (Throwable t) {
            throw t instanceof RuntimeException r ? r : new IllegalStateException(t);
        }
    }

    /** Puts the window above every other, so the pointer is over it and nothing steals the drop. */
    private static void bringToTop(WindowsWindow window) {
        try {
            int ok = (int) SET_WINDOW_POS.invokeExact(window.hwnd(), MemorySegment.ofAddress(-1), 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW);
            assertTrue(ok != 0, "SetWindowPos");
        } catch (Throwable t) {
            throw t instanceof RuntimeException r ? r : new IllegalStateException(t);
        }
    }

    /**
     * Presses the button in the middle of {@code window}, starts the shell drag of {@code files} with it still down, moves the pointer a
     * little over the window and lets go: a drop of those files onto the window.
     *
     * @return completes with whether the drag was accepted as a copy
     */
    private static CompletableFuture<Boolean> dragOntoItself(WindowsApplication app, WindowsWindow window, List<Path> files) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Thread driver = new Thread(() -> {
            int[] r = rect(window);
            int x = (r[0] + r[2]) / 2, y = (r[1] + r[3]) / 2;
            try {
                Thread.sleep(600); // the page is up and WebView2 has registered its drop target
                bringToTop(window);
                Thread.sleep(200);
                mouse(MOUSEEVENTF_MOVE, x, y);
                Thread.sleep(150);
                mouse(MOUSEEVENTF_LEFTDOWN, x, y);
                Thread.sleep(150);
                app.dispatch(() -> {
                    try {
                        result.complete(WindowsFileDrag.run(window.hwnd(), files)); // returns when the button goes up
                    } catch (RuntimeException e) {
                        result.completeExceptionally(e);
                    }
                });
                Thread.sleep(600); // the drag loop is running
                for (int i = 1; i <= 8; i++) {
                    mouse(MOUSEEVENTF_MOVE, x + i * 6, y + i * 4);
                    Thread.sleep(80);
                }
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                mouse(MOUSEEVENTF_LEFTUP, x + 48, y + 32); // always, so the button is never left down
            }
        }, "pointer-driver");
        driver.setDaemon(true);
        driver.start();
        return result;
    }

    /** Page for the drag-out tests: something to drag on the left, a drop zone on the right. */
    private static final String ROUND_TRIP_PAGE = """
            <body style="margin:0">
            <div draggable="true" data-jolt-drag-file="doc:1" style="position:fixed;left:0;top:0;width:50%;bottom:0;background:#9cf"></div>
            <div data-jolt-drop="target" style="position:fixed;left:50%;top:0;right:0;bottom:0;background:#fc9"></div>
            <script>jolt.postMessage('ready')</script>
            """;

    /**
     * Presses the left button a quarter of the way across {@code window} and drags the pointer slowly to three quarters of the way,
     * as a person would; the web view starts its own drag on the way, and whatever takes over is what the test is about.
     *
     * @return completes when the button has been let go
     */
    private static CompletableFuture<Void> mouseDragAcross(WindowsWindow window) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread driver = new Thread(() -> {
            int[] r = rect(window);
            int y = (r[1] + r[3]) / 2;
            int from = r[0] + (r[2] - r[0]) / 4, to = r[0] + 3 * (r[2] - r[0]) / 4;
            try {
                Thread.sleep(600);
                bringToTop(window);
                Thread.sleep(200);
                mouse(MOUSEEVENTF_MOVE, from, y);
                Thread.sleep(150);
                mouse(MOUSEEVENTF_LEFTDOWN, from, y);
                Thread.sleep(150);
                int steps = 40;
                for (int i = 1; i <= steps; i++) {
                    mouse(MOUSEEVENTF_MOVE, from + (to - from) * i / steps, y);
                    Thread.sleep(40);
                }
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                mouse(MOUSEEVENTF_LEFTUP, to, y); // always, so the button is never left down
                done.complete(null);
            }
        }, "pointer-driver");
        driver.setDaemon(true);
        driver.start();
        return done;
    }

    private static List<Path> realPaths(List<Path> paths) {
        return paths.stream().map(p -> {
            try {
                return p.toRealPath();
            } catch (java.io.IOException e) {
                return p;
            }
        }).toList();
    }

    @Test
    void anElementMarkedForFileDragCanBeDraggedToAnotherZoneAndArrivesAsAFileDrop() throws Exception {
        Path file = Files.createTempFile("jolt-drag-", ".txt");
        List<FileDrop> drops = new CopyOnWriteArrayList<>();
        List<String> asked = new CopyOnWriteArrayList<>();
        List<String> finished = new CopyOnWriteArrayList<>();
        try {
            WebWindowContract.runApp(60, app -> {
                WindowsWindow window = (WindowsWindow) app.createWindow(WindowOptions.defaults().withSize(600, 400));
                window.setFileDragSource(new io.github.b077as.jolt.FileDragSource() {
                    @Override
                    public List<Path> filesFor(String token) {
                        asked.add(token);
                        return List.of(file);
                    }

                    @Override
                    public void finished(String token, boolean dropped) {
                        finished.add(token + ":" + dropped);
                    }
                });
                window.onFileDrop(drops::add);
                window.onMessage(message -> {
                    if (message.equals("ready")) {
                        mouseDragAcross(window).thenRun(() -> app.dispatch(() ->
                                WebWindowContract.eventually(() -> !drops.isEmpty() && !finished.isEmpty(), 5000)
                                        .whenComplete((v, e) -> app.dispatch(window::close))));
                    }
                });
                window.loadHtml(ROUND_TRIP_PAGE);
            });
            assertEquals(List.of("doc:1"), asked, "the source was asked once, with the page's token");
            assertEquals(1, drops.size(), "onFileDrop calls");
            assertEquals(realPaths(List.of(file)), realPaths(drops.get(0).files()));
            assertEquals("target", drops.get(0).zone());
            assertEquals(List.of("doc:1:true"), finished, "the source learns the files were taken");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void aSourceThatRefusesATokenStartsNoDrag() throws Exception {
        List<FileDrop> drops = new CopyOnWriteArrayList<>();
        List<String> asked = new CopyOnWriteArrayList<>();
        List<String> finished = new CopyOnWriteArrayList<>();
        WebWindowContract.runApp(60, app -> {
            WindowsWindow window = (WindowsWindow) app.createWindow(WindowOptions.defaults().withSize(600, 400));
            window.setFileDragSource(new io.github.b077as.jolt.FileDragSource() {
                @Override
                public List<Path> filesFor(String token) {
                    asked.add(token);
                    return null; // refuse
                }

                @Override
                public void finished(String token, boolean dropped) {
                    finished.add(token);
                }
            });
            window.onFileDrop(drops::add);
            window.onMessage(message -> {
                if (message.equals("ready")) {
                    mouseDragAcross(window).thenRun(() -> app.dispatch(() ->
                            WebWindowContract.eventually(() -> false, 1500).whenComplete((v, e) -> app.dispatch(window::close))));
                }
            });
            window.loadHtml(ROUND_TRIP_PAGE);
        });
        assertEquals(List.of("doc:1"), asked);
        assertTrue(drops.isEmpty(), "nothing was offered, so nothing can have been dropped");
        assertTrue(finished.isEmpty(), "no drag started, so none finished");
    }

    @Test
    void filesDraggedFromTheShellOntoAZoneReachOnFileDropWithTheirPathAndTheZone() throws Exception {
        Path file = Files.createTempFile("jolt-drop-", ".txt");
        List<FileDrop> drops = new CopyOnWriteArrayList<>();
        CompletableFuture<Boolean> accepted = new CompletableFuture<>();
        try {
            WebWindowContract.runApp(60, app -> {
                WindowsWindow window = (WindowsWindow) app.createWindow(WindowOptions.defaults().withSize(500, 400));
                window.onFileDrop(drop -> {
                    drops.add(drop);
                    window.close();
                });
                window.onMessage(message -> {
                    if (message.equals("ready")) {
                        dragOntoItself((WindowsApplication) app, window, List.of(file)).whenComplete((ok, error) -> {
                            if (error != null) {
                                accepted.completeExceptionally(error);
                            } else {
                                accepted.complete(ok);
                            }
                            // the drop message may still be on its way; give it a moment, then end the test either way
                            app.dispatch(() -> {
                                WebWindowContract.eventually(() -> !drops.isEmpty(), 5000).whenComplete((v, e) -> app.dispatch(window::close));
                            });
                        });
                    }
                });
                window.loadHtml(PAGE);
            });
            assertTrue(accepted.get(1, TimeUnit.SECONDS), "the shell reported no copy: WebView2 did not take the drop");
            assertEquals(1, drops.size(), "onFileDrop calls");
            FileDrop drop = drops.get(0);
            assertEquals(List.of(file.toRealPath()), drop.files().stream().map(p -> {
                try {
                    return p.toRealPath();
                } catch (java.io.IOException e) {
                    return p;
                }
            }).toList());
            assertEquals("docs", drop.zone());
            assertTrue(drop.x() > 0 && drop.y() > 0, "the pointer position is in page pixels: " + drop);
            assertFalse(drop.toString().contains(file.getFileName().toString()), "paths stay out of toString");
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
