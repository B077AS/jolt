package io.github.b077as.jolt.linux;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The handler entry is a plain file (the xdg-mime call is replaced by a recorder), so this runs anywhere. */
class XdgUrlSchemesTest {

    @Test
    void registerWritesAHandlerEntryAndMakesItTheDefaultAndUnregisterRemovesIt(@TempDir Path dir) throws IOException {
        List<String> defaults = new ArrayList<>();
        XdgUrlSchemes schemes = new XdgUrlSchemes("com.example.myapp", dir.resolve("applications"),
                (file, mime) -> defaults.add(file + " " + mime));
        assertFalse(schemes.isRegistered("myapp"));
        schemes.register("myapp", "MyApp", List.of("/opt/MyApp/bin/MyApp", "--url"));
        assertTrue(schemes.isRegistered("myapp"));
        assertEquals("""
                [Desktop Entry]
                Type=Application
                Version=1.0
                Name=MyApp
                Exec=/opt/MyApp/bin/MyApp --url %u
                Terminal=false
                NoDisplay=true
                MimeType=x-scheme-handler/myapp;
                """, Files.readString(dir.resolve("applications/com.example.myapp-myapp.desktop")));
        assertEquals(List.of("com.example.myapp-myapp.desktop x-scheme-handler/myapp"), defaults);
        schemes.unregister("myapp");
        assertFalse(schemes.isRegistered("myapp"));
        schemes.unregister("myapp"); // nothing to remove is not an error
    }

    @Test
    void severalSchemesAndPathsWithSpacesWork(@TempDir Path dir) throws IOException {
        XdgUrlSchemes schemes = new XdgUrlSchemes("app", dir, (file, mime) -> {});
        schemes.register("one", "One", List.of("/home/me/My Apps/one"));
        schemes.register("two", "Two", List.of("/bin/true"));
        assertTrue(Files.readString(dir.resolve("app-one.desktop")).contains("Exec=\"/home/me/My Apps/one\" %u\n"));
        assertTrue(schemes.isRegistered("one") && schemes.isRegistered("two"));
        schemes.unregister("one");
        assertTrue(schemes.isRegistered("two"));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "no temporary file may be left behind");
        }
    }

    @Test
    void badInputIsRefusedBeforeAnythingIsWritten(@TempDir Path dir) throws IOException {
        XdgUrlSchemes schemes = new XdgUrlSchemes("app", dir, (file, mime) -> {});
        assertThrows(IllegalArgumentException.class, () -> schemes.register("http", "x", List.of("/bin/true")));
        assertThrows(IllegalArgumentException.class, () -> schemes.register("Bad Name", "x", List.of("/bin/true")));
        assertThrows(IllegalArgumentException.class, () -> schemes.register("ok", "x", List.of()));
        assertThrows(IllegalArgumentException.class, () -> schemes.register("ok", "x", List.of("/bin/true", "a\nExec=evil")));
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void aNewlineInTheDisplayNameCannotAddKeysToTheFile(@TempDir Path dir) throws IOException {
        XdgUrlSchemes schemes = new XdgUrlSchemes("../evil", dir, (file, mime) -> {});
        schemes.register("ok", "Name\nExec=/bin/evil", List.of("/bin/true"));
        try (var files = Files.list(dir)) {
            List<Path> written = files.toList();
            assertEquals(1, written.size());
            assertEquals(dir, written.get(0).getParent());
            assertEquals(1, Files.readAllLines(written.get(0)).stream().filter(l -> l.startsWith("Exec=")).count());
        }
    }
}
