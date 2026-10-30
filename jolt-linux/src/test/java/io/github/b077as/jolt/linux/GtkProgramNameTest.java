package io.github.b077as.jolt.linux;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The program name is what Wayland uses as the window's app_id; the log filter must only drop the known messages. */
@EnabledOnOs(OS.LINUX)
class GtkProgramNameTest {

    @Test
    void knownGtkNoiseIsRecognised() {
        assertTrue(Gtk.isKnownNoise("GFileInfo created without standard::size"));
        assertTrue(Gtk.isKnownNoise("file ../../../gio/gfileinfo.c: line 1865 (g_file_info_get_size): should not be reached"));
    }

    @Test
    void otherMessagesAreNotHidden() {
        assertFalse(Gtk.isKnownNoise("Something else went wrong"));
        assertFalse(Gtk.isKnownNoise(null));
    }
}
