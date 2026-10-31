package io.github.b077as.jolt;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The menu of a {@link Tray} icon: a list of actions, checkboxes, separators and submenus. A menu is immutable; to change one, build
 * a new menu and give it to {@link Tray#setMenu}. Callbacks run on the UI thread.
 *
 * <pre>{@code
 * tray.setMenu(TrayMenu.of(
 *         TrayMenu.action("Open Parsel", main::focus),
 *         TrayMenu.separator(),
 *         TrayMenu.check("Close to tray", closeToTray, on -> setCloseToTray(on)),
 *         TrayMenu.check("Start with the computer", autostart, on -> setAutostart(on)),
 *         TrayMenu.separator(),
 *         TrayMenu.action("Quit", app::quit)));
 * }</pre>
 */
public record TrayMenu(List<Item> items) {

    public TrayMenu {
        items = List.copyOf(items);
    }

    /** One entry of a menu. */
    public sealed interface Item permits Action, Check, Submenu, Separator {}

    /** An entry that does something when chosen. */
    public record Action(String label, boolean enabled, Runnable onSelect) implements Item {
        public Action {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(onSelect, "onSelect");
        }
    }

    /** An entry with a check mark; {@code onToggle} receives the state the user asked for (the opposite of {@code checked}). */
    public record Check(String label, boolean checked, boolean enabled, Consumer<Boolean> onToggle) implements Item {
        public Check {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(onToggle, "onToggle");
        }
    }

    /** An entry that opens a nested menu. */
    public record Submenu(String label, boolean enabled, List<Item> items) implements Item {
        public Submenu {
            Objects.requireNonNull(label, "label");
            items = List.copyOf(items);
        }
    }

    /** A dividing line. */
    public record Separator() implements Item {}

    public static TrayMenu of(Item... items) {
        return new TrayMenu(List.of(items));
    }

    public static Item action(String label, Runnable onSelect) {
        return new Action(label, true, onSelect);
    }

    public static Item disabled(String label) {
        return new Action(label, false, () -> {});
    }

    public static Item check(String label, boolean checked, Consumer<Boolean> onToggle) {
        return new Check(label, checked, true, onToggle);
    }

    public static Item submenu(String label, Item... items) {
        return new Submenu(label, true, List.of(items));
    }

    public static Item separator() {
        return new Separator();
    }
}
