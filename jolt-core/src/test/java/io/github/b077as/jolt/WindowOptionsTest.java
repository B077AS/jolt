package io.github.b077as.jolt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WindowOptionsTest {

    @Test
    void defaultsHaveNoAppearanceOverrides() {
        WindowOptions defaults = WindowOptions.defaults();
        assertNull(defaults.icon());
        assertNull(defaults.titleBarScheme());
        assertNull(defaults.backgroundRgb());
    }

    @Test
    void withMethodsKeepEveryOtherField() {
        WindowOptions o = WindowOptions.defaults().withTitle("T").withSize(800, 600).withDevTools(true).withVisible(false)
                .withIcon(new byte[] {1, 2, 3}).withTitleBarScheme(ColorScheme.DARK).withBackgroundColor(0x123456);
        // change one field last and confirm all the others survived
        WindowOptions changed = o.withTitle("U");
        assertEquals("U", changed.title());
        assertEquals(800, changed.width());
        assertEquals(600, changed.height());
        assertEquals(true, changed.devTools());
        assertEquals(false, changed.visible());
        assertArrayEquals(new byte[] {1, 2, 3}, changed.icon());
        assertEquals(ColorScheme.DARK, changed.titleBarScheme());
        assertEquals(0x123456, changed.backgroundRgb());
    }

    @Test
    void theIconIsCopiedSoLaterChangesToTheArrayDoNotLeakIn() {
        byte[] png = {1, 2, 3};
        WindowOptions o = WindowOptions.defaults().withIcon(png);
        png[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, o.icon());
        assertNotSame(png, o.icon());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> WindowOptions.defaults().withBackgroundColor(-1));
        assertThrows(IllegalArgumentException.class, () -> WindowOptions.defaults().withBackgroundColor(0x1000000));
        assertThrows(IllegalArgumentException.class, () -> WindowOptions.defaults().withSize(0, 10));
        assertThrows(NullPointerException.class, () -> WindowOptions.defaults().withTitle(null));
    }
}
