package io.github.b077as.jolt;

import java.nio.file.Path;
import java.util.List;

/**
 * What a file dialog shows. Everything is optional; the operating system picks sensible defaults.
 *
 * @param title            the dialog's title, or {@code null} for the system's own ("Open", "Save As")
 * @param initialDirectory the folder the dialog starts in, or {@code null} for where the system last had the user. A folder that
 *                         does not exist is ignored
 * @param fileName         the file name filled in, such as {@code "report.pdf"}: the proposed name in a save dialog, the file
 *                         already chosen in an open dialog (so it only has to be confirmed). A name only, no folder; it is
 *                         looked for in {@code initialDirectory}. Ignored by {@link Dialogs#chooseFolder}
 * @param width            how wide the dialog should be, in logical pixels, or {@code 0} to leave the width to the system. The
 *                         operating system owns the dialog, so a size is a request: on Windows the dialog is resized right after
 *                         it opens (and Windows then remembers that size for the next dialog); on Linux a dialog with a size is
 *                         GTK's own file chooser, because the desktop portal's chooser cannot be sized, so it does not go through
 *                         the portal, and GTK will not make it smaller than its contents need
 * @param height           how tall the dialog should be, in logical pixels, or {@code 0} to leave the height to the system; see
 *                         {@code width}
 * @param filters          the file types offered, the first one selected. Empty means all files. Ignored by
 *                         {@link Dialogs#chooseFolder}
 */
public record FileDialogOptions(String title, Path initialDirectory, String fileName, int width, int height,
                                List<FileFilter> filters) {

    /** The largest width or height that can be asked for. */
    public static final int MAX_SIZE = 10_000;

    public FileDialogOptions {
        filters = List.copyOf(filters);
        if (fileName != null && (fileName.isBlank() || fileName.contains("/") || fileName.contains("\\"))) {
            throw new IllegalArgumentException("fileName is a file name, not a path: " + fileName);
        }
        if (width < 0 || width > MAX_SIZE || height < 0 || height > MAX_SIZE) {
            throw new IllegalArgumentException("width and height must be between 0 and " + MAX_SIZE + ": " + width + "x" + height);
        }
    }

    public static FileDialogOptions defaults() {
        return new FileDialogOptions(null, null, null, 0, 0, List.of());
    }

    public FileDialogOptions withTitle(String title) {
        return new FileDialogOptions(title, initialDirectory, fileName, width, height, filters);
    }

    public FileDialogOptions withInitialDirectory(Path directory) {
        return new FileDialogOptions(title, directory, fileName, width, height, filters);
    }

    public FileDialogOptions withFileName(String fileName) {
        return new FileDialogOptions(title, initialDirectory, fileName, width, height, filters);
    }

    /** How big the dialog should be in logical pixels, see {@link #width()}; {@code 0} leaves that dimension to the system. */
    public FileDialogOptions withSize(int width, int height) {
        return new FileDialogOptions(title, initialDirectory, fileName, width, height, filters);
    }

    public FileDialogOptions withFilters(FileFilter... filters) {
        return new FileDialogOptions(title, initialDirectory, fileName, width, height, List.of(filters));
    }
}
