package io.github.b077as.jolt;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A menu: the menu bar of a window ({@link WebWindow#setMenuBar}) or the menu shown for a right click in the page
 * ({@link WebWindow#setContextMenu}). It is a list of actions, checkboxes, separators and submenus, and an entry can have an
 * <em>accelerator</em> (keyboard shortcut). A menu is immutable; to change one, build a new menu and set it again. Callbacks run on the
 * UI thread.
 *
 * <p><b>Labels.</b> {@code &} marks the letter that opens the entry with Alt (or the mnemonic on Linux): {@code "&File"}. Write
 * {@code &&} for a real ampersand. A label is otherwise shown as written.
 *
 * <pre>{@code
 * window.setMenuBar(Menu.of(
 *         Menu.submenu("&File",
 *                 Menu.action("&New window", "Ctrl+N", () -> newWindow()),
 *                 Menu.action("&Open...", "Ctrl+O", () -> open()),
 *                 Menu.separator(),
 *                 Menu.action("E&xit", "Alt+F4", app::quit)),
 *         Menu.submenu("&View",
 *                 Menu.check("&Dense list", dense, "Ctrl+D", on -> setDense(on)),
 *                 Menu.action("&Reload", "F5", () -> window.eval("location.reload()"))),
 *         Menu.submenu("&Help", Menu.action("&About", () -> about()))));
 * }</pre>
 *
 * <p>Accelerators of a menu bar work while the page has the keyboard focus, and the page does not see those key presses. In a context menu
 * they are only shown next to the entry.
 */
public record Menu(List<Item> items) {

    /** The deepest nesting of submenus a menu may have (a menu bar's entries count as the first level). */
    public static final int MAX_DEPTH = 8;
    /** The most entries a menu may have, all submenus together. */
    public static final int MAX_ITEMS = 1000;

    public Menu {
        items = List.copyOf(items);
        int[] count = {0};
        validate(items, 1, count);
    }

    private static void validate(List<Item> items, int depth, int[] count) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("a menu may nest at most " + MAX_DEPTH + " levels");
        }
        for (Item item : items) {
            if (++count[0] > MAX_ITEMS) {
                throw new IllegalArgumentException("a menu may have at most " + MAX_ITEMS + " entries");
            }
            if (item instanceof Submenu submenu) {
                validate(submenu.items(), depth + 1, count);
            }
        }
    }

    /** One entry of a menu. */
    public sealed interface Item permits Action, Check, Submenu, Separator {}

    /** An entry that does something when chosen. */
    public record Action(String label, boolean enabled, Shortcut accelerator, Runnable onSelect) implements Item {
        public Action {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(onSelect, "onSelect");
        }

        public Action withEnabled(boolean enabled) {
            return new Action(label, enabled, accelerator, onSelect);
        }

        public Action withAccelerator(Shortcut accelerator) {
            return new Action(label, enabled, accelerator, onSelect);
        }
    }

    /**
     * An entry with a check mark. {@code onToggle} receives the state the user asked for (the opposite of {@code checked}); the mark
     * changes at once, and a new menu with the state you keep replaces it when you set one.
     */
    public record Check(String label, boolean checked, boolean enabled, Shortcut accelerator, Consumer<Boolean> onToggle) implements Item {
        public Check {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(onToggle, "onToggle");
        }

        public Check withEnabled(boolean enabled) {
            return new Check(label, checked, enabled, accelerator, onToggle);
        }

        public Check withAccelerator(Shortcut accelerator) {
            return new Check(label, checked, enabled, accelerator, onToggle);
        }
    }

    /** An entry that opens a nested menu. */
    public record Submenu(String label, boolean enabled, List<Item> items) implements Item {
        public Submenu {
            Objects.requireNonNull(label, "label");
            items = List.copyOf(items);
        }

        public Submenu withEnabled(boolean enabled) {
            return new Submenu(label, enabled, items);
        }
    }

    /** A dividing line. */
    public record Separator() implements Item {}

    public static Menu of(Item... items) {
        return new Menu(List.of(items));
    }

    /** An entry that runs {@code onSelect}. */
    public static Action action(String label, Runnable onSelect) {
        return new Action(label, true, null, onSelect);
    }

    /** An entry with an accelerator, written as for {@link Shortcut#parse}. */
    public static Action action(String label, String accelerator, Runnable onSelect) {
        return new Action(label, true, Shortcut.parse(accelerator), onSelect);
    }

    public static Action action(String label, Shortcut accelerator, Runnable onSelect) {
        return new Action(label, true, accelerator, onSelect);
    }

    /** An entry that is shown grayed out and does nothing. */
    public static Action disabled(String label) {
        return new Action(label, false, null, () -> {});
    }

    public static Check check(String label, boolean checked, Consumer<Boolean> onToggle) {
        return new Check(label, checked, true, null, onToggle);
    }

    public static Check check(String label, boolean checked, String accelerator, Consumer<Boolean> onToggle) {
        return new Check(label, checked, true, Shortcut.parse(accelerator), onToggle);
    }

    public static Submenu submenu(String label, Item... items) {
        return new Submenu(label, true, List.of(items));
    }

    public static Separator separator() {
        return new Separator();
    }
}
