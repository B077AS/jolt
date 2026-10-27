package io.github.b077as.jolt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The files of a web app (HTML, CSS, JavaScript, images, fonts) that a window serves with
 * {@link WebWindow#loadResources}, so the page can be split into ordinary files that refer to each other with
 * relative URLs.
 */
@FunctionalInterface
public interface AppResources {

    /**
     * Returns the bytes of the file at {@code path}, or {@code null} if there is none. {@code path} is relative, uses
     * {@code /} separators, has no leading slash and never contains {@code .} or {@code ..} segments. A request for a
     * folder arrives as {@code index.html} inside it, so the root is {@code index.html}.
     */
    byte[] read(String path) throws IOException;

    /**
     * Files bundled in your jar (or on the classpath) under {@code base}, for example {@code "ui"} for
     * {@code src/main/resources/ui}.
     */
    static AppResources classpath(String base) {
        return classpath(Thread.currentThread().getContextClassLoader(), base);
    }

    /** Like {@link #classpath(String)}, with the class loader to read from. */
    static AppResources classpath(ClassLoader loader, String base) {
        String prefix = base.replaceAll("^/+|/+$", "");
        String root = prefix.isEmpty() ? "" : prefix + "/";
        return path -> {
            try (InputStream in = loader.getResourceAsStream(root + path)) {
                return in == null ? null : in.readAllBytes();
            }
        };
    }

    /** Files in a folder on disk, for example your frontend's source folder while you develop. */
    static AppResources directory(Path folder) {
        Path root = folder.toAbsolutePath().normalize();
        return path -> {
            Path file = root.resolve(path).normalize();
            return file.startsWith(root) && Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        };
    }
}
