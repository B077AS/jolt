package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.Dialogs;
import io.github.b077as.jolt.FileDialogOptions;
import io.github.b077as.jolt.FileFilter;
import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.WindowClosedException;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The common item dialogs ({@code IFileOpenDialog} / {@code IFileSaveDialog}) driven through their vtables. Everything runs on
 * the UI thread, which is the single-threaded COM apartment the dialogs need; {@code Show} runs its own modal message loop, so the
 * window and the web view keep working while it is open.
 */
final class WindowsDialogs implements Dialogs {

    private static final String CLSID_FILE_OPEN_DIALOG = "DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7";
    private static final String CLSID_FILE_SAVE_DIALOG = "C0B4E2F3-BA21-4773-8DBA-335EC946EB8B";
    private static final String IID_FILE_DIALOG = "42F85136-DB7E-439C-85F1-E4075D135FC8";
    private static final String IID_FILE_OPEN_DIALOG = "D57C7288-D4AD-4768-BE02-9D969532D960";
    private static final String IID_SHELL_ITEM = "43826D1E-E718-42EE-BC55-A1E261C37BFE";

    // IFileDialog (after IUnknown 0-2 and IModalWindow::Show 3)
    private static final int SHOW = 3, SET_FILE_TYPES = 4, SET_FILE_TYPE_INDEX = 5, SET_OPTIONS = 9, GET_OPTIONS = 10,
            SET_FOLDER = 12, SET_FILE_NAME = 15, SET_TITLE = 17, GET_RESULT = 20, SET_DEFAULT_EXTENSION = 22, CLOSE = 23;
    // IFileOpenDialog
    private static final int GET_RESULTS = 27;
    // IShellItem / IShellItemArray
    private static final int ITEM_GET_DISPLAY_NAME = 5, ARRAY_GET_COUNT = 7, ARRAY_GET_ITEM_AT = 8;

    private static final int FOS_OVERWRITEPROMPT = 0x2, FOS_NOCHANGEDIR = 0x8, FOS_PICKFOLDERS = 0x20, FOS_FORCEFILESYSTEM = 0x40,
            FOS_ALLOWMULTISELECT = 0x200, FOS_PATHMUSTEXIST = 0x800, FOS_FILEMUSTEXIST = 0x1000;
    private static final int WM_CLOSE = 0x0010;
    private static final int SIGDN_FILESYSPATH = 0x80058000;
    private static final int HRESULT_CANCELLED = 0x800704C7; // HRESULT_FROM_WIN32(ERROR_CANCELLED)

    /** {@code HRESULT m(this, UINT a, void* b)}: {@code SetFileTypes}, {@code GetItemAt}, and {@code GetDisplayName(SIGDN, LPWSTR*)}. */
    private static final FunctionDescriptor HR_INT_PTR = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
    private static final long FILTER_SPEC_SIZE = 2 * ADDRESS.byteSize();

    private enum Kind { OPEN_FILE, OPEN_FILES, SAVE_FILE, CHOOSE_FOLDER }

    private final WindowsApplication app;
    private final WindowsWindow window;

    // UI thread only
    private final ArrayDeque<Runnable> waiting = new ArrayDeque<>();
    private boolean busy;
    private volatile MemorySegment showing = MemorySegment.NULL;
    private volatile int shown;

