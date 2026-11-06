package io.github.b077as.jolt;

import io.github.b077as.jolt.internal.ClipboardData;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipboardContentTest {

    private static byte[] png() {
        return ClipboardData.encodePng(new ClipboardData.Image(1, 1, new byte[] {1, 2, 3, 4}));
    }

    private static Path absolute() {
        return Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("x.txt");
    }

    @Test
    void emptyContentIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ClipboardContent(null, null, null, null, false, null));
        assertThrows(IllegalArgumentException.class, () -> new ClipboardContent(null, null, null, List.of(), false, null));
    }

    @Test
    void textWithANulIsRefusedBecauseTheNativeFormatsWouldCutItShort() {
        assertThrows(IllegalArgumentException.class, () -> ClipboardContent.text("pass\0word"));
        assertThrows(IllegalArgumentException.class, () -> ClipboardContent.html("<b>a\0</b>", null));
    }

    @Test
    void oversizedTextIsRefused() {
        String big = "x".repeat(ClipboardData.MAX_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> ClipboardContent.text(big));
    }

    @Test
    void anImageMustBeAPng() {
        assertThrows(IllegalArgumentException.class, () -> ClipboardContent.image(new byte[] {1, 2, 3}));
        assertEquals(1, ClipboardContent.image(png()).png().length > 0 ? 1 : 0);
    }

    @Test
    void filesMustBeAbsoluteAndLocal() {
        assertThrows(IllegalArgumentException.class, () -> ClipboardContent.files(List.of(Path.of("relative.txt"))));
        if (java.io.File.separatorChar == '\\') {
            assertThrows(IllegalArgumentException.class, () -> ClipboardContent.files(List.of(Path.of("\\\\server\\share\\a.txt"))));
        }
        ClipboardContent.files(List.of(absolute()));
    }

    @Test
    void tooManyFilesAreRefused() {
        List<Path> many = java.util.Collections.nCopies(ClipboardData.MAX_FILES + 1, absolute());
        assertThrows(IllegalArgumentException.class, () -> ClipboardContent.files(many));
    }

    @Test
    void aSecretIsSensitiveAndExpiresByDefault() {
        ClipboardContent secret = ClipboardContent.secret("hunter2");
        assertTrue(secret.sensitive());
        assertEquals(ClipboardContent.DEFAULT_SECRET_LIFETIME, secret.clearAfter());
        assertThrows(IllegalArgumentException.class, () -> ClipboardContent.secret("x", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ClipboardContent.secret("x", Duration.ofSeconds(-1)));
    }

    @Test
    void theStringFormNeverShowsTheContent() {
        String shown = ClipboardContent.secret("hunter2-the-password").withHtml("<i>topsecret</i>").toString();
        assertFalse(shown.contains("hunter2"));
        assertFalse(shown.contains("topsecret"));
    }

    @Test
    void theImageIsCopiedSoCallersCannotChangeItLater() {
        byte[] original = png();
        ClipboardContent content = ClipboardContent.image(original);
        original[0] = 0;
        assertEquals((byte) 0x89, content.png()[0]);
        content.png()[0] = 0;
        assertEquals((byte) 0x89, content.png()[0]);
    }

    @Test
    void contentsWithEqualImagesAreEqual() {
        assertEquals(ClipboardContent.image(png()), ClipboardContent.image(png()));
        assertNotEquals(ClipboardContent.text("a"), ClipboardContent.text("b"));
    }

    @Test
    void severalFormatsCanBeCombinedIntoOneCopy() {
        ClipboardContent c = ClipboardContent.text("plain").withHtml("<b>plain</b>").withImage(png()).withFiles(List.of(absolute()));
        assertEquals("plain", c.text());
        assertEquals("<b>plain</b>", c.html());
        assertEquals(1, c.files().size());
    }
}
