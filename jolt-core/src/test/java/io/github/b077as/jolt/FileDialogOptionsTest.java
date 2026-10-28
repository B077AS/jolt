package io.github.b077as.jolt;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileDialogOptionsTest {

    @Test
    void everythingIsOptionalByDefault() {
        FileDialogOptions o = FileDialogOptions.defaults();
        assertNull(o.title());
        assertNull(o.initialDirectory());
        assertNull(o.fileName());
        assertEquals(List.of(), o.filters());
        assertEquals(0, o.width());
        assertEquals(0, o.height());
    }

    @Test
    void theWithMethodsKeepEveryOtherField() {
        FileDialogOptions o = FileDialogOptions.defaults().withTitle("T").withInitialDirectory(Path.of("a"))
                .withFileName("x.txt").withFilters(FileFilter.of("Text", "txt"));
        FileDialogOptions changed = o.withTitle("U");
        assertEquals("U", changed.title());
        assertEquals(Path.of("a"), changed.initialDirectory());
        assertEquals("x.txt", changed.fileName());
        assertEquals(List.of(FileFilter.of("Text", "txt")), changed.filters());
    }

    @Test
    void aFileNameIsANameNotAPath() {
        assertThrows(IllegalArgumentException.class, () -> FileDialogOptions.defaults().withFileName("dir/x.txt"));
        assertThrows(IllegalArgumentException.class, () -> FileDialogOptions.defaults().withFileName("dir\\x.txt"));
        assertThrows(IllegalArgumentException.class, () -> FileDialogOptions.defaults().withFileName(" "));
    }

    @Test
    void aSizeIsKeptByTheOtherWithMethodsAndMustBeSensible() {
        FileDialogOptions o = FileDialogOptions.defaults().withSize(900, 600).withTitle("T").withFilters(FileFilter.of("Text", "txt"));
        assertEquals(900, o.width());
        assertEquals(600, o.height());
        assertThrows(IllegalArgumentException.class, () -> FileDialogOptions.defaults().withSize(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> FileDialogOptions.defaults().withSize(0, FileDialogOptions.MAX_SIZE + 1));
    }

    @Test
    void aFilterNeedsPlainExtensions() {
        assertEquals(List.of("png", "jpg"), FileFilter.of("Images", "png", "jpg").extensions());
        assertThrows(IllegalArgumentException.class, () -> FileFilter.of("Images"));
        assertThrows(IllegalArgumentException.class, () -> FileFilter.of("Images", ".png"));
        assertThrows(IllegalArgumentException.class, () -> FileFilter.of("Images", "*.png"));
        assertThrows(IllegalArgumentException.class, () -> FileFilter.of("Images", "p;g"));
    }
}
