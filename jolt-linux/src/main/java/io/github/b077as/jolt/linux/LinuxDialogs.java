package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.Dialogs;
import io.github.b077as.jolt.FileDialogOptions;
import io.github.b077as.jolt.FileFilter;
import io.github.b077as.jolt.WindowClosedException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * File dialogs for a GTK window. Everything runs on the GTK thread; {@code gtk_native_dialog_run} runs a nested main loop, so the
 * window and the web view keep working while a dialog is open.
 */
final class LinuxDialogs implements Dialogs {

    private enum Kind { OPEN_FILE, OPEN_FILES, SAVE_FILE, CHOOSE_FOLDER }

    private final LinuxApplication app;
    private final LinuxWindow window;

    // GTK thread only
    private final ArrayDeque<Runnable> waiting = new ArrayDeque<>();
    private boolean busy;
    private GtkDialogs.Dialog showing;
    private volatile boolean isShowing;

    LinuxDialogs(LinuxApplication app, LinuxWindow window) {
        this.app = app;
        this.window = window;
    }

    @Override
    public CompletableFuture<Optional<Path>> openFile(FileDialogOptions options) {
        return request(Kind.OPEN_FILE, options, paths -> paths.stream().findFirst());
    }

    @Override
    public CompletableFuture<List<Path>> openFiles(FileDialogOptions options) {
        return request(Kind.OPEN_FILES, options, paths -> paths);
    }

    @Override
    public CompletableFuture<Optional<Path>> saveFile(FileDialogOptions options) {
        return request(Kind.SAVE_FILE, options, paths -> paths.stream().findFirst());
    }

    @Override
    public CompletableFuture<Optional<Path>> chooseFolder(FileDialogOptions options) {
        return request(Kind.CHOOSE_FOLDER, options, paths -> paths.stream().findFirst());
    }

    /** Whether a dialog is open right now. For tests. */
    boolean isShowing() {
        return isShowing;
    }

    /** The open dialog's window size {width, height} if it is GTK's own chooser (the native one is not a window), else null. GTK thread. For tests. */
    int[] showingSize() {
        GtkDialogs.Dialog dialog = showing;
        if (dialog == null || !dialog.plain()) {
            return null;
        }
        int[] g = Gtk.windowGeometry(dialog.handle());
        return new int[] {g[2], g[3]};
    }

    /** Answers the open dialog as if the user had pressed its accept button or cancelled. GTK thread. */
    void respond(boolean accept) {
        if (showing != null) {
            try (Arena arena = Arena.ofConfined()) {
                GtkDialogs.respond(arena, showing, accept ? GtkDialogs.RESPONSE_ACCEPT : GtkDialogs.RESPONSE_CANCEL);
            }
        }
    }

    private <T> CompletableFuture<T> request(Kind kind, FileDialogOptions options, Function<List<Path>, T> result) {
        CompletableFuture<T> future = new CompletableFuture<>();
        FileDialogOptions o = options == null ? FileDialogOptions.defaults() : options;
        // A nested main loop also runs tasks dispatched meanwhile, so a second request would open on top of the first.
        // Requests wait their turn instead.
        Runnable job = () -> {
            busy = true;
            try {
                if (window.isDestroyed()) {
                    throw new WindowClosedException();
                }
                List<Path> paths = show(kind, o);
                if (window.isDestroyed()) {
                    throw new WindowClosedException();
                }
                future.complete(result.apply(paths));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                busy = false;
                if (window.isDestroyed()) {
                    window.destroyNow(); // the window was closed while the dialog was open
                }
                Runnable next = waiting.poll();
                if (next != null) {
                    app.dispatch(next);
                }
            }
        };
        window.ready().whenComplete((v, error) -> {
            if (error != null) {
                future.completeExceptionally(error);
                return;
            }
            app.dispatch(() -> {
                if (busy) {
                    waiting.add(job);
                } else {
                    job.run();
                }
            });
        });
        return future;
    }

