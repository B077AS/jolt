package io.github.b077as.jolt;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The system clipboard, for Java code. Get it from {@link Application#clipboard()}. It needs no AWT and works from any
 * thread: every call runs on the UI thread and its future completes there, so do not block the UI thread waiting on one.
 *
 * <p><b>This is for your code, not for the page.</b> Web content gets no access through Jolt. A page's own
 * {@code navigator.clipboard} is governed by the window's {@link SecurityPolicy}: with the default policy
 * ({@code denyPermissions}) a page cannot read the clipboard. If you want a page to copy through Java, expose a handler of your own
 * and decide what it may do.
 *
 * <p><b>The clipboard is shared with every program the user runs, so treat what you read as hostile input.</b>
 * <ul>
 *   <li>Text, HTML and file names come from whoever last copied. Do not run, render or trust them without checking.</li>
 *   <li>{@link #readHtml()} returns a fragment that may contain scripts and remote references; never put it in a page
 *       without sanitizing it.</li>
 *   <li>{@link #readImage()} returns a validated PNG header, but the image data itself is not decoded or checked: a program that
 *       decodes it should be prepared for a malformed one.</li>
 *   <li>{@link #readFiles()} only returns absolute local paths (no network paths) but says nothing about what is at them.</li>
 *   <li>Reads that find more than {@value io.github.b077as.jolt.internal.ClipboardData#MAX_BYTES} bytes fail instead of loading it.</li>
 * </ul>
 * Contents are never logged. To put a password or token on the clipboard use {@link ClipboardContent#secret}.
 */
public interface Clipboard {

    /** The kinds of content a clipboard can hold. */
    enum Format {
        /** Plain text. */
        TEXT,
        /** An HTML fragment. */
        HTML,
        /** A picture. */
        IMAGE,
        /**
         * A list of files, as copied in a file manager. On Linux this is reported whenever a {@code text/uri-list} is offered,
         * which browsers also do for plain links; {@link #readFiles()} then returns an empty list.
         */
        FILES
    }

    /** What the clipboard holds right now. */
    CompletableFuture<Set<Format>> formats();

    /** The text on the clipboard, or empty if there is none. */
    CompletableFuture<Optional<String>> readText();

    /** The HTML fragment on the clipboard, or empty. Untrusted markup: see the class description. */
    CompletableFuture<Optional<String>> readHtml();

    /**
     * The picture on the clipboard as PNG data, or empty if there is none or it is in a form that cannot be converted
     * (Windows bitmaps with a palette or compression).
     */
    CompletableFuture<Optional<byte[]>> readImage();

    /** The files on the clipboard (absolute local paths), or an empty list. */
    CompletableFuture<List<Path>> readFiles();

    /**
     * Replaces the clipboard's content. If {@link ClipboardContent#clearAfter()} is set and the clipboard still holds this
     * content then, it is emptied; whatever of it is still there when the application ends is removed too.
     */
    CompletableFuture<Void> write(ClipboardContent content);

    /** Same as {@code write(ClipboardContent.text(text))}. */
    default CompletableFuture<Void> writeText(String text) {
        return write(ClipboardContent.text(text));
    }

    /** Empties the clipboard. */
    CompletableFuture<Void> clear();

    /**
     * Calls {@code listener} on the UI thread whenever the clipboard's content changes, including changes made by this
     * application, with the formats it now holds (never the content itself: read it if you need it). Must return quickly.
     * Close the returned subscription to stop; the listener does not outlive the application.
     */
    Subscription onChange(Consumer<Set<Format>> listener);

    /** Ends a listener registration. */
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
