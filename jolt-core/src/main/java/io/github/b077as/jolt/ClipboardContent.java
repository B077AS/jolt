package io.github.b077as.jolt;

import io.github.b077as.jolt.internal.ClipboardData;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * What to put on the clipboard: any mix of text, HTML, an image and a list of files, put there together as one copy
 * (programs read whichever form they prefer). Build one with the static factories and the {@code with...} methods, then pass
 * it to {@link Clipboard#write}.
 *
 * <p>A value that is {@link #sensitive() sensitive} (a password, a token) is marked so that the system's clipboard history
 * and cloud sync do not keep it, and it is taken off the clipboard again after {@link #clearAfter()} if nothing else has been
 * copied by then. {@link #secret(String)} sets both up for you. The marking is a request that well-behaved programs honour;
 * any program the user runs can still read the clipboard while the value is on it.
 *
 * <p>Checked when built: something must be present; text and HTML are at most {@link ClipboardData#MAX_BYTES} bytes as UTF-8 and
 * text has no NUL character (the system formats end at one); the image must be a PNG with a sane header; files must be
 * absolute local paths (network paths are refused, see {@link ClipboardData#isLocalAbsolutePath}), at most
 * {@value ClipboardData#MAX_FILES} of them. Violations throw {@link IllegalArgumentException}.
 *
 * @param text       plain text, or {@code null}
 * @param html       an HTML fragment (not a whole document), or {@code null}. Programs that paste it may render it, so it
 *                   must not contain markup you do not trust
 * @param png        a PNG file, or {@code null}
 * @param files      absolute paths of local files (a copy of them, as a file manager would put there); empty for none
 * @param sensitive  keep the value out of clipboard history and cloud sync
 * @param clearAfter remove the value from the clipboard after this long unless something else was copied meanwhile;
 *                   {@code null} to leave it
 */
public record ClipboardContent(String text, String html, byte[] png, List<Path> files, boolean sensitive, Duration clearAfter) {

    /** How long {@link #secret(String)} leaves a value on the clipboard. */
    public static final Duration DEFAULT_SECRET_LIFETIME = Duration.ofSeconds(30);

    public ClipboardContent {
        files = files == null ? List.of() : List.copyOf(files);
        png = png == null ? null : png.clone();
        if (text == null && html == null && png == null && files.isEmpty()) {
            throw new IllegalArgumentException("nothing to put on the clipboard");
        }
        if (text != null && (ClipboardData.hasNul(text) || utf8Length(text) > ClipboardData.MAX_BYTES)) {
            throw new IllegalArgumentException("clipboard text must not contain NUL and must be at most "
                    + ClipboardData.MAX_BYTES + " bytes");
        }
        if (html != null && (ClipboardData.hasNul(html) || utf8Length(html) > ClipboardData.MAX_BYTES)) {
            throw new IllegalArgumentException("clipboard HTML must not contain NUL and must be at most "
                    + ClipboardData.MAX_BYTES + " bytes");
        }
        if (png != null && ClipboardData.pngSize(png) == null) {
            throw new IllegalArgumentException("the image must be a PNG file of at most " + ClipboardData.MAX_BYTES + " bytes");
        }
        if (files.size() > ClipboardData.MAX_FILES) {
            throw new IllegalArgumentException("too many files for the clipboard (" + files.size() + ")");
        }
        for (Path file : files) {
            if (!ClipboardData.isLocalAbsolutePath(file)) {
                throw new IllegalArgumentException("clipboard files must be absolute local paths: " + file);
            }
        }
        if (clearAfter != null && (clearAfter.isNegative() || clearAfter.isZero())) {
            throw new IllegalArgumentException("clearAfter must be positive");
        }
    }

    private static long utf8Length(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    public static ClipboardContent text(String text) {
        return new ClipboardContent(Objects.requireNonNull(text, "text"), null, null, null, false, null);
    }

    /** {@code html} is a fragment; {@code plainText} is what programs that cannot paste HTML get instead (may be {@code null}). */
    public static ClipboardContent html(String html, String plainText) {
        return new ClipboardContent(plainText, Objects.requireNonNull(html, "html"), null, null, false, null);
    }

    public static ClipboardContent image(byte[] png) {
        return new ClipboardContent(null, null, Objects.requireNonNull(png, "png"), null, false, null);
    }

    public static ClipboardContent files(List<Path> files) {
        return new ClipboardContent(null, null, null, Objects.requireNonNull(files, "files"), false, null);
    }

    /** Text that is {@link #sensitive() sensitive} and removed again after {@link #DEFAULT_SECRET_LIFETIME}. */
    public static ClipboardContent secret(String text) {
        return secret(text, DEFAULT_SECRET_LIFETIME);
    }

    public static ClipboardContent secret(String text, Duration clearAfter) {
        return new ClipboardContent(Objects.requireNonNull(text, "text"), null, null, null, true,
                Objects.requireNonNull(clearAfter, "clearAfter"));
    }

    public ClipboardContent withText(String text) {
        return new ClipboardContent(text, html, png, files, sensitive, clearAfter);
    }

    public ClipboardContent withHtml(String html) {
        return new ClipboardContent(text, html, png, files, sensitive, clearAfter);
    }

    public ClipboardContent withImage(byte[] png) {
        return new ClipboardContent(text, html, png, files, sensitive, clearAfter);
    }

    public ClipboardContent withFiles(List<Path> files) {
        return new ClipboardContent(text, html, png, files, sensitive, clearAfter);
    }

    public ClipboardContent withSensitive(boolean sensitive) {
        return new ClipboardContent(text, html, png, files, sensitive, clearAfter);
    }

    public ClipboardContent withClearAfter(Duration clearAfter) {
        return new ClipboardContent(text, html, png, files, sensitive, clearAfter);
    }

    /** A copy of the image bytes, or {@code null}. */
    @Override
    public byte[] png() {
        return png == null ? null : png.clone();
    }

    /** Never shows the content: this ends up in logs. */
    @Override
    public String toString() {
        return "ClipboardContent[text=" + (text != null) + ", html=" + (html != null) + ", image=" + (png != null)
                + ", files=" + files.size() + ", sensitive=" + sensitive + ", clearAfter=" + clearAfter + "]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClipboardContent c && Objects.equals(text, c.text) && Objects.equals(html, c.html)
                && java.util.Arrays.equals(png, c.png) && files.equals(c.files) && sensitive == c.sensitive
                && Objects.equals(clearAfter, c.clearAfter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, html, java.util.Arrays.hashCode(png), files, sensitive, clearAfter);
    }
}
