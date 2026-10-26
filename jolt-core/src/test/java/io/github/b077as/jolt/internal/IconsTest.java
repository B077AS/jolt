package io.github.b077as.jolt.internal;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IconsTest {

    private static final byte[] PNG_HEAD = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    /** An icon file with one image per width; each image is 12 bytes: either PNG-looking or bitmap-looking, tagged by width. */
    private static byte[] ico(boolean[] png, int... widths) {
        int count = widths.length;
        ByteBuffer b = ByteBuffer.allocate(6 + 16 * count + 12 * count).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) count);
        for (int i = 0; i < count; i++) {
            byte w = (byte) (widths[i] >= 256 ? 0 : widths[i]); // 256 is stored as 0
            b.put(w).put(w).put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
            b.putInt(12).putInt(6 + 16 * count + 12 * i);
        }
        for (int i = 0; i < count; i++) {
            byte[] image = new byte[12];
            if (png[i]) {
                System.arraycopy(PNG_HEAD, 0, image, 0, PNG_HEAD.length);
            } else {
                image[0] = 40; // a BITMAPINFOHEADER starts with its size
            }
            image[11] = (byte) widths[i]; // so tests can tell the images apart
            b.put(image);
        }
        return b.array();
    }

    @Test
    void recognisesIconFilesAndNothingElse() {
        assertTrue(Icons.isIco(ico(new boolean[] {true}, 32)));
        assertFalse(Icons.isIco(PNG_HEAD));
        assertFalse(Icons.isIco(new byte[0]));
        assertFalse(Icons.isIco(null));
        assertFalse(Icons.isIco("this is not an icon".getBytes()));
    }

    @Test
    void readsEveryEntryAndTreatsZeroAs256() {
        var entries = Icons.entries(ico(new boolean[] {false, false, true}, 16, 48, 256));
        assertEquals(3, entries.size());
        assertEquals(16, entries.get(0).width());
        assertEquals(48, entries.get(1).width());
        assertEquals(256, entries.get(2).width());
        assertEquals(256, entries.get(2).height());
    }

    @Test
    void picksTheSmallestImageThatIsBigEnoughSoItIsNeverScaledUp() {
        byte[] icon = ico(new boolean[] {false, false, false, false}, 16, 32, 48, 256);
        assertEquals(16, Icons.best(icon, 16).width());
        assertEquals(32, Icons.best(icon, 32).width());
        assertEquals(48, Icons.best(icon, 40).width());   // no 40: take 48 and shrink slightly
        assertEquals(256, Icons.best(icon, 100).width());
        assertEquals(256, Icons.best(icon, 999).width()); // nothing big enough: the largest
        assertEquals(16, Icons.best(icon, 1).width());
    }

    @Test
    void returnsTheExactBytesOfTheChosenImage() {
        byte[] icon = ico(new boolean[] {false, true}, 24, 64);
        byte[] image = Icons.image(icon, Icons.best(icon, 64));
        assertEquals(12, image.length);
        assertEquals(64, image[11]);
        assertTrue(Icons.isPng(image, 0));
        assertFalse(Icons.isPng(Icons.image(icon, Icons.best(icon, 24)), 0));
    }

    @Test
    void largestPngIgnoresBitmapEntries() {
        byte[] icon = ico(new boolean[] {false, true, false, true}, 16, 128, 200, 256);
        assertEquals((byte) 0, Icons.largestPng(icon)[11]); // the 256 image: width byte truncates to 0 in image[11]
        assertNull(Icons.largestPng(ico(new boolean[] {false, false}, 16, 32)));
        assertNull(Icons.largestPng("not an icon".getBytes()));
    }

    @Test
    void damagedFilesGiveNothingInsteadOfAnException() {
        byte[] good = ico(new boolean[] {true, true}, 32, 64);
        // truncated in the middle of the directory
        assertEquals(0, Icons.entries(java.util.Arrays.copyOf(good, 12)).size());
        // truncated so the last image points past the end: that entry is skipped, the first survives
        assertEquals(1, Icons.entries(java.util.Arrays.copyOf(good, good.length - 6)).size());
        // header says 60000 images but the file has two
        byte[] lying = good.clone();
        lying[4] = (byte) 0x60;
        lying[5] = (byte) 0xEA;
        assertEquals(2, Icons.entries(lying).size());
        assertNull(Icons.best(new byte[] {0, 0, 1, 0, 0, 0}, 32));
        assertArrayEquals(new byte[0], Icons.image(good, new Icons.Entry(1, 1, 6, 0)));
    }

    // ---- bitmap entries become PNG ----

    /** A bitmap icon entry: header (height doubled), bottom-up BGR(A) rows padded to 4 bytes, then the AND mask. */
    private static byte[] bitmapEntry(int width, int height, int bits, int[][] rgbaTopDown, boolean[][] maskTransparentTopDown) {
        int colourStride = ((width * bits + 31) / 32) * 4;
        int maskStride = ((width + 31) / 32) * 4;
        ByteBuffer b = ByteBuffer.allocate(40 + colourStride * height + maskStride * height).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(40).putInt(width).putInt(height * 2).putShort((short) 1).putShort((short) bits).putInt(0)
                .putInt(0).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = height - 1; y >= 0; y--) {
            int start = b.position();
            for (int x = 0; x < width; x++) {
                int argb = rgbaTopDown[y][x]; // 0xAARRGGBB
                b.put((byte) argb).put((byte) (argb >> 8)).put((byte) (argb >> 16));
                if (bits == 32) {
                    b.put((byte) (argb >>> 24));
                }
            }
            b.position(start + colourStride);
        }
        for (int y = height - 1; y >= 0; y--) {
            byte[] row = new byte[maskStride];
            for (int x = 0; x < width; x++) {
                if (maskTransparentTopDown != null && maskTransparentTopDown[y][x]) {
                    row[x / 8] |= (byte) (0x80 >> (x % 8));
                }
            }
            b.put(row);
        }
        return b.array();
    }

    private static byte[] icoWith(byte[] image, int width) {
        ByteBuffer b = ByteBuffer.allocate(22 + image.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) 1);
        b.put((byte) width).put((byte) width).put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32)
                .putInt(image.length).putInt(22).put(image);
        return b.array();
    }

    private static int[][] decode(byte[] png) throws Exception {
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        int[][] pixels = new int[image.getHeight()][image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                pixels[y][x] = image.getRGB(x, y);
            }
        }
        return pixels;
    }

    @Test
    void a32BitBitmapEntryBecomesAPngWithTheSamePixelsTheRightWayUp() throws Exception {
        int[][] source = {
                {0xFFFF0000, 0x8000FF00, 0x000000FF},  // top row: opaque red, half-transparent green, fully transparent blue
                {0xFF0000FF, 0xFFFFFFFF, 0x40123456}}; // bottom row
        byte[] ico = icoWith(bitmapEntry(3, 2, 32, source, null), 3);
        byte[] png = Icons.asPng(ico, Icons.entries(ico).get(0));
        assertTrue(Icons.isPng(png, 0));
        int[][] decoded = decode(png);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                int expected = source[y][x];
                int actual = decoded[y][x];
                assertEquals(expected >>> 24, actual >>> 24, "alpha at " + x + "," + y);
                if ((expected >>> 24) != 0) { // colour of a fully transparent pixel is meaningless
                    assertEquals(expected & 0xFFFFFF, actual & 0xFFFFFF, "colour at " + x + "," + y);
                }
            }
        }
    }

    @Test
    void a24BitBitmapEntryTakesItsTransparencyFromTheMask() throws Exception {
        int[][] source = {{0xFF112233, 0xFF445566}, {0xFF778899, 0xFFAABBCC}};
        boolean[][] transparent = {{false, true}, {true, false}};
        byte[] ico = icoWith(bitmapEntry(2, 2, 24, source, transparent), 2);
        int[][] decoded = decode(Icons.asPng(ico, Icons.entries(ico).get(0)));
        assertEquals(0xFF, decoded[0][0] >>> 24);
        assertEquals(0x112233, decoded[0][0] & 0xFFFFFF);
        assertEquals(0, decoded[0][1] >>> 24);
        assertEquals(0, decoded[1][0] >>> 24);
        assertEquals(0xFF, decoded[1][1] >>> 24);
        assertEquals(0xAABBCC, decoded[1][1] & 0xFFFFFF);
    }

    @Test
    void a32BitEntryWithNoAlphaAtAllFallsBackToTheMask() throws Exception {
        int[][] source = {{0x00112233, 0x00445566}}; // alpha 0 everywhere: an old-style icon relying on the mask
        boolean[][] transparent = {{false, true}};
        byte[] ico = icoWith(bitmapEntry(2, 1, 32, source, transparent), 2);
        int[][] decoded = decode(Icons.asPng(ico, Icons.entries(ico).get(0)));
        assertEquals(0xFF, decoded[0][0] >>> 24);
        assertEquals(0, decoded[0][1] >>> 24);
    }

    @Test
    void pngEntriesPassThroughAndUnconvertibleBitmapsGiveNull() {
        byte[] pngBytes = Icons.encodePng(1, 1, new byte[] {1, 2, 3, 4});
        byte[] icoPng = icoWith(pngBytes, 1);
        assertArrayEquals(pngBytes, Icons.asPng(icoPng, Icons.entries(icoPng).get(0)));

        byte[] indexed = bitmapEntry(2, 2, 24, new int[][] {{0, 0}, {0, 0}}, null);
        indexed[14] = 8; // claim 8 bits per pixel (a palette image): not supported
        byte[] icoIndexed = icoWith(indexed, 2);
        assertNull(Icons.asPng(icoIndexed, Icons.entries(icoIndexed).get(0)));
        byte[] truncated = java.util.Arrays.copyOf(bitmapEntry(4, 4, 32, new int[4][4], null), 60);
        byte[] icoTruncated = icoWith(truncated, 4);
        assertNull(Icons.asPng(icoTruncated, Icons.entries(icoTruncated).get(0)));
    }
}
