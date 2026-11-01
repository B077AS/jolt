package io.github.b077as.jolt.linux;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain file handling: runs anywhere, no display needed. */
class DesktopEntryTest {

    /** Just enough of a PNG for the size to be read: signature, IHDR length and type, width, height. */
    private static byte[] png(int size, int shade) {
        ByteBuffer b = ByteBuffer.allocate(32);
        b.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}).putInt(13).put("IHDR".getBytes()).putInt(size).putInt(size);
        b.put(7, (byte) shade);
        return b.array();
    }

    private static List<String> files(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString()).sorted().toList();
        }
    }

    @Test
    void withoutASystemEntryAHiddenOneThatStartsTheProgramIsWritten(@TempDir Path data) throws IOException {
        DesktopEntry entry = new DesktopEntry("com.example.app", "Example", data, List.of(), List.of("/opt/app/run", "--x y"));
        entry.install(png(256, 1));
        String text = Files.readString(data.resolve("applications/com.example.app.desktop"));
        assertTrue(text.contains("[Desktop Entry]\nX-Jolt-Generated=true\n"), text);
        assertTrue(text.contains("Exec=/opt/app/run \"--x y\"\n"), text);
        assertTrue(text.contains("NoDisplay=true\n") && text.contains("StartupWMClass=com.example.app\n"), text);
        String icon = text.lines().filter(l -> l.startsWith("Icon=")).findFirst().orElseThrow().substring(5);
        assertTrue(icon.matches("com\\.example\\.app-[0-9a-f]{12}"), icon);
        assertTrue(Files.isRegularFile(data.resolve("icons/hicolor/256x256/apps/" + icon + ".png")));
    }

    @Test
    void aSystemEntryIsCopiedWithOnlyTheIconChanged(@TempDir Path data, @TempDir Path system) throws IOException {
        Files.createDirectories(system.resolve("applications"));
        Files.writeString(system.resolve("applications/com.example.app.desktop"), """
                [Desktop Entry]
                Name=Example
                Exec=/opt/example/bin/Example
                Icon=/opt/example/lib/Example.png
                Categories=Network;

                [Desktop Action New]
                Name=New
                Icon=other
                """);
        new DesktopEntry("com.example.app", "Example", data, List.of(system), List.of("java")).install(png(48, 1));
        String text = Files.readString(data.resolve("applications/com.example.app.desktop"));
        assertTrue(text.contains("Exec=/opt/example/bin/Example\n") && text.contains("Categories=Network;\n"), text);
        assertFalse(text.contains("/opt/example/lib/Example.png"), text);
        assertFalse(text.contains("NoDisplay"), text);
        assertTrue(text.contains("[Desktop Action New]\nName=New\nIcon=other\n"), "other groups stay as they are: " + text);
        assertEquals(1, text.lines().filter(l -> l.startsWith("Icon=com.example.app-")).count(), text);
    }

    @Test
    void aNewPictureReplacesTheOldOneAndTheSameOneIsNotWrittenTwice(@TempDir Path data) throws IOException {
        DesktopEntry entry = new DesktopEntry("com.example.app", "Example", data, List.of(), List.of("/bin/app"));
        entry.install(png(64, 1));
        List<String> first = files(data);
        assertEquals(2, first.size(), first.toString());
        entry.install(png(64, 1));
        assertEquals(first, files(data));
        entry.install(png(64, 2));
        List<String> second = files(data);
        assertEquals(2, second.size(), "the old picture is removed: " + second);
        assertFalse(second.equals(first));
    }

    @Test
    void anEntryThatJoltDidNotWriteIsLeftAlone(@TempDir Path data) throws IOException {
        Files.createDirectories(data.resolve("applications"));
        Files.writeString(data.resolve("applications/com.example.app.desktop"), "[Desktop Entry]\nName=Mine\nIcon=mine\n");
        new DesktopEntry("com.example.app", "Example", data, List.of(), List.of("/bin/app")).install(png(64, 1));
        assertEquals("[Desktop Entry]\nName=Mine\nIcon=mine\n", Files.readString(data.resolve("applications/com.example.app.desktop")));
        assertEquals(1, files(data).size());
    }

    @Test
    void junkIsIgnored(@TempDir Path data) throws IOException {
        new DesktopEntry("com.example.app", "Example", data, List.of(), List.of("/bin/app")).install(new byte[] {1, 2, 3});
        assertEquals(List.of(), files(data));
    }
}
