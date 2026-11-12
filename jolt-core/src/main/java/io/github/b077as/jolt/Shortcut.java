package io.github.b077as.jolt;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A key combination such as {@code Ctrl+Shift+N}: the accelerator of a {@link Menu} entry, or the key of a global
 * {@link Application#registerHotkey hotkey}.
 *
 * <p>{@link #parse} reads the usual written form. Modifiers are {@code Ctrl} (also {@code Control}, {@code CmdOrCtrl}),
 * {@code Shift}, {@code Alt} (also {@code Option}) and {@code Meta} (also {@code Cmd}, {@code Super}, {@code Win}: the Windows key, which
 * the system keeps for itself for most combinations). The key is a letter, a digit, {@code F1} to {@code F24}, one of
 * {@code Enter Space Tab Escape Backspace Delete Insert Home End PageUp PageDown Up Down Left Right}, or a punctuation key either as
 * the character ({@code , . - = / ; ' [ ] \ `}) or by name ({@code Comma Period Minus Equal Slash Semicolon Quote LeftBracket RightBracket
 * Backslash Backquote}). Names are not case sensitive. The combination does not depend on the keyboard layout beyond what the operating
 * system does for these keys.
 *
 * <pre>{@code
 * Shortcut.parse("Ctrl+Shift+N")
 * Shortcut.of("F5")
 * Shortcut.of("S", Shortcut.Modifier.CTRL)
 * }</pre>
 *
 * @param key   the canonical name of the key, see {@link #parse}
 * @param ctrl  Control
 * @param shift Shift
 * @param alt   Alt
 * @param meta  the Windows key (the Super key on Linux)
 */
public record Shortcut(String key, boolean ctrl, boolean shift, boolean alt, boolean meta) {

    /** A modifier key, for {@link #of}. */
    public enum Modifier { CTRL, SHIFT, ALT, META }

    private static final Set<String> NAMED_KEYS = Set.of("Enter", "Space", "Tab", "Escape", "Backspace", "Delete", "Insert", "Home", "End",
            "PageUp", "PageDown", "Up", "Down", "Left", "Right",
            "Comma", "Period", "Minus", "Equal", "Slash", "Semicolon", "Quote", "LeftBracket", "RightBracket", "Backslash", "Backquote");

    /** Alternative spellings of keys, keyed by lower case. */
    private static final Map<String, String> KEY_ALIASES = Map.ofEntries(
            Map.entry("return", "Enter"), Map.entry("esc", "Escape"), Map.entry("del", "Delete"), Map.entry("ins", "Insert"),
            Map.entry("pgup", "PageUp"), Map.entry("pageup", "PageUp"), Map.entry("pgdn", "PageDown"), Map.entry("pagedown", "PageDown"),
            Map.entry("spacebar", "Space"), Map.entry("arrowup", "Up"), Map.entry("arrowdown", "Down"), Map.entry("arrowleft", "Left"),
            Map.entry("arrowright", "Right"),
            Map.entry(",", "Comma"), Map.entry(".", "Period"), Map.entry("-", "Minus"), Map.entry("=", "Equal"), Map.entry("/", "Slash"),
            Map.entry(";", "Semicolon"), Map.entry("'", "Quote"), Map.entry("[", "LeftBracket"), Map.entry("]", "RightBracket"),
            Map.entry("\\", "Backslash"), Map.entry("`", "Backquote"));

    public Shortcut {
        Objects.requireNonNull(key, "key");
        String canonical = canonicalKey(key);
        if (canonical == null) {
            throw new IllegalArgumentException("not a key a shortcut can use: \"" + key + "\"");
        }
        key = canonical;
    }

    /** A shortcut for {@code key} with the given modifiers; {@code key} is spelled as in {@link #parse}. */
    public static Shortcut of(String key, Modifier... modifiers) {
        Set<Modifier> set = modifiers.length == 0 ? Set.of() : Set.of(modifiers);
        return new Shortcut(key, set.contains(Modifier.CTRL), set.contains(Modifier.SHIFT), set.contains(Modifier.ALT),
                set.contains(Modifier.META));
    }

    /**
     * Reads a combination written as {@code "Ctrl+Shift+N"}.
     *
     * @throws IllegalArgumentException if a part is not a known modifier, there is no key, or there is more than one
     */
    public static Shortcut parse(String text) {
        Objects.requireNonNull(text, "text");
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("an empty shortcut");
        }
        boolean ctrl = false, shift = false, alt = false, meta = false;
        String key = null;
        // The key is the last part; a '+' key would make the split ambiguous, and is not a key a shortcut can use anyway.
        String[] parts = trimmed.split("\\+", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].strip();
            if (part.isEmpty()) {
                throw new IllegalArgumentException("not a shortcut: \"" + text + "\"");
            }
            String lower = part.toLowerCase(Locale.ROOT);
            boolean last = i == parts.length - 1;
            switch (lower) {
                case "ctrl", "control", "cmdorctrl", "commandorcontrol" -> ctrl = requireModifier(ctrl, last, text);
                case "shift" -> shift = requireModifier(shift, last, text);
                case "alt", "option" -> alt = requireModifier(alt, last, text);
                case "meta", "cmd", "command", "super", "win", "windows" -> meta = requireModifier(meta, last, text);
                default -> {
                    if (!last || key != null) {
                        throw new IllegalArgumentException("not a shortcut: \"" + text + "\"");
                    }
                    key = part;
                }
            }
        }
        if (key == null) {
            throw new IllegalArgumentException("a shortcut needs a key besides its modifiers: \"" + text + "\"");
        }
        return new Shortcut(key, ctrl, shift, alt, meta);
    }

    private static boolean requireModifier(boolean already, boolean last, String text) {
        if (already || last) {
            throw new IllegalArgumentException(last ? "a shortcut needs a key besides its modifiers: \"" + text + "\""
                    : "a modifier is given twice in \"" + text + "\"");
        }
        return true;
    }

    /** The canonical name for {@code key}, or {@code null} if it is not a key a shortcut can use. */
    static String canonicalKey(String key) {
        String trimmed = key.strip();
        if (trimmed.length() == 1) {
            char c = trimmed.charAt(0);
            if (c >= 'a' && c <= 'z') {
                return String.valueOf((char) (c - 'a' + 'A'));
            }
            if (c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
                return trimmed;
            }
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String alias = KEY_ALIASES.get(lower);
        if (alias != null) {
            return alias;
        }
        if (lower.length() >= 2 && lower.length() <= 3 && lower.charAt(0) == 'f') {
            try {
                int number = Integer.parseInt(lower.substring(1));
                if (number >= 1 && number <= 24 && !lower.substring(1).startsWith("0") && !lower.substring(1).startsWith("+")) {
                    return "F" + number;
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        for (String named : NAMED_KEYS) {
            if (named.toLowerCase(Locale.ROOT).equals(lower)) {
                return named;
            }
        }
        return null;
    }

    /** Whether the key is a function key ({@code F1} to {@code F24}). */
    public boolean isFunctionKey() {
        return key.length() >= 2 && key.charAt(0) == 'F' && Character.isDigit(key.charAt(1));
    }

    /**
     * Whether this combination is fit to be a <em>global</em> hotkey: it has Ctrl, Alt or Meta, or it is a function key. A bare letter, or
     * Shift and a letter, would be taken away from every program the user types in.
     */
    public boolean isGlobalHotkeyCandidate() {
        return ctrl || alt || meta || isFunctionKey();
    }

    /** The written form, such as {@code "Ctrl+Shift+N"}: modifiers in the order Ctrl, Alt, Shift, Meta, then the key. */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        if (ctrl) {
            text.append("Ctrl+");
        }
        if (alt) {
            text.append("Alt+");
        }
        if (shift) {
            text.append("Shift+");
        }
        if (meta) {
            text.append("Meta+");
        }
        return text.append(key).toString();
    }
}
