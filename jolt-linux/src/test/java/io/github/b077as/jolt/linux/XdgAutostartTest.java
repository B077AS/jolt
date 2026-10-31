package io.github.b077as.jolt.linux;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The autostart entry is a plain file, so this runs anywhere, with or without a display. */
class XdgAutostartTest {

    @Test
    void enableWritesADesktopEntryThatDisableRemoves(@TempDir Path dir) throws IOException {
        XdgAutostart autostart = new XdgAutostart("com.example.parsel", dir.resolve("autostart"));
        assertFalse(autostart.isEnabled());
        autostart.enable(List.of("/opt/Parsel/bin/Parsel", "--minimized"));
        assertTrue(autostart.isEnabled());
        String entry = Files.readString(dir.resolve("autostart/com.example.parsel.desktop"));
        assertEquals("""
                [Desktop Entry]
                Type=Application
                Version=1.0
                Name=com.example.parsel
                Exec=/opt/Parsel/bin/Parsel --minimized
                Terminal=false
                X-GNOME-Autostart-enabled=true
                """, entry);
        autostart.disable();
        assertFalse(autostart.isEnabled());
        autostart.disable(); // nothing to remove is not an error
    }

    @Test
    void enableReplacesTheEntryAndQuotesArgumentsThatNeedIt(@TempDir Path dir) throws IOException {
        XdgAutostart autostart = new XdgAutostart("com.example.parsel", dir);
        autostart.enable(List.of("/opt/Parsel/bin/Parsel"));
        autostart.enable(List.of("/home/me/My Apps/parsel", "--profile=a b", "50%"));
        String entry = Files.readString(dir.resolve("com.example.parsel.desktop"));
        assertTrue(entry.contains("Exec=\"/home/me/My Apps/parsel\" \"--profile=a b\" 50%%\n"), entry);
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "no temporary file may be left behind");
        }
    }

    @Test
    void anApplicationIdCannotEscapeTheDirectoryOrBreakTheFile(@TempDir Path dir) throws IOException {
        XdgAutostart autostart = new XdgAutostart("../evil\nid", dir);
        autostart.enable(List.of("/bin/true"));
        try (var files = Files.list(dir)) {
            List<Path> written = files.toList();
            assertEquals(1, written.size());
            assertEquals(dir, written.get(0).getParent());
            assertFalse(written.get(0).getFileName().toString().contains("/"));
        }
        assertFalse(Files.readString(dir.resolve("__evil_id.desktop")).contains("\nid"));
    }

    @Test
    void anEmptyCommandOrALineBreakInAnArgumentIsRefused(@TempDir Path dir) {
        XdgAutostart autostart = new XdgAutostart("com.example.parsel", dir);
        assertThrows(IllegalArgumentException.class, () -> autostart.enable(List.of()));
        assertThrows(IllegalArgumentException.class, () -> autostart.enable(List.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> autostart.enable(List.of("/bin/true", "a\nExec=/bin/evil")));
    }
}
