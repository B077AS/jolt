package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.BinaryChannel;
import io.github.b077as.jolt.Dialogs;
import io.github.b077as.jolt.FileDialogOptions;
import io.github.b077as.jolt.FileFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The saveAs protocol, spoken to by hand: no window, a scripted dialog and an in-memory channel. */
class PageSaveAsTest {

    private static final int SAVE = 1, DATA = 2, END = 3, ABORT = 4, CHOSEN = 11, CANCELLED = 12, DONE = 13, ERROR = 14;

    private Path folder;
    private FakeChannel channel;
    private FakeDialogs dialogs;
    private PageSaveAs saveAs;

    private static final class FakeChannel implements BinaryChannel {
        final List<ByteBuffer> sent = new ArrayList<>();
        Consumer<ByteBuffer> onMessage;
        Runnable onClose;

        @Override public String name() { return PageSaveAs.CHANNEL; }
        @Override public boolean isOpen() { return true; }
        @Override public boolean send(ByteBuffer data) {
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data.duplicate()).flip();
            sent.add(copy);
            return true;
        }
        @Override public void onMessage(Consumer<ByteBuffer> listener) { onMessage = listener; }
        @Override public void onOpen(Runnable listener) { }
        @Override public void onClose(Runnable listener) { onClose = listener; }
        @Override public void close() { }

