package io.github.b077as.jolt.win32;

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
 * The real common item dialogs. Nobody can click in them here, so each test waits until the dialog is up and then closes it from
 * the UI thread with {@code IFileDialog::Close}, which makes {@code Show} return exactly as if the user had pressed OK or Cancel.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsDialogsTest {

    private static final FileDialogOptions NO_OPTIONS = FileDialogOptions.defaults();

    private static final java.lang.foreign.Linker LINKER = java.lang.foreign.Linker.nativeLinker();
    private static final java.lang.foreign.SymbolLookup USER32 = java.lang.foreign.SymbolLookup.libraryLookup("user32.dll", java.lang.foreign.Arena.global());
    private static final java.lang.invoke.MethodHandle GET_WINDOW = LINKER.downcallHandle(USER32.find("GetWindow").orElseThrow(),
            java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.JAVA_INT));
    private static final java.lang.invoke.MethodHandle GET_DLG_ITEM = LINKER.downcallHandle(USER32.find("GetDlgItem").orElseThrow(),
            java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.JAVA_INT));
    private static final java.lang.invoke.MethodHandle POST_MESSAGE = LINKER.downcallHandle(USER32.find("PostMessageW").orElseThrow(),
            java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.ADDRESS,
                    java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_LONG, java.lang.foreign.ValueLayout.JAVA_LONG));

    /** Presses the dialog's default button (Open / Save) the way a user would: it is the control with the id IDOK. UI thread. */
    private static boolean pressOk(WindowsWindow window) throws Throwable {
        java.lang.foreign.MemorySegment dialog = (java.lang.foreign.MemorySegment) GET_WINDOW.invokeExact(window.hwnd(), 6 /* GW_ENABLEDPOPUP */);
        if (dialog.equals(java.lang.foreign.MemorySegment.NULL)) {
            return false;
        }
        java.lang.foreign.MemorySegment ok = (java.lang.foreign.MemorySegment) GET_DLG_ITEM.invokeExact(dialog, 1 /* IDOK */);
        if (ok.equals(java.lang.foreign.MemorySegment.NULL)) {
            return false;
        }
        int posted = (int) POST_MESSAGE.invokeExact(ok, 0x00F5 /* BM_CLICK */, 0L, 0L);
        return posted != 0;
    }

    /** Presses OK once the dialog is on screen. */
    private static void pressOkWhenShown(Application app, WindowsWindow window) {
        answerEach(app, window, true);
    }

    /** Closes the dialog as soon as it is on screen, from a thread of its own that hands the close to the UI thread. */
    private static void closeWhenShown(Application app, WindowsDialogs dialogs, boolean accept) {
        Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < 400 && !dialogs.isShowing(); i++) {
                    Thread.sleep(25);
                }
                app.dispatch(() -> dialogs.cancelShowing());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private interface Body<T> {
        CompletableFuture<T> apply(Application app, WindowsDialogs dialogs, WindowsWindow window);
    }

    /** Runs {@code body} with a window's dialogs; the body returns the future to wait for. Returns what it completed with. */
    private static <T> CompletableFuture<T> run(Body<T> body) {
        CompletableFuture<T> outcome = new CompletableFuture<>();
        WebWindowContract.runApp(40, app -> {
            WindowsWindow window = (WindowsWindow) app.createWindow(WindowOptions.defaults().withSize(900, 500));
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
    private static String pageReport(String script, FolderSetup folderSetup, java.util.function.BiConsumer<Application, WindowsWindow> interact) {
        CompletableFuture<String> reported = new CompletableFuture<>();
        WebWindowContract.runApp(40, app -> {
            WindowsWindow window = (WindowsWindow) app.createWindow(WindowOptions.defaults().withSize(900, 500));
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
        void apply(WindowsWindow window);
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
                    (app, window) -> pressOkWhenShown(app, window));
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

    /** Answers the dialogs one after the other: true presses OK, false cancels. */
    private static void answerEach(Application app, WindowsWindow window, boolean... accepts) {
        Thread.ofVirtual().start(() -> {
            try {
                WindowsDialogs dialogs = window.dialogs();
                for (int n = 0; n < accepts.length; n++) {
                    for (int i = 0; i < 400 && dialogs.shownCount() <= n; i++) {
                        Thread.sleep(25); // wait for dialog number n + 1, not for any dialog
                    }
                    int mine = n + 1;
                    if (accepts[n]) {
                        // A click sent while the dialog is still laying itself out is lost, so press, and press again if it is still open.
                        for (int attempt = 0; attempt < 12 && dialogs.shownCount() == mine && dialogs.isShowing(); attempt++) {
                            Thread.sleep(400);
                            app.submit(() -> {
                                try {
                                    return dialogs.shownCount() == mine && pressOk(window);
                                } catch (Throwable t) {
                                    throw new IllegalStateException(t);
                                }
                            }).get();
                            for (int i = 0; i < 60 && dialogs.shownCount() == mine && dialogs.isShowing(); i++) {
                                Thread.sleep(25);
                            }
                        }
                    } else {
                        // Close asked for while the dialog is still opening can be missed, so ask again until it has gone.
                        for (int attempt = 0; attempt < 12 && dialogs.shownCount() == mine && dialogs.isShowing(); attempt++) {
                            Thread.sleep(300);
                            app.dispatch(() -> {
                                if (dialogs.shownCount() == mine) {
                                    dialogs.cancelShowing();
                                }
                            });
                            for (int i = 0; i < 20 && dialogs.shownCount() == mine && dialogs.isShowing(); i++) {
                                Thread.sleep(25);
                            }
                        }
                    }
                    for (int i = 0; i < 400 && dialogs.shownCount() == mine && dialogs.isShowing(); i++) {
                        Thread.sleep(25);
                    }
                }
            } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
                // the test fails by timing out
            }
        });
    }

    @Test
    void aRequestedSizeIsGivenToTheDialogOnceItIsOpen() {
        int[] measured = new int[4];
        run((app, dialogs, window) -> {
            CompletableFuture<Optional<Path>> f = dialogs.openFile(NO_OPTIONS.withSize(1100, 650));
            Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 400 && !dialogs.isShowing(); i++) {
                        Thread.sleep(25);
                    }
                    Thread.sleep(800); // the dialog is widened a few milliseconds after it appears
                    app.submit(() -> {
                        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
                            java.lang.foreign.MemorySegment popup = Win32.enabledPopupOf(window.hwnd());
                            java.lang.foreign.MemorySegment rect = arena.allocate(Win32.RECT);
                            Win32.getWindowRect(popup, rect);
                            measured[0] = rect.get(java.lang.foreign.ValueLayout.JAVA_INT, 8) - rect.get(java.lang.foreign.ValueLayout.JAVA_INT, 0);
                            int[] area = Win32.workAreaOf(window.hwnd());
                            int wanted = Math.round(1100 * Win32.dpiForWindow(window.hwnd()) / 96f);
                            measured[1] = Math.min(wanted, area[2] - area[0]);
                            measured[2] = rect.get(java.lang.foreign.ValueLayout.JAVA_INT, 12) - rect.get(java.lang.foreign.ValueLayout.JAVA_INT, 4);
                            measured[3] = Math.min(Math.round(650 * Win32.dpiForWindow(window.hwnd()) / 96f), area[3] - area[1]);
                        }
                        return null;
                    }).get();
                    app.dispatch(dialogs::cancelShowing);
                } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
                    // the test fails by timing out
                }
            });
            return f;
        });
        assertTrue(Math.abs(measured[0] - measured[1]) <= 3, "dialog is " + measured[0] + " px wide but should be " + measured[1]);
        assertTrue(Math.abs(measured[2] - measured[3]) <= 3, "dialog is " + measured[2] + " px tall but should be " + measured[3]);
    }

    @Test
    void cancellingAnOpenDialogGivesNothingNotAnError() {
        CompletableFuture<Optional<Path>> result = run((app, dialogs, window) -> {
            CompletableFuture<Optional<Path>> f = dialogs.openFile(NO_OPTIONS.withTitle("Jolt test: open"));
            closeWhenShown(app, dialogs, false);
            return f;
        });
        assertEquals(Optional.empty(), result.getNow(null));
    }

    @Test
    void cancellingTheOtherDialogsAlsoGivesNothing() {
        List<Object> results = new java.util.ArrayList<>();
        run((app, dialogs, window) -> {
            CompletableFuture<?> all = dialogs.openFiles(NO_OPTIONS).thenAccept(results::add);
            closeWhenShown(app, dialogs, false);
            return all.thenCompose(v -> {
                CompletableFuture<?> folder = dialogs.chooseFolder(NO_OPTIONS).thenAccept(results::add);
                closeWhenShown(app, dialogs, false);
                return folder;
            }).thenCompose(v -> {
                CompletableFuture<?> save = dialogs.saveFile(NO_OPTIONS.withFileName("x.txt")).thenAccept(results::add);
                closeWhenShown(app, dialogs, false);
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
                pressOkWhenShown(app, window);
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
                pressOkWhenShown(app, window);
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
            closeWhenShown(app, dialogs, false);
            first.thenRun(() -> closeWhenShown(app, dialogs, false));
            return CompletableFuture.allOf(first, second);
        });
        assertEquals(List.of("first", "second"), order);
    }

    @Test
    void closingTheWindowWhileADialogIsOpenFailsTheRequest() {
        CompletableFuture<Optional<Path>> outcome = new CompletableFuture<>();
        WebWindowContract.runApp(40, app -> {
            WindowsWindow window = (WindowsWindow) app.createWindow(WindowOptions.defaults().withSize(900, 500));
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
