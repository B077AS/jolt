package io.github.b077as.jolt.examples.notes;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The application's data, kept in a JSON file. This is the kind of thing the Java side exists for: a web page cannot
 * read or write files, a Java backend can.
 */
final class NotesStore {

    record Note(long id, String text, long createdAt) {}

    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type LIST_TYPE = new TypeToken<List<Note>>() {}.getType();

    private final Path file;
    private final List<Note> notes;
    private long nextId;

    NotesStore(Path file) {
        this.file = file;
        this.notes = load(file);
        this.nextId = notes.stream().mapToLong(Note::id).max().orElse(0) + 1;
    }

    synchronized List<Note> list() {
        return List.copyOf(notes);
    }

    synchronized Note add(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("A note cannot be empty");
        }
        Note note = new Note(nextId++, text.strip(), Instant.now().toEpochMilli());
        notes.add(note);
        save();
        return note;
    }

    synchronized boolean delete(long id) {
        boolean removed = notes.removeIf(n -> n.id() == id);
        if (removed) {
            save();
        }
        return removed;
    }

    private void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            // write then rename, so a crash never leaves a half-written file
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(notes, LIST_TYPE), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save notes to " + file, e);
        }
    }

    private static List<Note> load(Path file) {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            List<Note> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), LIST_TYPE);
            return loaded == null ? new ArrayList<>() : new ArrayList<>(loaded);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read notes from " + file, e);
        }
    }
}
