package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.ColorScheme;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.contract.WebWindowContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Reads back what Windows itself holds for a real window after the appearance calls: the icon it installed and the
 * DWM attributes of the title bar. (The shared contract only proves the calls leave the window working.)
 */
@EnabledOnOs(OS.WINDOWS)
class WindowAppearanceTest {

    /**
     * Creates a window, lets {@code act} call setters on it, and then reads native state with {@code read}. The read is
     * queued behind everything {@code act} dispatched, so it sees the setters' effect.
     */
    private static <T> T observe(WindowOptions options, Consumer<WebWindow> act, Function<WindowsWindow, T> read) {
        CompletableFuture<T> result = new CompletableFuture<>();
        WebWindowContract.runApp(30, app -> {
            WindowsWindow window = (WindowsWindow) app.createWindow(options.withSize(300, 200));
            window.ready().thenRun(() -> {
                act.accept(window);
                app.dispatch(() -> {
                    try {
                        result.complete(read.apply(window));
                    } catch (Throwable t) {
                        result.completeExceptionally(t);
                    }
                    window.close();
                });
            });
        });
        return result.getNow(null);
    }

    private static final Consumer<WebWindow> NOTHING = w -> { };

    private static long[] icons(WindowsWindow w) {
        return new long[] {Win32.sendMessage(w.hwnd(), Win32.WM_GETICON, Win32.ICON_BIG, 0),
                Win32.sendMessage(w.hwnd(), Win32.WM_GETICON, Win32.ICON_SMALL, 0)};
    }

    private static Integer darkMode(WindowsWindow w) {
        return Win32.dwmGetAttribute(w.hwnd(), Win32.DWMWA_USE_IMMERSIVE_DARK_MODE);
    }

    @Test
    void noIconMeansNoIconWasInstalled() {
        long[] icons = observe(WindowOptions.defaults(), NOTHING, WindowAppearanceTest::icons);
        assertEquals(0, icons[0]);
        assertEquals(0, icons[1]);
    }

    @Test
    void anIconInTheOptionsInstallsBothSizes() {
        long[] icons = observe(WindowOptions.defaults().withIcon(WebWindowContract.samplePng()), NOTHING, WindowAppearanceTest::icons);
        assertNotEquals(0, icons[0], "big icon");
        assertNotEquals(0, icons[1], "small icon");
        assertNotEquals(icons[0], icons[1], "each size is decoded separately");
    }

    @Test
    void anIconFileInstallsBothSizes() {
        long[] icons = observe(WindowOptions.defaults().withIcon(WebWindowContract.sampleIco()), NOTHING, WindowAppearanceTest::icons);
        assertNotEquals(0, icons[0], "big icon");
        assertNotEquals(0, icons[1], "small icon");
    }

    @Test
    void theIconsHaveTheSizesWindowsAsksForWhetherTheySourceIsAPngOrAnIconFile() {
        for (byte[] source : new byte[][] {WebWindowContract.sampleIco(), WebWindowContract.samplePng()}) {
            int[] sizes = observe(WindowOptions.defaults().withIcon(source), NOTHING, w -> {
                int dpi = Win32.dpiForWindow(w.hwnd());
                return new int[] {
                        Win32.iconWidth(MemorySegment.ofAddress(icons(w)[0])), Win32.systemMetricForDpi(Win32.SM_CXICON, dpi),
                        Win32.iconWidth(MemorySegment.ofAddress(icons(w)[1])), Win32.systemMetricForDpi(Win32.SM_CXSMICON, dpi)};
            });
            assertEquals(sizes[1], sizes[0], "big icon width");
            assertEquals(sizes[3], sizes[2], "small icon width");
        }
    }

    @Test
    void settingTheIconLaterInstallsItAndReplacingItIsSafe() {
        long[] icons = observe(WindowOptions.defaults(), w -> {
            w.setIcon(WebWindowContract.samplePng());
            w.setIcon(WebWindowContract.samplePng()); // replaces the first; the old handles must be released without trouble
        }, WindowAppearanceTest::icons);
        assertNotEquals(0, icons[0]);
        assertNotEquals(0, icons[1]);
    }

    @Test
    void anUndecodableIconLeavesTheWindowWithoutAnIcon() {
        long[] icons = observe(WindowOptions.defaults().withIcon("not a png".getBytes()), NOTHING, WindowAppearanceTest::icons);
        assertEquals(0, icons[0]);
        assertEquals(0, icons[1]);
    }

    @Test
    void theSchemeInTheOptionsIsAlreadyInEffectWhenTheWindowIsReady() {
        assertEquals(1, observe(WindowOptions.defaults().withTitleBarScheme(ColorScheme.DARK), NOTHING, WindowAppearanceTest::darkMode));
        assertEquals(0, observe(WindowOptions.defaults().withTitleBarScheme(ColorScheme.LIGHT), NOTHING, WindowAppearanceTest::darkMode));
    }

    @Test
    void theSchemeCanBeSwitchedBothWaysAtRuntime() {
        assertEquals(1, observe(WindowOptions.defaults().withTitleBarScheme(ColorScheme.LIGHT),
                w -> w.setTitleBarScheme(ColorScheme.DARK), WindowAppearanceTest::darkMode));
        assertEquals(0, observe(WindowOptions.defaults().withTitleBarScheme(ColorScheme.DARK),
                w -> w.setTitleBarScheme(ColorScheme.LIGHT), WindowAppearanceTest::darkMode));
    }

    // Exact title bar colours (setTitleBarColors) cannot be asserted here: DwmGetWindowAttribute does not return the
    // Windows 11 caption and text colour attributes. They are checked by looking at a screenshot of the real window.

    @Test
    void theWebViewBackgroundColourFromTheOptionsIsHeld() {
        assertEquals(0x101418, observe(WindowOptions.defaults().withBackgroundColor(0x101418), NOTHING, WindowsWindow::readBackgroundRgb));
    }

    @Test
    void theWebViewBackgroundColourCanBeChangedAtRuntime() {
        assertEquals(0xF7F7F5, observe(WindowOptions.defaults().withBackgroundColor(0x101418),
                w -> w.setBackgroundColor(0xF7F7F5), WindowsWindow::readBackgroundRgb));
    }

    @Test
    void win32ColourReferencesAreBlueGreenRed() {
        // 0xRRGGBB = 0x112233 is red 0x11, green 0x22, blue 0x33; a COLORREF stores it as 0x00BBGGRR = 0x00332211
        assertEquals(0x00332211, WindowsWindow.colorRef(0x112233));
        assertEquals(0x00FF0000, WindowsWindow.colorRef(0x0000FF)); // blue ends up in the third byte
        assertEquals(0x000000FF, WindowsWindow.colorRef(0xFF0000)); // red ends up in the lowest byte
    }
}
