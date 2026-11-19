package io.github.b077as.jolt;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuTest {

    @Test
    void buildsEntriesWithAcceleratorsGivenAsTextOrShortcuts() {
        Menu.Action a = Menu.action("New", "Ctrl+N", () -> {});
        Menu.Action b = Menu.action("Open", Shortcut.parse("Ctrl+O"), () -> {});
        Menu.Action c = Menu.action("Plain", () -> {});
        assertEquals(Shortcut.parse("Ctrl+N"), a.accelerator());
        assertEquals(Shortcut.parse("Ctrl+O"), b.accelerator());
        assertNull(c.accelerator());
        assertTrue(c.enabled());
    }

    @Test
    void aBadAcceleratorFailsWhenTheEntryIsBuilt() {
        assertThrows(IllegalArgumentException.class, () -> Menu.action("New", "Ctrl+", () -> {}));
    }

    @Test
    void entriesAreChangedByCopying() {
        Menu.Action original = Menu.action("Save", "Ctrl+S", () -> {});
        Menu.Action off = original.withEnabled(false);
        assertTrue(original.enabled());
        assertEquals(false, off.enabled());
        assertEquals(original.accelerator(), off.accelerator());
        assertNull(original.withAccelerator(null).accelerator());
        assertEquals(false, Menu.check("Dense", true, on -> {}).withEnabled(false).enabled());
        assertEquals(false, Menu.submenu("More").withEnabled(false).enabled());
        assertEquals(false, Menu.disabled("Nothing").enabled());
    }

    @Test
    void aMenuIsACopy() {
        List<Menu.Item> items = new ArrayList<>(List.of(Menu.action("One", () -> {})));
        Menu menu = new Menu(items);
        items.add(Menu.action("Two", () -> {}));
        assertEquals(1, menu.items().size());
        assertThrows(UnsupportedOperationException.class, () -> menu.items().add(Menu.separator()));
    }

    @Test
    void aNestedMenuKeepsItsStructure() {
        Menu menu = Menu.of(Menu.submenu("&File", Menu.action("&New", "Ctrl+N", () -> {}), Menu.separator(),
                Menu.submenu("Recent", Menu.disabled("(none)"))));
        Menu.Submenu file = (Menu.Submenu) menu.items().get(0);
        assertEquals(3, file.items().size());
        assertEquals(1, ((Menu.Submenu) file.items().get(2)).items().size());
    }

    @Test
    void nestingIsLimited() {
        Menu.Item level = Menu.action("leaf", () -> {});
        for (int i = 0; i < Menu.MAX_DEPTH - 1; i++) {
            level = Menu.submenu("level " + i, level);
        }
        Menu.Item fine = level;
        new Menu(List.of(fine)); // exactly MAX_DEPTH levels
        assertThrows(IllegalArgumentException.class, () -> new Menu(List.of(Menu.submenu("one too many", fine))));
    }

    @Test
    void theNumberOfEntriesIsLimitedAcrossSubmenus() {
        List<Menu.Item> many = new ArrayList<>();
        for (int i = 0; i < Menu.MAX_ITEMS; i++) {
            many.add(Menu.separator());
        }
        new Menu(many);
        List<Menu.Item> tooMany = new ArrayList<>(many);
        tooMany.add(Menu.separator());
        assertThrows(IllegalArgumentException.class, () -> new Menu(tooMany));
        assertThrows(IllegalArgumentException.class, () -> new Menu(List.of(Menu.submenu("s", many.toArray(new Menu.Item[0])), Menu.separator())));
    }

    @Test
    void entriesNeedALabelAndACallback() {
        assertThrows(NullPointerException.class, () -> Menu.action(null, () -> {}));
        assertThrows(NullPointerException.class, () -> Menu.action("x", (Runnable) null));
        assertThrows(NullPointerException.class, () -> Menu.check("x", false, null));
        assertThrows(NullPointerException.class, () -> Menu.submenu(null));
    }
}
