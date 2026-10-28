package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.FileDialogOptions;
import io.github.b077as.jolt.FileFilter;
import io.github.b077as.jolt.WindowClosedException;
import io.github.b077as.jolt.WindowOptions;
import io.github.b077as.jolt.contract.WebWindowContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real GTK file dialogs. Nobody can click in them here, so each test waits until the dialog is up and then answers it from the
 * GTK thread with the "response" signal, which is what pressing its button does.
 */
@EnabledOnOs(OS.LINUX)
class LinuxDialogsTest {

    private static final FileDialogOptions NO_OPTIONS = FileDialogOptions.defaults();

    /** Answers the dialog (accept or cancel) as soon as it is on screen, from a thread of its own that hands it to the GTK thread. */
    private static void answerWhenShown(Application app, LinuxDialogs dialogs, boolean accept) {
        Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < 400 && !dialogs.isShowing(); i++) {
                    Thread.sleep(25);
                }
                Thread.sleep(200); // let the dialog finish laying itself out
                app.dispatch(() -> dialogs.respond(accept));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private interface Body<T> {
        CompletableFuture<T> apply(Application app, LinuxDialogs dialogs, LinuxWindow window);
    }

    /** Runs {@code body} with a window's dialogs; the body returns the future to wait for. Returns what it completed with. */
    private static <T> CompletableFuture<T> run(Body<T> body) {
        CompletableFuture<T> outcome = new CompletableFuture<>();
        WebWindowContract.runApp(40, app -> {
            LinuxWindow window = (LinuxWindow) app.createWindow(WindowOptions.defaults().withSize(900, 500));
            window.ready().thenRun(() -> body.apply(app, window.dialogs(), window).whenComplete((value, error) -> {
                if (error != null) {
                    outcome.completeExceptionally(error);
                } else {
                    outcome.complete(value);
                }
                window.close();
            }));
        });
        return outcome;
    }

    /** A page that runs {@code script} (inside an async function, with {@code report(text)} to answer) and the text it reported. */
    private static String pageReport(String script, FolderSetup folderSetup, java.util.function.BiConsumer<Application, LinuxWindow> interact) {
        CompletableFuture<String> reported = new CompletableFuture<>();
        WebWindowContract.runApp(40, app -> {
            LinuxWindow window = (LinuxWindow) app.createWindow(WindowOptions.defaults().withSize(900, 500));
            folderSetup.apply(window);
            window.onMessage(reported::complete);
            window.ready().thenRun(() -> {
                byte[] page = ("<script>const report = (t) => jolt.postMessage(String(t));"
                        + "(async () => {" + script + "})().catch(e => report('error: ' + e.message));</script>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                window.loadResources(path -> path.endsWith("index.html") ? page : null);
                interact.accept(app, window);
            });
            reported.whenComplete((v, e) -> window.close());
        });
        return reported.getNow(null);
    }

    private interface FolderSetup {
        void apply(LinuxWindow window);
    }

    @Test
    void aPageSavesBytesThroughTheDialogWithSaveAs() throws Exception {
        Path folder = Files.createTempDirectory("jolt-saveas-");
        try {
            String reported = pageReport(
                    "const bytes = new Uint8Array(3000000); for (let i = 0; i < bytes.length; i++) bytes[i] = i * 7;"
                            + "const r = await jolt.saveAs(bytes, { name: 'page.bin', filters: [{ name: 'Binary', extensions: ['bin'] }] });"
                            + "report(JSON.stringify(r));",
                    w -> w.enableSaveAs(folder),
                    (app, window) -> answerWhenShown(app, window.dialogs(), true));
            assertEquals("{\"name\":\"page.bin\",\"size\":3000000}", reported);
            byte[] expected = new byte[3_000_000];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = (byte) (i * 7);
            }
            assertArrayEquals(expected, Files.readAllBytes(folder.resolve("page.bin")));
            try (var files = Files.list(folder)) {
                assertEquals(1, files.count(), "no temporary file is left behind");
            }
        } finally {
            deleteAll(folder);
        }
    }

