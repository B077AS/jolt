package io.github.b077as.jolt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortcutTest {

    @Test
    void parsesModifiersAndKeyInAnyCase() {
        Shortcut shortcut = Shortcut.parse("ctrl+SHIFT+n");
        assertEquals(new Shortcut("N", true, true, false, false), shortcut);
        assertEquals("Ctrl+Shift+N", shortcut.toString());
    }

    @Test
    void writesModifiersInAFixedOrder() {
        assertEquals("Ctrl+Alt+Shift+Meta+F5", Shortcut.parse("Meta+Shift+Alt+Ctrl+F5").toString());
    }

    @Test
    void knowsTheAlternativeSpellings() {
        assertEquals(Shortcut.parse("Ctrl+Alt+Delete"), Shortcut.parse("CmdOrCtrl+Option+Del"));
        assertTrue(Shortcut.parse("Win+E").meta());
        assertTrue(Shortcut.parse("Super+E").meta());
        assertEquals("Escape", Shortcut.parse("Esc").key());
        assertEquals("PageDown", Shortcut.parse("Ctrl+pgdn").key());
        assertEquals("Up", Shortcut.parse("Alt+ArrowUp").key());
    }

    @Test
    void acceptsPunctuationAsACharacterOrByName() {
        assertEquals("Comma", Shortcut.parse("Ctrl+,").key());
        assertEquals("Minus", Shortcut.parse("Ctrl+-").key());
        assertEquals("Equal", Shortcut.parse("Ctrl+=").key());
        assertEquals("Backquote", Shortcut.parse("Ctrl+`").key());
        assertEquals("Backslash", Shortcut.parse("Ctrl+\\").key());
        assertEquals("LeftBracket", Shortcut.parse("Ctrl+leftbracket").key());
        assertEquals("Comma", Shortcut.parse("Ctrl+Comma").key());
    }

    @Test
    void acceptsFunctionKeysUpToF24() {
        assertEquals("F1", Shortcut.parse("f1").key());
        assertEquals("F24", Shortcut.parse("F24").key());
        assertThrows(IllegalArgumentException.class, () -> Shortcut.parse("F25"));
        assertThrows(IllegalArgumentException.class, () -> Shortcut.parse("F0"));
        assertThrows(IllegalArgumentException.class, () -> Shortcut.parse("F01"));
        assertThrows(IllegalArgumentException.class, () -> Shortcut.parse("F+1"));
    }

    @Test
    void rejectsWhatIsNotAShortcut() {
        for (String bad : new String[] {"", "  ", "Ctrl", "Ctrl+", "+N", "Ctrl++", "Ctrl+Shift", "Ctrl+N+M", "Ctrl+Ctrl+N", "Hyper+N",
                "Ctrl+Nope", "Ctrl+Plus", "N+Ctrl", "Ctrl+Shift+Alt+Meta"}) {
            assertThrows(IllegalArgumentException.class, () -> Shortcut.parse(bad), bad);
        }
    }

    @Test
    void theCanonicalConstructorNormalisesTheKey() {
        assertEquals("S", new Shortcut("s", true, false, false, false).key());
        assertThrows(IllegalArgumentException.class, () -> new Shortcut("???", true, false, false, false));
        assertThrows(NullPointerException.class, () -> new Shortcut(null, true, false, false, false));
    }

    @Test
    void ofBuildsFromModifiers() {
        assertEquals(Shortcut.parse("Ctrl+Shift+S"), Shortcut.of("S", Shortcut.Modifier.SHIFT, Shortcut.Modifier.CTRL));
        assertEquals(Shortcut.parse("F5"), Shortcut.of("F5"));
    }

    @Test
    void aGlobalHotkeyNeedsCtrlAltMetaOrAFunctionKey() {
        assertTrue(Shortcut.parse("Ctrl+Alt+M").isGlobalHotkeyCandidate());
        assertTrue(Shortcut.parse("Alt+Space").isGlobalHotkeyCandidate());
        assertTrue(Shortcut.parse("Win+Shift+S").isGlobalHotkeyCandidate());
        assertTrue(Shortcut.parse("F9").isGlobalHotkeyCandidate());
        assertTrue(Shortcut.parse("Shift+F9").isGlobalHotkeyCandidate());
        assertFalse(Shortcut.parse("M").isGlobalHotkeyCandidate());
        assertFalse(Shortcut.parse("Shift+M").isGlobalHotkeyCandidate());
        assertFalse(Shortcut.parse("Space").isGlobalHotkeyCandidate());
        assertFalse(Shortcut.parse("Enter").isGlobalHotkeyCandidate());
    }
}
