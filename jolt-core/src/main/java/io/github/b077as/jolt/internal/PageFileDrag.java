package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.FileDragSource;
import io.github.b077as.jolt.WebWindow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Per-window state of {@link WebWindow#setFileDragSource}: asks the application which files a drag carries, then starts it. */
public final class PageFileDrag {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.filedrag");

    private static final Map<WebWindow, PageFileDrag> ENABLED = new IdentityHashMap<>();

    private volatile FileDragSource source;
    /** UI thread only: a drag is under way, and the loop it runs may deliver more messages. */
    private boolean dragging;

    private PageFileDrag(FileDragSource source) {
        this.source = source;
    }

    /** Makes {@code source} decide what the pages of {@code window} can drag out; replaces an earlier one. */
    public static void enable(WebWindow window, FileDragSource source) {
        boolean first = false;
        synchronized (ENABLED) {
            PageFileDrag existing = ENABLED.get(window);
            if (existing != null) {
                existing.source = source;
            } else {
                ENABLED.put(window, new PageFileDrag(source));
                window.onClosed(() -> {
                    synchronized (ENABLED) {
                        ENABLED.remove(window);
                    }
                });
                first = true;
            }
        }
        if (first) {
            String script = FileDragScript.build();
            window.addInitScript(script);
            window.eval(script).exceptionally(t -> null);
        }
    }

    /** A backend received {@code message} (a {@link FileDragProtocol} message) from a page of {@code window}. UI thread. */
    public static void handle(WebWindow window, String message) {
        PageFileDrag drag;
        synchronized (ENABLED) {
            drag = ENABLED.get(window);
        }
        String token = FileDragProtocol.token(message);
        if (drag == null || token == null || drag.dragging) {
            return;
        }
        if (!(window instanceof NativeFileDragHost host)) {
            LOG.log(System.Logger.Level.WARNING, "this window cannot drag files out");
            return;
        }
        FileDragSource current = drag.source;
        List<Path> files;
        try {
            files = current.filesFor(token);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "the file drag source failed", e);
            return;
        }
        if (!acceptable(files)) {
            return;
        }
        drag.dragging = true;
        boolean started = false;
        try {
            started = host.beginFileDrag(List.copyOf(files), dropped -> {
                drag.dragging = false;
                try {
                    current.finished(token, dropped);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "the file drag source failed", e);
                }
            });
        } finally {
            if (!started) {
                drag.dragging = false;
            }
        }
    }

    /** Whether {@code files} may be offered: some, not too many, each an absolute local path that exists. */
    static boolean acceptable(List<Path> files) {
        if (files == null || files.isEmpty()) {
            return false;
        }
        if (files.size() > ClipboardData.MAX_FILES) {
            LOG.log(System.Logger.Level.WARNING, "a file drag was refused: more than " + ClipboardData.MAX_FILES + " files");
            return false;
        }
        for (Path file : files) {
            if (!ClipboardData.isLocalAbsolutePath(file) || !Files.exists(file)) {
                LOG.log(System.Logger.Level.WARNING, "a file drag was refused: a path is not an existing local file");
                return false;
            }
        }
        return true;
    }
}