    WindowsDialogs(WindowsApplication app, WindowsWindow window) {
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

    /** How many dialogs have been opened so far, so a test can tell one from the next. */
    int shownCount() {
        return shown;
    }

    /** Whether a dialog is open right now. For tests. */
    boolean isShowing() {
        return !showing.equals(MemorySegment.NULL);
    }

    /**
     * Cancels the open dialog, as if the user had pressed Cancel; {@code Show} then returns and the request completes empty. UI thread.
     * {@code IFileDialog::Close} alone is sometimes ignored by a dialog that has only just opened, so the dialog window is also asked
     * to close, which always works.
     */
    void cancelShowing() {
        MemorySegment dialog = showing;
        if (dialog.equals(MemorySegment.NULL)) {
            return;
        }
        Com.call(dialog, CLOSE, Com.HR_INT, HRESULT_CANCELLED);
        MemorySegment popup = Win32.enabledPopupOf(window.hwnd());
        if (!popup.equals(MemorySegment.NULL)) {
            Win32.postMessage(popup, WM_CLOSE, 0, 0);
        }
    }

    private <T> CompletableFuture<T> request(Kind kind, FileDialogOptions options, Function<List<Path>, T> result) {
        CompletableFuture<T> future = new CompletableFuture<>();
        FileDialogOptions o = options == null ? FileDialogOptions.defaults() : options;
        // A dialog's modal loop also runs tasks dispatched meanwhile, so a second request would open on top of the first.
        // Requests wait their turn instead.
        Runnable job = () -> {
            busy = true;
            try {
                if (window.isDestroyed()) {
                    throw new WindowClosedException();
                }
                List<Path> paths;
                try {
                    paths = show(kind, o);
                } catch (JoltException e) {
                    throw window.isDestroyed() ? new WindowClosedException() : e; // closing the owner ends the dialog with an error
                }
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
        // Not before the window and its web view have finished starting: a dialog opened over a window that is still being set
        // up crashed the process inside the shell.
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

    /** Shows one dialog and returns the chosen paths, empty when the user cancelled. UI thread. */
    private List<Path> show(Kind kind, FileDialogOptions o) {
        boolean save = kind == Kind.SAVE_FILE;
        MemorySegment dialog = Win32.coCreateInstance(save ? CLSID_FILE_SAVE_DIALOG : CLSID_FILE_OPEN_DIALOG, IID_FILE_DIALOG);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment flagsOut = arena.allocate(JAVA_INT);
            Com.callChecked("GetOptions", dialog, GET_OPTIONS, Com.HR_PTR, flagsOut);
            int flags = flagsOut.get(JAVA_INT, 0) | FOS_FORCEFILESYSTEM | FOS_NOCHANGEDIR | FOS_PATHMUSTEXIST;
            switch (kind) {
                case OPEN_FILE -> flags |= FOS_FILEMUSTEXIST;
                case OPEN_FILES -> flags |= FOS_FILEMUSTEXIST | FOS_ALLOWMULTISELECT;
                case SAVE_FILE -> flags |= FOS_OVERWRITEPROMPT;
                case CHOOSE_FOLDER -> flags |= FOS_PICKFOLDERS;
            }
            Com.callChecked("SetOptions", dialog, SET_OPTIONS, Com.HR_INT, flags);

            if (o.title() != null) {
                Com.callChecked("SetTitle", dialog, SET_TITLE, Com.HR_PTR, Native.allocWide(arena, o.title()));
            }
            if (kind != Kind.CHOOSE_FOLDER) {
                applyFilters(arena, dialog, o.filters(), save);
            }
            if (kind != Kind.CHOOSE_FOLDER && o.fileName() != null) {
                Com.callChecked("SetFileName", dialog, SET_FILE_NAME, Com.HR_PTR, Native.allocWide(arena, o.fileName()));
            }
            if (o.initialDirectory() != null && Files.isDirectory(o.initialDirectory())) {
                MemorySegment folder = Win32.shCreateItemFromParsingName(arena, o.initialDirectory().toAbsolutePath().toString(), IID_SHELL_ITEM);
                if (!folder.equals(MemorySegment.NULL)) {
                    try {
                        Com.call(dialog, SET_FOLDER, Com.HR_PTR, folder); // a folder the dialog cannot use is not worth failing for
                    } finally {
                        Com.release(folder);
                    }
                }
            }

            if (o.width() > 0 || o.height() > 0) {
                resizeWhenOpen(o.width(), o.height());
            }
            showing = dialog;
            shown++;
            int hr;
            try {
                hr = Com.call(dialog, SHOW, Com.HR_PTR, window.hwnd());
            } finally {
                showing = MemorySegment.NULL;
            }
            if (hr == HRESULT_CANCELLED) {
                return List.of();
            }
            Com.check("showing the file dialog", hr);
            return results(arena, dialog, kind);
        } finally {
            Com.release(dialog);
        }
    }

    /** Resizes the dialog about to be shown once its window exists, from a thread that polls so the UI thread is never kept busy. */
    private void resizeWhenOpen(int logicalWidth, int logicalHeight) {
        int number = shown + 1; // the dialog that is about to be shown
        Thread.ofVirtual().name("jolt-dialog-width").start(() -> {
            try {
                for (int i = 0; i < 300; i++) {
                    Thread.sleep(10);
                    if (app.submit(() -> resize(logicalWidth, logicalHeight, number)).get()) {
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException e) {
                // the dialog stays the width the system gave it
            }
        });
    }

    /** Sizes dialog number {@code number}, keeping it centred where it opened. Returns false to be asked again. UI thread. */
    private boolean resize(int logicalWidth, int logicalHeight, int number) {
        if (shown < number) {
            return false; // not shown yet
        }
        if (shown > number || showing.equals(MemorySegment.NULL)) {
            return true; // gone
        }
        MemorySegment popup = Win32.enabledPopupOf(window.hwnd());
        if (popup.equals(MemorySegment.NULL)) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rect = arena.allocate(Win32.RECT);
            Win32.getWindowRect(popup, rect);
            int left = rect.get(JAVA_INT, 0);
            int top = rect.get(JAVA_INT, 4);
            int width = rect.get(JAVA_INT, 8) - left;
            int height = rect.get(JAVA_INT, 12) - top;
            float scale = Win32.dpiForWindow(window.hwnd()) / 96f;
            int wantedWidth = logicalWidth > 0 ? Math.round(logicalWidth * scale) : width;
            int wantedHeight = logicalHeight > 0 ? Math.round(logicalHeight * scale) : height;
            int[] area = Win32.workAreaOf(window.hwnd());
            if (area != null) {
                wantedWidth = Math.min(wantedWidth, area[2] - area[0]);
                wantedHeight = Math.min(wantedHeight, area[3] - area[1]);
            }
            if (Math.abs(wantedWidth - width) > 2 || Math.abs(wantedHeight - height) > 2) {
                int newLeft = left - (wantedWidth - width) / 2;
                int newTop = top - (wantedHeight - height) / 2;
                if (area != null) {
                    newLeft = Math.max(area[0], Math.min(newLeft, area[2] - wantedWidth));
                    newTop = Math.max(area[1], Math.min(newTop, area[3] - wantedHeight));
                }
                Win32.setWindowPos(popup, newLeft, newTop, wantedWidth, wantedHeight, Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE);
            }
            return true;
        }
    }

    private static void applyFilters(Arena arena, MemorySegment dialog, List<FileFilter> filters, boolean save) {
        if (filters.isEmpty()) {
            return;
        }
        MemorySegment specs = arena.allocate(FILTER_SPEC_SIZE * filters.size());
        for (int i = 0; i < filters.size(); i++) {
            FileFilter filter = filters.get(i);
            List<String> patterns = filter.extensions().stream().map(e -> "*." + e).toList();
            String label = filter.name() + " (" + String.join(", ", patterns) + ")";
            specs.set(ADDRESS, i * FILTER_SPEC_SIZE, Native.allocWide(arena, label));
            specs.set(ADDRESS, i * FILTER_SPEC_SIZE + ADDRESS.byteSize(), Native.allocWide(arena, String.join(";", patterns)));
        }
        Com.callChecked("SetFileTypes", dialog, SET_FILE_TYPES, HR_INT_PTR, filters.size(), specs);
        Com.callChecked("SetFileTypeIndex", dialog, SET_FILE_TYPE_INDEX, Com.HR_INT, 1); // 1-based
        if (save) {
            // the extension added to a name typed without one
            Com.call(dialog, SET_DEFAULT_EXTENSION, Com.HR_PTR, Native.allocWide(arena, filters.get(0).extensions().get(0)));
        }
    }

    private static List<Path> results(Arena arena, MemorySegment dialog, Kind kind) {
        List<Path> paths = new ArrayList<>();
        MemorySegment out = arena.allocate(ADDRESS);
        if (kind == Kind.OPEN_FILES) {
            MemorySegment openDialog = Com.queryInterface(dialog, IID_FILE_OPEN_DIALOG);
            if (openDialog.equals(MemorySegment.NULL)) {
                throw new JoltException("the file dialog cannot return several files");
            }
            try {
                Com.callChecked("GetResults", openDialog, GET_RESULTS, Com.HR_PTR, out);
            } finally {
                Com.release(openDialog);
            }
            MemorySegment array = Native.readPointer(out);
            try {
                MemorySegment count = arena.allocate(JAVA_INT);
                Com.callChecked("GetCount", array, ARRAY_GET_COUNT, Com.HR_PTR, count);
                for (int i = 0; i < count.get(JAVA_INT, 0); i++) {
                    Com.callChecked("GetItemAt", array, ARRAY_GET_ITEM_AT, HR_INT_PTR, i, out);
                    paths.add(pathOf(arena, Native.readPointer(out)));
                }
            } finally {
                Com.release(array);
            }
        } else {
            Com.callChecked("GetResult", dialog, GET_RESULT, Com.HR_PTR, out);
            paths.add(pathOf(arena, Native.readPointer(out)));
        }
        return paths;
    }

    /** The file system path of a shell item; releases the item. */
    private static Path pathOf(Arena arena, MemorySegment item) {
        try {
            MemorySegment name = arena.allocate(ADDRESS);
            Com.callChecked("GetDisplayName", item, ITEM_GET_DISPLAY_NAME, HR_INT_PTR, SIGDN_FILESYSPATH, name);
            MemorySegment text = Native.readPointer(name);
            try {
                return Path.of(Native.readWide(text));
            } finally {
                Win32.coTaskMemFree(text);
            }
        } finally {
            Com.release(item);
        }
    }
}
