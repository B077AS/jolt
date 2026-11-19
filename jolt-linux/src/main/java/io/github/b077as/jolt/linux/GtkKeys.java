package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.Shortcut;

import java.util.Map;

/** Maps the keys of a {@link Shortcut} to GDK key values ({@code GDK_KEY_*}). */
final class GtkKeys {

    private static final Map<String, Integer> NAMED = Map.ofEntries(
            Map.entry("Backspace", 0xff08), Map.entry("Tab", 0xff09), Map.entry("Enter", 0xff0d), Map.entry("Escape", 0xff1b),
            Map.entry("Space", 0x20), Map.entry("Home", 0xff50), Map.entry("Left", 0xff51), Map.entry("Up", 0xff52),
            Map.entry("Right", 0xff53), Map.entry("Down", 0xff54), Map.entry("PageUp", 0xff55), Map.entry("PageDown", 0xff56),
            Map.entry("End", 0xff57), Map.entry("Insert", 0xff63), Map.entry("Delete", 0xffff),
            Map.entry("Comma", 0x2c), Map.entry("Minus", 0x2d), Map.entry("Period", 0x2e), Map.entry("Slash", 0x2f),
            Map.entry("Semicolon", 0x3b), Map.entry("Equal", 0x3d), Map.entry("LeftBracket", 0x5b), Map.entry("Backslash", 0x5c),
            Map.entry("RightBracket", 0x5d), Map.entry("Backquote", 0x60), Map.entry("Quote", 0x27));

    private GtkKeys() {}

    /** The GDK key value of the shortcut's key: lower case for letters, as GTK wants for accelerators. */
    static int keyval(Shortcut shortcut) {
        String key = shortcut.key();
        if (key.length() == 1) {
            char c = key.charAt(0);
            return c >= 'A' && c <= 'Z' ? c - 'A' + 'a' : c; // digits are their own value
        }
        if (shortcut.isFunctionKey()) {
            return 0xffbe + Integer.parseInt(key.substring(1)) - 1; // GDK_KEY_F1
        }
        Integer value = NAMED.get(key);
        if (value == null) {
            throw new IllegalArgumentException("no GDK key for " + key);
        }
        return value;
    }
}