    @Test
    void aPageSavesTextAndBlobsAndGetsNullWhenTheUserCancels() throws Exception {
        Path folder = Files.createTempDirectory("jolt-saveas-");
        try {
            String reported = pageReport(
                    "const a = await jolt.saveAs('héllo', { name: 'text.txt' });"
                            + "const b = await jolt.saveAs(new Blob(['one ', 'two']), { name: 'blob.txt' });"
                            + "report(JSON.stringify([a, b]));",
                    w -> w.enableSaveAs(folder),
                    (app, window) -> answerEach(app, window, true, false));
            assertEquals("[{\"name\":\"text.txt\",\"size\":6},null]", reported);
            assertEquals("héllo", Files.readString(folder.resolve("text.txt")));
            assertFalse(Files.exists(folder.resolve("blob.txt")));
        } finally {
            deleteAll(folder);
        }
    }

    @Test
    void saveAsRejectsInsteadOfHangingWhenTheWindowDidNotEnableIt() {
        String reported = pageReport("await jolt.saveAs('x'); report('saved');", w -> { }, (app, window) -> { });
        assertTrue(reported.startsWith("error: ") && reported.contains("enableSaveAs"), reported);
    }

    @Test
    void saveAsRefusesDataItCannotWrite() throws Exception {
        Path folder = Files.createTempDirectory("jolt-saveas-");
        try {
            String reported = pageReport("await jolt.saveAs({ not: 'bytes' }); report('saved');", w -> w.enableSaveAs(folder), (app, window) -> { });
            assertTrue(reported.startsWith("error: ") && reported.contains("typed array"), reported);
        } finally {
            deleteAll(folder);
        }
    }

    private static void deleteAll(Path folder) throws java.io.IOException {
        try (var files = Files.list(folder)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
        Files.deleteIfExists(folder);
    }

    /** Answers the dialogs one after the other: true accepts, false cancels. */
    private static void answerEach(Application app, LinuxWindow window, boolean... accepts) {
        Thread.ofVirtual().start(() -> {
            try {
                for (boolean accept : accepts) {
                    for (int i = 0; i < 400 && !window.dialogs().isShowing(); i++) {
                        Thread.sleep(25);
                    }
                    Thread.sleep(200);
                    app.dispatch(() -> window.dialogs().respond(accept));
                    for (int i = 0; i < 400 && window.dialogs().isShowing(); i++) {
                        Thread.sleep(25);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Test
    void aRequestedSizeIsGivenToTheDialog() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("JOLT_TEST_KIOSK_COMPOSITOR") == null,
                "the kiosk compositor keeps every window the size of the screen");
        int[][] measured = new int[1][];
        run((app, dialogs, window) -> {
            CompletableFuture<Optional<Path>> f = dialogs.openFile(NO_OPTIONS.withSize(800, 500));
            Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 400 && !dialogs.isShowing(); i++) {
                        Thread.sleep(25);
                    }
                    Thread.sleep(500); // let the dialog be mapped and sized
                    measured[0] = app.submit(dialogs::showingSize).get();
                    app.dispatch(() -> dialogs.respond(false));
                } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
                    // the test fails by timing out
                }
            });
            return f;
        });
        assertTrue(measured[0] != null, "a dialog with a width is GTK's own chooser window");
        assertTrue(Math.abs(measured[0][0] - 800) <= 20, "dialog is " + measured[0][0] + " px wide but should be about 800");
        assertTrue(Math.abs(measured[0][1] - 500) <= 20, "dialog is " + measured[0][1] + " px tall but should be about 500");
    }

    @Test
    void cancellingAnOpenDialogGivesNothingNotAnError() {
        CompletableFuture<Optional<Path>> result = run((app, dialogs, window) -> {
            CompletableFuture<Optional<Path>> f = dialogs.openFile(NO_OPTIONS.withTitle("Jolt test: open"));
            answerWhenShown(app, dialogs, false);
            return f;
        });
        assertEquals(Optional.empty(), result.getNow(null));
    }