    /** Shows one dialog and returns the chosen paths, empty when the user cancelled. GTK thread. */
    private List<Path> show(Kind kind, FileDialogOptions o) {
        int action = switch (kind) {
            case SAVE_FILE -> GtkDialogs.ACTION_SAVE;
            case CHOOSE_FOLDER -> GtkDialogs.ACTION_SELECT_FOLDER;
            default -> GtkDialogs.ACTION_OPEN;
        };
        String title = o.title() != null ? o.title() : switch (kind) {
            case SAVE_FILE -> "Save As";
            case CHOOSE_FOLDER -> "Select Folder";
            default -> "Open";
        };
        String accept = switch (kind) {
            case SAVE_FILE -> "_Save";
            case CHOOSE_FOLDER -> "_Select";
            default -> "_Open";
        };
        try (Arena arena = Arena.ofConfined()) {
            GtkDialogs.Dialog chooser = GtkDialogs.create(arena, title, window.nativeWindow(), action, accept, o.width(), o.height());
            MemorySegment dialog = chooser.handle();
            try {
                if (kind == Kind.OPEN_FILES) {
                    GtkDialogs.setSelectMultiple(dialog, true);
                }
                if (kind == Kind.SAVE_FILE) {
                    GtkDialogs.setOverwriteConfirmation(dialog);
                }
                if (kind != Kind.CHOOSE_FOLDER) {
                    MemorySegment first = MemorySegment.NULL;
                    for (FileFilter filter : o.filters()) {
                        MemorySegment added = GtkDialogs.addFilter(arena, dialog, filter.name(), filter.extensions());
                        if (first.equals(MemorySegment.NULL)) {
                            first = added;
                        }
                    }
                    if (!first.equals(MemorySegment.NULL)) {
                        GtkDialogs.setFilter(dialog, first);
                    }
                }
                Path folder = o.initialDirectory() != null && Files.isDirectory(o.initialDirectory()) ? o.initialDirectory().toAbsolutePath() : null;
                if (folder != null) {
                    GtkDialogs.setCurrentFolder(arena, dialog, folder.toString());
                }
                if (kind == Kind.SAVE_FILE && o.fileName() != null) {
                    GtkDialogs.setCurrentName(arena, dialog, o.fileName());
                } else if ((kind == Kind.OPEN_FILE || kind == Kind.OPEN_FILES) && o.fileName() != null && folder != null
                        && Files.exists(folder.resolve(o.fileName()))) {
                    GtkDialogs.setFilename(arena, dialog, folder.resolve(o.fileName()).toString());
                }

                showing = chooser;
                isShowing = true;
                if (chooser.plain()) {
                    resizeWhenMapped(chooser, o.width(), o.height());
                }
                int response;
                try {
                    response = GtkDialogs.run(chooser);
                } finally {
                    showing = null;
                    isShowing = false;
                }
                if (response != GtkDialogs.RESPONSE_ACCEPT) {
                    return List.of();
                }
                List<String> names = kind == Kind.OPEN_FILES ? GtkDialogs.filenames(dialog) : singleName(dialog);
                return names.stream().map(Path::of).toList();
            } finally {
                GtkDialogs.release(chooser);
            }
        }
    }

    /**
     * GTK's chooser restores the size it had last time when it is first shown, which overrides the default size set on it, so the
     * size is set again once the dialog is up. Runs from a thread that waits, so the nested main loop is never kept busy.
     */
    private void resizeWhenMapped(GtkDialogs.Dialog chooser, int width, int height) {
        Thread.ofVirtual().name("jolt-dialog-size").start(() -> {
            try {
                for (int attempt = 0; attempt < 8; attempt++) {
                    Thread.sleep(attempt == 0 ? 150 : 200);
                    boolean done = app.submit(() -> {
                        if (showing != chooser) {
                            return true; // closed
                        }
                        int[] now = Gtk.windowGeometry(chooser.handle());
                        boolean rightWidth = width <= 0 || Math.abs(now[2] - width) <= 2;
                        boolean rightHeight = height <= 0 || Math.abs(now[3] - height) <= 2;
                        if (rightWidth && rightHeight) {
                            return true; // it has the size asked for; leave it to the user from here
                        }
                        Gtk.windowResize(chooser.handle(), width > 0 ? width : now[2], height > 0 ? height : now[3]);
                        return false;
                    }).get();
                    if (done) {
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException e) {
                // the dialog keeps the size GTK gave it
            }
        });
    }

    private static List<String> singleName(MemorySegment dialog) {
        String name = GtkDialogs.filename(dialog);
        return name == null ? List.of() : List.of(name);
    }
}
