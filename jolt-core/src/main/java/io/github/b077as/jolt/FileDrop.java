package io.github.b077as.jolt;

import java.nio.file.Path;
import java.util.List;

/**
 * Files the user dropped onto a window from the file manager, see {@link WebWindow#onFileDrop}.
 *
 * @param files the dropped files and folders: absolute paths on this machine, never network ({@code \\server\share}) or device
 *              paths, at most 4096. Nothing has been read; check that a path is what you expect before using it
 * @param zone  the value of the {@code data-jolt-drop} attribute of the element the files landed on (or inside), so one window can
 *              have several drop areas ({@code <div data-jolt-drop="avatar">}); {@code null} if they landed outside every such element
 * @param x     where the pointer was, in the page's CSS pixels from the left edge of the page
 * @param y     where the pointer was, in the page's CSS pixels from the top edge of the page
 */
public record FileDrop(List<Path> files, String zone, int x, int y) {

    public FileDrop {
        files = List.copyOf(files);
    }

    /** The paths are the user's business, so they are not shown by accident in logs. */
    @Override
    public String toString() {
        return "FileDrop[" + files.size() + (files.size() == 1 ? " file" : " files") + ", zone=" + zone + ", x=" + x + ", y=" + y + "]";
    }
}