    @Test
    void cancellingTheOtherDialogsAlsoGivesNothing() {
        List<Object> results = new java.util.ArrayList<>();
        run((app, dialogs, window) -> {
            CompletableFuture<?> all = dialogs.openFiles(NO_OPTIONS).thenAccept(results::add);
            answerWhenShown(app, dialogs, false);
            return all.thenCompose(v -> {
                CompletableFuture<?> folder = dialogs.chooseFolder(NO_OPTIONS).thenAccept(results::add);
                answerWhenShown(app, dialogs, false);
                return folder;
            }).thenCompose(v -> {
                CompletableFuture<?> save = dialogs.saveFile(NO_OPTIONS.withFileName("x.txt")).thenAccept(results::add);
                answerWhenShown(app, dialogs, false);
                return save;
            });
        });
        assertEquals(List.of(List.of(), Optional.empty(), Optional.empty()), results);
    }

    @Test
    void aSaveDialogReturnsTheFolderAndNameWithoutCreatingTheFile() throws Exception {
        Path folder = Files.createTempDirectory("jolt-dialog-");
        try {
            CompletableFuture<Optional<Path>> result = run((app, dialogs, window) -> {
                CompletableFuture<Optional<Path>> f = dialogs.saveFile(NO_OPTIONS
                        .withTitle("Jolt test: save")
                        .withInitialDirectory(folder)
                        .withFileName("report.pdf")
                        .withFilters(FileFilter.of("PDF documents", "pdf")));
                answerWhenShown(app, dialogs, true);
                return f;
            });
            Optional<Path> path = result.getNow(null);
            assertTrue(path.isPresent(), "accepting the dialog gives a path");
            assertEquals(folder.toRealPath(), path.get().getParent().toRealPath());
            assertEquals("report.pdf", path.get().getFileName().toString());
            assertFalse(Files.exists(path.get()), "the dialog does not create the file");
        } finally {
            Files.deleteIfExists(folder);
        }
    }

    @Test
    void anOpenDialogReturnsTheFileThatWasAlreadyChosenWhenTheUserConfirms() throws Exception {
        Path folder = Files.createTempDirectory("jolt-dialog-");
        Path file = Files.writeString(folder.resolve("notes.txt"), "hello");
        try {
            CompletableFuture<Optional<Path>> result = run((app, dialogs, window) -> {
                CompletableFuture<Optional<Path>> f = dialogs.openFile(NO_OPTIONS
                        .withInitialDirectory(folder)
                        .withFileName("notes.txt")
                        .withFilters(FileFilter.of("Text files", "txt")));
                answerWhenShown(app, dialogs, true);
                return f;
            });
            assertEquals(file.toRealPath(), result.getNow(null).orElseThrow().toRealPath());
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(folder);
        }
    }

    @Test
    void aSecondRequestWaitsForTheFirstInsteadOfOpeningOnTopOfIt() {
        List<String> order = new java.util.ArrayList<>();
        run((app, dialogs, window) -> {
            CompletableFuture<?> first = dialogs.openFile(NO_OPTIONS).thenAccept(p -> order.add("first"));
            CompletableFuture<?> second = dialogs.openFile(NO_OPTIONS).thenAccept(p -> order.add("second"));
            answerWhenShown(app, dialogs, false);
            first.thenRun(() -> answerWhenShown(app, dialogs, false));
            return CompletableFuture.allOf(first, second);
        });
        assertEquals(List.of("first", "second"), order);
    }

    @Test
    void closingTheWindowWhileADialogIsOpenFailsTheRequest() {
        CompletableFuture<Optional<Path>> outcome = new CompletableFuture<>();
        WebWindowContract.runApp(40, app -> {
            LinuxWindow window = (LinuxWindow) app.createWindow(WindowOptions.defaults().withSize(900, 500));
            window.dialogs().openFile(NO_OPTIONS).whenComplete((v, e) -> {
                if (e != null) {
                    outcome.completeExceptionally(e);
                } else {
                    outcome.complete(v);
                }
            });
            Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 400 && !window.dialogs().isShowing(); i++) {
                        Thread.sleep(25);
                    }
                } catch (InterruptedException e) {
                    return;
                }
                window.close();
            });
        });
        CompletionException failure = assertThrows(CompletionException.class, outcome::join);
        assertInstanceOf(WindowClosedException.class, failure.getCause());
    }
}
