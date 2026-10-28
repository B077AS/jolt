package io.github.b077as.jolt;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One choice in a file dialog's file type list, for example {@code FileFilter.of("Images", "png", "jpg", "jpeg")}.
 *
 * @param name       what the user sees, such as {@code "Images"}. The extensions are added to it by the dialog where the
 *                   platform does that (Windows shows {@code Images (*.png, *.jpg)})
 * @param extensions file name endings without the dot or a wildcard, matched ignoring case. Letters, digits and
 *                   {@code _ + -} only
 */
public record FileFilter(String name, List<String> extensions) {

    private static final Pattern EXTENSION = Pattern.compile("[A-Za-z0-9_+-]+");

    public FileFilter {
        Objects.requireNonNull(name, "name");
        extensions = List.copyOf(extensions);
        if (extensions.isEmpty()) {
            throw new IllegalArgumentException("a filter needs at least one extension");
        }
        for (String extension : extensions) {
            if (extension == null || !EXTENSION.matcher(extension).matches()) {
                throw new IllegalArgumentException("an extension is letters, digits or _ + - without a dot or wildcard: " + extension);
            }
        }
    }

    public static FileFilter of(String name, String... extensions) {
        return new FileFilter(name, List.of(extensions));
    }
}
