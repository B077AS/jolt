package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.ColorScheme;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.contract.WebWindowContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads back what GTK and WebKit themselves hold for a real window after the appearance calls: the window icon, the dark
 * theme preference and the web view background. (The shared contract only proves the calls leave the window working.)
 *
 * <p>Wayland note: a Wayland compositor takes a window's icon from its desktop entry, so on Wayland the icon is not
 * visible to the user. GTK still stores it, which is what is checked here.
 */
@EnabledOnOs(OS.LINUX)
class LinuxAppearanceTest {

    /** Creates a window, runs {@code act}, then reads toolkit state queued behind everything {@code act} dispatched. */
    private static <T> T observe(WindowOptions options, Consumer<WebWindow> act, Function<LinuxWindow, T> read) {
        CompletableFuture<T> result = new CompletableFuture<>();
        WebWindowContract.runApp(30, app -> {
            LinuxWindow window = (LinuxWindow) app.createWindow(options.withSize(300, 200));
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

    // Typed wrappers, so the assert methods are not ambiguous about boxed and primitive results.
    private static boolean flag(WindowOptions options, Consumer<WebWindow> act, Function<LinuxWindow, Boolean> read) {
        return observe(options, act, read);
    }

    private static int number(WindowOptions options, Consumer<WebWindow> act, Function<LinuxWindow, Integer> read) {
        return observe(options, act, read);
    }

    @Test
    void noIconMeansGtkHoldsNone() {
        assertFalse(flag(WindowOptions.defaults(), NOTHING, w -> Gtk.windowHasIcon(w.nativeWindow())));
    }

    @Test
    void anIconInTheOptionsIsHeldByTheWindow() {
        assertTrue(flag(WindowOptions.defaults().withIcon(WebWindowContract.samplePng()), NOTHING,
                w -> Gtk.windowHasIcon(w.nativeWindow())));
    }

    @Test
    void anIconFileIsHeldByTheWindowViaItsLargestPngImage() {
        assertTrue(flag(WindowOptions.defaults().withIcon(WebWindowContract.sampleIco()), NOTHING,
                w -> Gtk.windowHasIcon(w.nativeWindow())));
        assertTrue(flag(WindowOptions.defaults(), w -> w.setIcon(WebWindowContract.sampleIco()),
                w -> Gtk.windowHasIcon(w.nativeWindow())));
    }

    @Test
    void settingTheIconLaterIsHeldByTheWindow() {
        assertTrue(flag(WindowOptions.defaults(), w -> w.setIcon(WebWindowContract.samplePng()),
                w -> Gtk.windowHasIcon(w.nativeWindow())));
    }

    @Test
    void anUndecodableIconLeavesTheWindowWithoutAnIcon() {
        assertFalse(flag(WindowOptions.defaults().withIcon("not a png".getBytes()), NOTHING,
                w -> Gtk.windowHasIcon(w.nativeWindow())));
    }

    @Test
    void theDarkThemePreferenceFollowsTheOptionsAndTheSetter() {
        assertTrue(flag(WindowOptions.defaults().withTitleBarScheme(ColorScheme.DARK), NOTHING, w -> Gtk.preferDarkTheme()));
        assertFalse(flag(WindowOptions.defaults().withTitleBarScheme(ColorScheme.LIGHT), NOTHING, w -> Gtk.preferDarkTheme()));
        assertTrue(flag(WindowOptions.defaults().withTitleBarScheme(ColorScheme.LIGHT),
                w -> w.setTitleBarScheme(ColorScheme.DARK), w -> Gtk.preferDarkTheme()));
        assertFalse(flag(WindowOptions.defaults().withTitleBarScheme(ColorScheme.DARK),
                w -> w.setTitleBarScheme(ColorScheme.LIGHT), w -> Gtk.preferDarkTheme()));
    }

    @Test
    void theWebViewBackgroundColourFromTheOptionsAndTheSetterIsHeld() {
        assertEquals(0x101418, number(WindowOptions.defaults().withBackgroundColor(0x101418), NOTHING,
                w -> Gtk.backgroundColor(w.nativeWebView())));
        assertEquals(0xF4F5FA, number(WindowOptions.defaults().withBackgroundColor(0x101418),
                w -> w.setBackgroundColor(0xF4F5FA), w -> Gtk.backgroundColor(w.nativeWebView())));
    }

    @Test
    void exactTitleBarColoursAreIgnoredWithoutHarm() {
        // Linux desktops own the title bar, so this is documented as unsupported; it must simply not break anything.
        String outcome = observe(WindowOptions.defaults(), w -> w.setTitleBarColors(0x101418, 0xE8EAED), w -> "ok");
        assertEquals("ok", outcome);
    }
}
