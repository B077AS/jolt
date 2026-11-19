package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.Shortcut;

import java.util.HashMap;
import java.util.Map;

/** Maps the keys of a {@link Shortcut} to Win32 virtual-key codes and back. */
final class WindowsKeys {

    static final int VK_SHIFT = 0x10, VK_CONTROL = 0x11, VK_MENU = 0x12, VK_LWIN = 0x5B, VK_RWIN = 0x5C;

    private static final Map<String, Integer> BY_NAME = new HashMap<>();
    private static final Map<Integer, String> BY_CODE = new HashMap<>();

    static {
        for (char c = 'A'; c <= 'Z'; c++) {
            put(String.valueOf(c), c);
        }
        for (char c = '0'; c <= '9'; c++) {
            put(String.valueOf(c), c);
        }
        for (int n = 1; n <= 24; n++) {
            put("F" + n, 0x70 + n - 1);
        }
        put("Backspace", 0x08);
        put("Tab", 0x09);
        put("Enter", 0x0D);
        put("Escape", 0x1B);
        put("Space", 0x20);
        put("PageUp", 0x21);
        put("PageDown", 0x22);
        put("End", 0x23);
        put("Home", 0x24);
        put("Left", 0x25);
        put("Up", 0x26);
        put("Right", 0x27);
        put("Down", 0x28);
        put("Insert", 0x2D);
        put("Delete", 0x2E);
        put("Semicolon", 0xBA);   // VK_OEM_1
        put("Equal", 0xBB);       // VK_OEM_PLUS
        put("Comma", 0xBC);       // VK_OEM_COMMA
        put("Minus", 0xBD);       // VK_OEM_MINUS
        put("Period", 0xBE);      // VK_OEM_PERIOD
        put("Slash", 0xBF);       // VK_OEM_2
        put("Backquote", 0xC0);   // VK_OEM_3
        put("LeftBracket", 0xDB); // VK_OEM_4
        put("Backslash", 0xDC);   // VK_OEM_5
        put("RightBracket", 0xDD);// VK_OEM_6
        put("Quote", 0xDE);       // VK_OEM_7
    }

    private WindowsKeys() {}

    private static void put(String name, int code) {
        BY_NAME.put(name, code);
        BY_CODE.put(code, name);
    }

    /** The virtual-key code of the shortcut's key. */
    static int virtualKey(Shortcut shortcut) {
        Integer code = BY_NAME.get(shortcut.key());
        if (code == null) {
            throw new IllegalArgumentException("no Windows key for " + shortcut.key());
        }
        return code;
    }

    /** The shortcut a key press with the modifiers currently held makes, or {@code null} for a key a shortcut cannot name. */
    static Shortcut shortcutFor(int virtualKey, boolean ctrl, boolean shift, boolean alt, boolean meta) {
        String key = BY_CODE.get(virtualKey);
        return key == null ? null : new Shortcut(key, ctrl, shift, alt, meta);
    }

    /** {@code MOD_ALT}, {@code MOD_CONTROL}, {@code MOD_SHIFT} and {@code MOD_WIN} of {@code RegisterHotKey}. */
    static int hotkeyModifiers(Shortcut shortcut) {
        return (shortcut.alt() ? 0x1 : 0) | (shortcut.ctrl() ? 0x2 : 0) | (shortcut.shift() ? 0x4 : 0) | (shortcut.meta() ? 0x8 : 0);
    }
}
