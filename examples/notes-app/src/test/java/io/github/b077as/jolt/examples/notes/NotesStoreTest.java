package io.github.b077as.jolt.examples.notes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotesStoreTest {

    @TempDir
    Path dir;

    @Test
    void startsEmptyAndPersistsAcrossRestarts() {
        Path file = dir.resolve("data").resolve("notes.json");
        NotesStore first = new NotesStore(file);
        assertTrue(first.list().isEmpty());
        NotesStore.Note a = first.add("  first  ");
        first.add("second ✓ 😀");
        assertEquals("first", a.text(), "text is trimmed");

        NotesStore second = new NotesStore(file); // simulates restarting the app
        assertEquals(List.of("first", "second ✓ 😀"), second.list().stream().map(NotesStore.Note::text).toList());
    }

    @Test
    void idsAreNeverReusedAfterADelete() {
        NotesStore store = new NotesStore(dir.resolve("notes.json"));
        long one = store.add("one").id();
        long two = store.add("two").id();
        assertTrue(store.delete(two));
        assertFalse(store.delete(two), "deleting twice reports false");
        assertTrue(store.add("three").id() > two);
        assertEquals(1, store.list().stream().filter(n -> n.id() == one).count());
    }

    @Test
    void rejectsEmptyNotes() {
        NotesStore store = new NotesStore(dir.resolve("notes.json"));
        assertThrows(IllegalArgumentException.class, () -> store.add("   "));
        assertThrows(IllegalArgumentException.class, () -> store.add(null));
        assertTrue(store.list().isEmpty());
    }

    @Test
    void survivesConcurrentWrites() throws Exception {
        NotesStore store = new NotesStore(dir.resolve("notes.json"));
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            int worker = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 25; i++) {
                    store.add("w" + worker + "-" + i);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(200, store.list().size());
        assertEquals(200, store.list().stream().map(NotesStore.Note::id).distinct().count());
        assertEquals(200, new NotesStore(dir.resolve("notes.json")).list().size());
    }
}