        void deliver(byte[] message) {
            onMessage.accept(ByteBuffer.wrap(message));
        }
    }

    private static final class FakeDialogs implements Dialogs {
        Optional<Path> answer = Optional.empty();
        FileDialogOptions asked;

        @Override public CompletableFuture<Optional<Path>> openFile(FileDialogOptions options) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<List<Path>> openFiles(FileDialogOptions options) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<Optional<Path>> chooseFolder(FileDialogOptions options) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<Optional<Path>> saveFile(FileDialogOptions options) {
            asked = options;
            return CompletableFuture.completedFuture(answer);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        folder = Files.createTempDirectory("jolt-saveas-");
        channel = new FakeChannel();
        dialogs = new FakeDialogs();
        saveAs = new PageSaveAs(channel, () -> dialogs, folder);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var files = Files.list(folder)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
        Files.deleteIfExists(folder);
    }

    // ---- building and reading messages ----

    private static byte[] str(String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + utf8.length).putInt(utf8.length).put(utf8).array();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] head(int type, int id) {
        return ByteBuffer.allocate(5).put((byte) type).putInt(id).array();
    }

    private static byte[] saveRequest(int id, String title, String name, String[][] filters) {
        byte[] request = concat(head(SAVE, id), str(title), str(name), ByteBuffer.allocate(4).putInt(filters.length).array());
        for (String[] filter : filters) {
            request = concat(request, str(filter[0]), ByteBuffer.allocate(4).putInt(filter.length - 1).array());
            for (int i = 1; i < filter.length; i++) {
                request = concat(request, str(filter[i]));
            }
        }
        return request;
    }

    private ByteBuffer lastReply() {
        assertFalse(channel.sent.isEmpty(), "the app sent nothing");
        return channel.sent.get(channel.sent.size() - 1).duplicate();
    }

    private static String readString(ByteBuffer m) {
        byte[] bytes = new byte[m.getInt()];
        m.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private long partialFiles() throws IOException {
        try (var files = Files.list(folder)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".part")).count();
        }
    }

    // ---- tests ----

    @Test
    void theDialogGetsWhatThePageAskedForAndTheConfiguredFolder() {
        dialogs.answer = Optional.empty();
        channel.deliver(saveRequest(1, "Export", "report.pdf", new String[][] {{"PDF", "pdf"}, {"Images", "png", "jpg"}}));
        FileDialogOptions o = dialogs.asked;
        assertEquals("Export", o.title());
        assertEquals("report.pdf", o.fileName());
        assertEquals(folder, o.initialDirectory());
        assertEquals(List.of(FileFilter.of("PDF", "pdf"), FileFilter.of("Images", "png", "jpg")), o.filters());
    }

    @Test
    void emptyTitleAndNameMeanTheSystemDefaults() {
        channel.deliver(saveRequest(1, "", "", new String[0][]));
        assertEquals(null, dialogs.asked.title());
        assertEquals(null, dialogs.asked.fileName());
    }

    @Test
    void cancellingTheDialogTellsThePageAndWritesNothing() throws IOException {
        dialogs.answer = Optional.empty();
        channel.deliver(saveRequest(7, "", "x.txt", new String[0][]));
        ByteBuffer reply = lastReply();
        assertEquals(CANCELLED, reply.get() & 0xFF);
        assertEquals(7, reply.getInt());
        assertEquals(0, partialFiles());
    }

    @Test
    void theBytesArriveInPiecesAndReplaceTheFileOnlyAtTheEnd() throws IOException {
        Path target = folder.resolve("out.bin");
        Files.writeString(target, "old contents");
        dialogs.answer = Optional.of(target);
        channel.deliver(saveRequest(3, "", "out.bin", new String[0][]));
        ByteBuffer chosen = lastReply();
        assertEquals(CHOSEN, chosen.get() & 0xFF);
        assertEquals(3, chosen.getInt());
        assertEquals("out.bin", readString(chosen));

        channel.deliver(concat(head(DATA, 3), new byte[] {1, 2, 3}));
        channel.deliver(concat(head(DATA, 3), new byte[] {4, 5}));
        assertEquals("old contents", Files.readString(target), "the old file stays until the transfer is complete");
        assertEquals(1, partialFiles());

        channel.deliver(head(END, 3));
        ByteBuffer done = lastReply();
        assertEquals(DONE, done.get() & 0xFF);
        assertEquals(3, done.getInt());
        assertEquals(5L, done.getLong());
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, Files.readAllBytes(target));
        assertEquals(0, partialFiles());
    }

    @Test
    void anEmptyFileIsAFile() throws IOException {
        Path target = folder.resolve("empty.txt");
        dialogs.answer = Optional.of(target);
        channel.deliver(saveRequest(1, "", "", new String[0][]));
        channel.deliver(head(END, 1));
        assertEquals(DONE, lastReply().get() & 0xFF);
        assertEquals(0, Files.size(target));
    }

    @Test
    void abortingThrowsTheHalfWrittenFileAway() throws IOException {
        Path target = folder.resolve("never.bin");
        dialogs.answer = Optional.of(target);
        channel.deliver(saveRequest(1, "", "", new String[0][]));
        channel.deliver(concat(head(DATA, 1), new byte[] {9, 9, 9}));
        assertEquals(1, partialFiles());
        channel.deliver(head(ABORT, 1));
        assertEquals(0, partialFiles());
        assertFalse(Files.exists(target));
    }

    @Test
    void losingThePageThrowsEveryUnfinishedFileAway() throws IOException {
        dialogs.answer = Optional.of(folder.resolve("a.bin"));
        channel.deliver(saveRequest(1, "", "", new String[0][]));
        channel.deliver(saveRequest(2, "", "", new String[0][]));
        channel.deliver(concat(head(DATA, 1), new byte[] {1}));
        assertTrue(partialFiles() >= 1);
        channel.onClose.run();
        assertEquals(0, partialFiles());
    }

    @Test
    void transfersAreKeptApartById() throws IOException {
        Path first = folder.resolve("one.bin");
        Path second = folder.resolve("two.bin");
        dialogs.answer = Optional.of(first);
        channel.deliver(saveRequest(1, "", "", new String[0][]));
        dialogs.answer = Optional.of(second);
        channel.deliver(saveRequest(2, "", "", new String[0][]));
        channel.deliver(concat(head(DATA, 1), new byte[] {1}));
        channel.deliver(concat(head(DATA, 2), new byte[] {2}));
        channel.deliver(head(END, 2));
        channel.deliver(head(END, 1));
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(first));
        assertArrayEquals(new byte[] {2}, Files.readAllBytes(second));
    }

    @Test
    void aBadFilterIsAnErrorForThePageNotAnAbsurdDialog() {
        channel.deliver(saveRequest(5, "", "", new String[][] {{"Bad", "*.exe"}}));
        ByteBuffer reply = lastReply();
        assertEquals(ERROR, reply.get() & 0xFF);
        assertEquals(5, reply.getInt());
        assertTrue(readString(reply).contains("invalid saveAs options"));
        assertEquals(null, dialogs.asked, "no dialog was shown");
    }

    @Test
    void aNameWithAFolderInItIsRefused() {
        channel.deliver(saveRequest(5, "", "../../etc/passwd", new String[0][]));
        assertEquals(ERROR, lastReply().get() & 0xFF);
    }

    @Test
    void garbageIsIgnoredNotFatal() {
        channel.deliver(new byte[] {1});
        channel.deliver(new byte[] {99, 0, 0, 0, 1});
        channel.deliver(concat(head(DATA, 12345), new byte[] {1, 2}));
        channel.deliver(concat(head(SAVE, 1), new byte[] {0, 0, 0, 9, 1}));
        assertEquals(ERROR, lastReply().get() & 0xFF); // the truncated request is answered, the rest is dropped
    }
}
