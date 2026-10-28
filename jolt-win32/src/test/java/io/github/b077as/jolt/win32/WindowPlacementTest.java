package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.contract.WebWindowContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a Windows window is put and how it is painted, read back from Windows itself: the shared contract can only see
 * that the calls are accepted, not that the window really ended up in the middle of the screen or really got its colour.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowPlacementTest {

    private record Observed(int[] bounds, int[] workArea, boolean visible, boolean maximized, int backgroundRgb) {}

    /** Creates a window with {@code options}, runs {@code act}, and reads what Windows holds once everything has settled. */
    private static Observed observe(WindowOptions options, java.util.function.Consumer<WebWindow> act) {
        CompletableFuture<Observed> result = new CompletableFuture<>();
        WebWindowContract.runApp(30, app -> {
            WindowsWindow window = (WindowsWindow) app.createWindow(options);
            window.ready().thenRun(() -> {
                act.accept(window);
                app.dispatch(() -> {
                    try {
                        result.complete(new Observed(window.outerBounds(), Win32.workAreaOf(window.hwnd()), Win32.isWindowVisible(window.hwnd()),
                                Win32.isZoomed(window.hwnd()), window.readWindowBackgroundRgb()));
                    } catch (Throwable t) {
                        result.completeExceptionally(t);
                    }
                    window.close();
                });
            });
        });
        return result.getNow(null);
    }

    private static final java.util.function.Consumer<WebWindow> NOTHING = w -> { };

    @Test
    void aNewWindowIsCenteredInTheUsableAreaOfTheScreen() {
        Observed o = observe(WindowOptions.defaults().withSize(700, 500), NOTHING);
        int width = o.bounds[2] - o.bounds[0];
        int height = o.bounds[3] - o.bounds[1];
        int expectedLeft = o.workArea[0] + (o.workArea[2] - o.workArea[0] - width) / 2;
        int expectedTop = o.workArea[1] + (o.workArea[3] - o.workArea[1] - height) / 2;
        assertEquals(expectedLeft, o.bounds[0], "left edge");
        assertEquals(expectedTop, o.bounds[1], "top edge");
    }

    @Test
    void centeringCanBeSwitchedOffAndThenWindowsPlacesTheWindow() {
        Observed centered = observe(WindowOptions.defaults().withSize(700, 500), NOTHING);
        Observed notCentered = observe(WindowOptions.defaults().withSize(700, 500).withCentered(false), NOTHING);
        assertNotEquals(centered.bounds[0] + "," + centered.bounds[1], notCentered.bounds[0] + "," + notCentered.bounds[1],
                "the operating system's own placement is not the centre of the screen");
    }

    @Test
    void aWindowLargerThanTheScreenKeepsItsTitleBarOnScreen() {
        Observed o = observe(WindowOptions.defaults().withSize(20_000, 20_000), NOTHING);
        assertTrue(o.bounds[0] >= o.workArea[0], "left edge on screen: " + o.bounds[0]);
        assertTrue(o.bounds[1] >= o.workArea[1], "top edge on screen: " + o.bounds[1]);
    }

    private static int[] outerSize(Observed o) {
        return new int[] {o.bounds[2] - o.bounds[0], o.bounds[3] - o.bounds[1]};
    }

    @Test
    void aWindowCannotBeMadeSmallerThanItsMinimumSize() {
        int[] expected = outerSize(observe(WindowOptions.defaults().withSize(600, 400), NOTHING));
        Observed shrunk = observe(WindowOptions.defaults().withSize(900, 700).withMinSize(600, 400), w -> w.setSize(200, 150));
        assertArrayEquals(expected, outerSize(shrunk), "outer size after asking for 200x150 with a 600x400 minimum");
    }

    @Test
    void aMinimumSizeDoesNotStopAWindowFromBeingLarger() {
        int[] expected = outerSize(observe(WindowOptions.defaults().withSize(900, 700), NOTHING));
        Observed o = observe(WindowOptions.defaults().withSize(900, 700).withMinSize(600, 400), NOTHING);
        assertArrayEquals(expected, outerSize(o));
    }

    @Test
    void aWidthOnlyMinimumLeavesTheHeightFree() {
        int[] tall = outerSize(observe(WindowOptions.defaults().withSize(600, 150), NOTHING));
        Observed o = observe(WindowOptions.defaults().withSize(900, 700).withMinSize(600, 0), w -> w.setSize(200, 150));
        assertArrayEquals(tall, outerSize(o));
    }

    @Test
    void aWindowCreatedMaximizedFillsTheUsableArea() {
        Observed o = observe(WindowOptions.defaults().withMaximized(true), NOTHING);
        assertTrue(o.maximized, "Windows reports it as maximized");
        // a maximized window's frame reaches a few pixels past the work area on each side, never short of it
        assertTrue(o.bounds[0] <= o.workArea[0] && o.bounds[1] <= o.workArea[1], "top left covers the work area");
        assertTrue(o.bounds[2] >= o.workArea[2] && o.bounds[3] >= o.workArea[3], "bottom right covers the work area");
    }

    @Test
    void maximizingAHiddenWindowDoesNotShowItAndShowingItMaximizesIt() {
        Observed whileHidden = observe(WindowOptions.defaults().withVisible(false), w -> w.maximize());
        assertFalse(whileHidden.visible, "still hidden");
        assertFalse(whileHidden.maximized, "nothing is maximized until the window is shown");

        Observed afterShow = observe(WindowOptions.defaults().withVisible(false), w -> {
            w.maximize();
            w.show();
        });
        assertTrue(afterShow.visible);
        assertTrue(afterShow.maximized);
    }

    @Test
    void restoreUndoesMaximize() {
        Observed o = observe(WindowOptions.defaults().withMaximized(true), WebWindow::restore);
        assertFalse(o.maximized);
    }

    @Test
    void theBackgroundColourInTheOptionsIsWhatTheWindowIsPaintedWith() {
        assertEquals(0x336699, observe(WindowOptions.defaults().withBackgroundColor(0x336699), NOTHING).backgroundRgb);
    }

    @Test
    void noBackgroundColourLeavesTheWindowPaintingAsBefore() {
        assertEquals(-1, observe(WindowOptions.defaults(), NOTHING).backgroundRgb);
    }

    @Test
    void settingTheBackgroundColourLaterChangesWhatTheWindowIsPaintedWith() {
        Observed o = observe(WindowOptions.defaults().withBackgroundColor(0x336699), w -> w.setBackgroundColor(0x112233));
        assertEquals(0x112233, o.backgroundRgb);
    }

    // ---- the brush really reaches the pixels: ask the window to erase into an off-screen bitmap and read one back ----

    private static final SymbolLookup GDI32 = SymbolLookup.libraryLookup("gdi32.dll", Arena.global());
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle CREATE_COMPATIBLE_DC = LINKER.downcallHandle(GDI32.find("CreateCompatibleDC").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CREATE_COMPATIBLE_BITMAP = LINKER.downcallHandle(GDI32.find("CreateCompatibleBitmap").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle SELECT_OBJECT = LINKER.downcallHandle(GDI32.find("SelectObject").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GET_PIXEL = LINKER.downcallHandle(GDI32.find("GetPixel").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle DELETE_DC = LINKER.downcallHandle(GDI32.find("DeleteDC").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle DELETE_OBJECT = LINKER.downcallHandle(GDI32.find("DeleteObject").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
    private static final MethodHandle GET_DC = LINKER.downcallHandle(USER32.find("GetDC").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle RELEASE_DC = LINKER.downcallHandle(USER32.find("ReleaseDC").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** What a pixel of the window's own background looks like after Windows asks it to erase: {@code 0xRRGGBB}, or -1 if it declined. */
    private static int erasedPixel(WindowsWindow window) throws Throwable {
        MemorySegment screen = (MemorySegment) GET_DC.invokeExact(MemorySegment.NULL);
        MemorySegment memory = (MemorySegment) CREATE_COMPATIBLE_DC.invokeExact(screen);
        MemorySegment bitmap = (MemorySegment) CREATE_COMPATIBLE_BITMAP.invokeExact(screen, 64, 64);
        MemorySegment previous = (MemorySegment) SELECT_OBJECT.invokeExact(memory, bitmap);
        try {
            Long handled = window.handleMessage(Win32.WM_ERASEBKGND, memory.address(), 0);
            if (handled == null || handled != 1L) {
                return -1;
            }
            int colorRef = (int) GET_PIXEL.invokeExact(memory, 10, 10);
            return WindowsWindow.colorRef(colorRef) & 0xFFFFFF;
        } finally {
            MemorySegment ignoredSelected = (MemorySegment) SELECT_OBJECT.invokeExact(memory, previous);
            int ignoredBitmap = (int) DELETE_OBJECT.invokeExact(bitmap);
            int ignoredDc = (int) DELETE_DC.invokeExact(memory);
            int ignoredRelease = (int) RELEASE_DC.invokeExact(MemorySegment.NULL, screen);
        }
    }

    private static <T> T onWindow(WindowOptions options, Function<WindowsWindow, T> read) {
        CompletableFuture<T> result = new CompletableFuture<>();
        WebWindowContract.runApp(30, app -> {
            WindowsWindow window = (WindowsWindow) app.createWindow(options);
            window.ready().thenRun(() -> app.dispatch(() -> {
                try {
                    result.complete(read.apply(window));
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
                window.close();
            }));
        });
        return result.getNow(null);
    }

    @Test
    void erasingTheWindowBackgroundPaintsTheChosenColourIntoThePixels() {
        int pixel = onWindow(WindowOptions.defaults().withBackgroundColor(0x336699), w -> {
            try {
                return erasedPixel(w);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        });
        assertEquals(0x336699, pixel);
    }

    @Test
    void withoutABackgroundColourTheWindowLeavesErasingToWindows() {
        int pixel = onWindow(WindowOptions.defaults(), w -> {
            try {
                return erasedPixel(w);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        });
        assertEquals(-1, pixel, "the message is not handled, so the system default applies");
    }
}
