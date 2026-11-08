package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.FileDrop;
import io.github.b077as.jolt.WebWindow;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Per-window state of {@link WebWindow#onFileDrop}: the listeners, and the hand-over from a backend that has received a file-drop
 * message from the page together with the paths the platform reported.
 */
public final class PageFileDrop {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.filedrop");

    private static final Map<WebWindow, PageFileDrop> ENABLED = new IdentityHashMap<>();

    private final List<Consumer<FileDrop>> listeners = new CopyOnWriteArrayList<>();

    private PageFileDrop() {}

    /** Registers {@code listener} for {@code window}; the first call installs the page script. */
    public static void enable(WebWindow window, Consumer<FileDrop> listener) {
        PageFileDrop drop;
        boolean first;
        synchronized (ENABLED) {
            drop = ENABLED.get(window);
            first = drop == null;
            if (first) {
                drop = new PageFileDrop();
                ENABLED.put(window, drop);
                window.onClosed(() -> {
                    synchronized (ENABLED) {
                        ENABLED.remove(window);
                    }
                });
            }
        }
        drop.listeners.add(listener);
        if (first) {
            String script = FileDropScript.build();
            window.addInitScript(script);
            window.eval(script).exceptionally(t -> null);
        }
    }

    /** Whether {@link #enable} was called for {@code window}. */
    public static boolean isEnabled(WebWindow window) {
        synchronized (ENABLED) {
            return ENABLED.containsKey(window);
        }
    }

    /**
     * A backend received {@code message} (a {@link FileDropProtocol} message) from a page of {@code window} and learned that
     * {@code paths} were dropped. Checks them against what the page said and tells the listeners. UI thread.
     */
    public static void deliver(WebWindow window, String message, List<Path> paths) {
        PageFileDrop drop;
        synchronized (ENABLED) {
            drop = ENABLED.get(window);
        }
        if (drop == null) {
            return;
        }
        FileDrop accepted = FileDropProtocol.accept(message, paths);
        if (accepted == null) {
            LOG.log(System.Logger.Level.WARNING, "a file drop was ignored: the page's message and the dropped files do not match");
            return;
        }
        for (Consumer<FileDrop> listener : drop.listeners) {
            try {
                listener.accept(accepted);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "file drop listener failed", e);
            }
        }
    }

    /** {@code raw} as paths; entries that are not valid paths are left out (and so make the drop fail its check). */
    public static List<Path> paths(List<String> raw) {
        List<Path> paths = new ArrayList<>();
        for (String text : raw) {
            try {
                paths.add(Path.of(text).normalize());
            } catch (InvalidPathException e) {
                // left out
            }
        }
        return paths;
    }
}
