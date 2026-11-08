package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.FileDrop;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileDropProtocolTest {

    private static final Path DIR = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();

    private static String message(String zoneJson, String x, String y, String... names) {
        return FileDropProtocol.PREFIX + "{\"zone\":" + zoneJson + ",\"x\":" + x + ",\"y\":" + y
                + ",\"names\":\"" + String.join("\\u0000", names) + "\"}";
    }

    private static List<Path> files(String... names) {
        List<Path> list = new ArrayList<>();
        for (String name : names) {
            list.add(DIR.resolve(name));
        }
        return list;
    }

    @Test
    void aDropWhosePathsMatchTheNamesTheyWereAnnouncedWithIsAccepted() {
        FileDrop drop = FileDropProtocol.accept(message("\"avatar\"", "120", "45", "a.png", "b.txt"), files("a.png", "b.txt"));
        assertNotNull(drop);
        assertEquals(files("a.png", "b.txt"), drop.files());
        assertEquals("avatar", drop.zone());
        assertEquals(120, drop.x());
        assertEquals(45, drop.y());
    }

    @Test
    void theOrderOfTheNamesDoesNotMatter() {
        assertNotNull(FileDropProtocol.accept(message("null", "0", "0", "b.txt", "a.png"), files("a.png", "b.txt")));
    }

    @Test
    void aDropOutsideEveryZoneHasNoZone() {
        assertNull(FileDropProtocol.accept(message("null", "1", "2", "a.png"), files("a.png")).zone());
    }

    @Test
    void aPageCannotNameAFileThePlatformDidNotReport() {
        // the page says it dropped passwords.txt, the platform dropped a.png
        assertNull(FileDropProtocol.accept(message("null", "0", "0", "passwords.txt"), files("a.png")));
        // more or fewer names than paths
        assertNull(FileDropProtocol.accept(message("null", "0", "0", "a.png", "b.txt"), files("a.png")));
        assertNull(FileDropProtocol.accept(message("null", "0", "0", "a.png"), files("a.png", "b.txt")));
        // the same name twice does not stand for two different files
        assertNull(FileDropProtocol.accept(message("null", "0", "0", "a.png", "a.png"), files("a.png", "b.png")));
    }

    @Test
    void aMessageWithoutPlatformPathsIsIgnored() {
        assertNull(FileDropProtocol.accept(message("null", "0", "0", "a.png"), List.of()));
    }

    @Test
    void relativeNetworkAndDevicePathsAreRefused() {
        assertNull(FileDropProtocol.accept(message("null", "0", "0", "a.png"), List.of(Path.of("a.png"))));
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            assertNull(FileDropProtocol.accept(message("null", "0", "0", "a.png"), List.of(Path.of("\\\\server\\share\\a.png"))));
            // (Java's Path drops a "\\?\" prefix on parsing, so "\\?\C:\a.png" is already the plain local path C:\a.png)
        }
    }

    @Test
    void aRootHasNoNameAndIsRefused() {
        Path root = DIR.getRoot();
        assertNull(FileDropProtocol.accept(message("null", "0", "0", ""), List.of(root)));
    }

    @Test
    void tooManyFilesAreRefusedNotCut() {
        int n = ClipboardData.MAX_FILES + 1;
        List<Path> many = new ArrayList<>();
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            names[i] = "f" + i;
            many.add(DIR.resolve(names[i]));
        }
        assertNull(FileDropProtocol.accept(message("null", "0", "0", names), many));
    }

    @Test
    void namesThatDifferOnlyInUnicodeCompositionStillMatch() {
        String composed = "caf\u00e9.txt";
        String decomposed = "cafe\u0301.txt";
        assertNotNull(FileDropProtocol.accept(message("null", "0", "0", decomposed), files(composed)));
    }

    @Test
    void aZoneIsKeptOnlyIfItIsAShortPrintableName() {
        assertEquals("a b", FileDropProtocol.accept(message("\"a b\"", "0", "0", "a"), files("a")).zone());
        assertNull(FileDropProtocol.accept(message("\"\"", "0", "0", "a"), files("a")).zone());
        assertNull(FileDropProtocol.accept(message("\"" + "z".repeat(FileDropProtocol.MAX_ZONE + 1) + "\"", "0", "0", "a"), files("a")).zone());
        assertNull(FileDropProtocol.accept(message("\"a\\u0007b\"", "0", "0", "a"), files("a")).zone());
        assertEquals("z".repeat(FileDropProtocol.MAX_ZONE),
                FileDropProtocol.accept(message("\"" + "z".repeat(FileDropProtocol.MAX_ZONE) + "\"", "0", "0", "a"), files("a")).zone());
    }

    @Test
    void coordinatesThatAreNotSmallWholeNumbersBecomeZero() {
        FileDrop drop = FileDropProtocol.accept(message("null", "12.5", "99999999999", "a"), files("a"));
        assertEquals(0, drop.x());
        assertEquals(0, drop.y());
        drop = FileDropProtocol.accept(message("null", "-5", "1e3", "a"), files("a"));
        assertEquals(-5, drop.x());
        assertEquals(0, drop.y());
    }

    @Test
    void malformedMessagesAreIgnored() {
        List<Path> paths = files("a");
        assertNull(FileDropProtocol.accept("hello", paths));
        assertNull(FileDropProtocol.accept(FileDropProtocol.PREFIX, paths));
        assertNull(FileDropProtocol.accept(FileDropProtocol.PREFIX + "not json", paths));
        assertNull(FileDropProtocol.accept(FileDropProtocol.PREFIX + "{\"zone\":null}", paths));
        assertNull(FileDropProtocol.accept(FileDropProtocol.PREFIX + "{\"names\":42}", paths));
        assertNull(FileDropProtocol.accept(FileDropProtocol.PREFIX + "[\"a\"]", paths));
    }

    @Test
    void onlyTheOwnPrefixMarksADropMessage() {
        assertTrue(FileDropProtocol.isDropMessage(FileDropProtocol.PREFIX + "{}"));
        assertFalse(FileDropProtocol.isDropMessage("\u0001jolt-frame:minimize"));
        assertFalse(FileDropProtocol.isDropMessage("jolt-drop:"));
        assertFalse(FileDragProtocol.isDragMessage(FileDropProtocol.PREFIX + "{}"));
    }

    @Test
    void theDropDoesNotShowItsPathsInToString() {
        FileDrop drop = new FileDrop(files("secret-plans.txt"), "z", 1, 2);
        assertFalse(drop.toString().contains("secret"));
        assertTrue(drop.toString().contains("1 file"));
    }

    @Test
    void theScriptSpeaksTheProtocolTheWindowUnderstands() {
        String script = FileDropScript.build();
        for (String needed : new String[] {"\\u0001jolt-drop:", "postMessageWithAdditionalObjects", "data-jolt-drop-over",
                "data-jolt-drop-active", "[data-jolt-drop]", "isTrusted", "preventDefault", "'drop'", "'dragover'"}) {
            assertTrue(script.contains(needed), "missing: " + needed);
        }
        assertFalse(script.contains("%s"));
        assertFalse(script.contains("\u0001"), "the prefix must be written as an escape, not as the raw control character");
    }
}
