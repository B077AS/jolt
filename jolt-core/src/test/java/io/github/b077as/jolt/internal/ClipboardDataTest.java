package io.github.b077as.jolt.internal;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipboardDataTest {

    private static ClipboardData.Image picture() {
        byte[] rgba = new byte[3 * 2 * 4];
        for (int i = 0; i < rgba.length; i++) {
            rgba[i] = (byte) (i * 11 + 3);
        }
        return new ClipboardData.Image(3, 2, rgba);
    }

    // ---- PNG ----

    @Test
    void aPngWeEncodedIsRecognisedAndDecodesBackToTheSamePixels() {
        ClipboardData.Image image = picture();
        byte[] png = ClipboardData.encodePng(image);
        assertArrayEquals(new int[] {3, 2}, ClipboardData.pngSize(png));
        ClipboardData.Image back = ClipboardData.decodePng(png);
        assertNotNull(back);
        assertEquals(3, back.width());
        assertEquals(2, back.height());
        assertArrayEquals(image.rgba(), back.rgba());
    }

    @Test
    void decodingUndoesEveryPngFilterType() throws Exception {
        // a 4x5 RGB picture drawn by javax.imageio, which picks its own filters; plus our own unfiltered writer above
        java.awt.image.BufferedImage source = new java.awt.image.BufferedImage(4, 5, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 4; x++) {
                source.setRGB(x, y, (x * 60) << 16 | (y * 50) << 8 | (x * y * 9));
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(source, "png", out);
        ClipboardData.Image decoded = ClipboardData.decodePng(out.toByteArray());
        assertNotNull(decoded);
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 4; x++) {
                int o = (y * 4 + x) * 4;
                int rgb = (decoded.rgba()[o] & 0xFF) << 16 | (decoded.rgba()[o + 1] & 0xFF) << 8 | (decoded.rgba()[o + 2] & 0xFF);
                assertEquals(source.getRGB(x, y) & 0xFFFFFF, rgb, "pixel " + x + "," + y);
                assertEquals(0xFF, decoded.rgba()[o + 3] & 0xFF);
            }
        }
    }

    @Test
    void thingsThatAreNotPngsAreRejected() {
        assertNull(ClipboardData.pngSize(null));
        assertNull(ClipboardData.pngSize(new byte[10]));
        assertNull(ClipboardData.pngSize("GIF89a not a png at all, long enough to pass the length test".getBytes(StandardCharsets.US_ASCII)));
        byte[] png = ClipboardData.encodePng(picture());
        byte[] zeroWidth = png.clone();
        ByteBuffer.wrap(zeroWidth).order(ByteOrder.BIG_ENDIAN).putInt(16, 0);
        assertNull(ClipboardData.pngSize(zeroWidth));
        byte[] huge = png.clone();
        ByteBuffer.wrap(huge).order(ByteOrder.BIG_ENDIAN).putInt(16, 70000);
        assertNull(ClipboardData.pngSize(huge));
    }

    @Test
    void aPngThatPromisesMorePixelsThanItHoldsDecodesToNothing() {
        byte[] png = ClipboardData.encodePng(picture());
        ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN).putInt(20, 2000); // height 2000, but the data holds 2 rows
        assertNull(ClipboardData.decodePng(png));
    }

    @Test
    void aPngLargerThanTheDecodeLimitIsNotDecoded() {
        byte[] png = ClipboardData.encodePng(picture());
        ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN).putInt(16, 60000).putInt(20, 60000);
        assertNull(ClipboardData.decodePng(png));
    }

    @Test
    void trimPngDropsPaddingAndRejectsTruncatedData() {
        byte[] png = ClipboardData.encodePng(picture());
        byte[] padded = Arrays.copyOf(png, png.length + 37);
        assertArrayEquals(png, ClipboardData.trimPng(padded));
        assertNull(ClipboardData.trimPng(Arrays.copyOf(png, png.length - 5)));
        byte[] lying = png.clone();
        ByteBuffer.wrap(lying).order(ByteOrder.BIG_ENDIAN).putInt(33, 0x7FFFFFF0); // first IDAT claims a huge length
        assertNull(ClipboardData.trimPng(lying));
    }

    // ---- Windows bitmaps ----

    @Test
    void aV5BitmapWeBuildReadsBackAsTheSamePicture() {
        ClipboardData.Image image = picture();
        byte[] dib = ClipboardData.rgbaToDibV5(image);
        ClipboardData.Image back = ClipboardData.dibToRgba(dib, dib.length);
        assertNotNull(back);
        assertArrayEquals(image.rgba(), back.rgba());
    }

    private static byte[] plainDib(int width, int height, int bits, boolean topDown) {
        int stride = (width * bits + 31) / 32 * 4;
        byte[] dib = new byte[40 + stride * Math.abs(height)];
        ByteBuffer b = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(40).putInt(width).putInt(topDown ? -height : height).putShort((short) 1).putShort((short) bits).putInt(0);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int p = 40 + y * stride + x * bits / 8;
                dib[p] = (byte) 30;      // blue
                dib[p + 1] = (byte) (y == 0 ? 20 : 120); // green: shows which row is which
                dib[p + 2] = (byte) 10;  // red
                if (bits == 32) {
                    dib[p + 3] = 0;      // alpha left at zero, as screenshots do
                }
            }
        }
        return dib;
    }

    @Test
    void anOrdinary24BitBitmapIsReadBottomUpAndOpaque() {
        ClipboardData.Image image = ClipboardData.dibToRgba(plainDib(3, 2, 24, false), 40 + 12 * 2);
        assertNotNull(image);
        // stored row 0 is the BOTTOM row of the picture
        assertEquals(120, image.rgba()[1] & 0xFF, "top row comes from the last stored row");
        assertEquals(20, image.rgba()[3 * 4 + 1] & 0xFF);
        assertEquals(10, image.rgba()[0] & 0xFF);
        assertEquals(30, image.rgba()[2] & 0xFF);
        assertEquals(0xFF, image.rgba()[3] & 0xFF);
    }

    @Test
    void aThirtyTwoBitBitmapWithoutMasksIsOpaqueEvenThoughItsAlphaBytesAreZero() {
        byte[] dib = plainDib(2, 2, 32, true);
        ClipboardData.Image image = ClipboardData.dibToRgba(dib, dib.length);
        assertNotNull(image);
        assertEquals(20, image.rgba()[1] & 0xFF, "top-down: stored row 0 is the top");
        assertEquals(0xFF, image.rgba()[3] & 0xFF);
    }

    @Test
    void bitmapsWithOutOfRangeOrUnsupportedHeadersAreRefused() {
        byte[] good = plainDib(3, 2, 24, false);
        assertNull(ClipboardData.dibToRgba(good, 40 + 5), "pixel data shorter than the header promises");
        assertNull(ClipboardData.dibToRgba(good, 20));
        byte[] palette = good.clone();
        ByteBuffer.wrap(palette).order(ByteOrder.LITTLE_ENDIAN).putShort(14, (short) 8);
        assertNull(ClipboardData.dibToRgba(palette, palette.length));
        byte[] huge = good.clone();
        ByteBuffer.wrap(huge).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 100000).putInt(8, 100000);
        assertNull(ClipboardData.dibToRgba(huge, huge.length));
        byte[] negative = good.clone();
        ByteBuffer.wrap(negative).order(ByteOrder.LITTLE_ENDIAN).putInt(4, -5);
        assertNull(ClipboardData.dibToRgba(negative, negative.length));
        byte[] minValue = good.clone();
        ByteBuffer.wrap(minValue).order(ByteOrder.LITTLE_ENDIAN).putInt(8, Integer.MIN_VALUE);
        assertNull(ClipboardData.dibToRgba(minValue, minValue.length));
        byte[] compressed = good.clone();
        ByteBuffer.wrap(compressed).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 1); // BI_RLE8
        assertNull(ClipboardData.dibToRgba(compressed, compressed.length));
    }

    // ---- HTML ----

    @Test
    void cfHtmlRoundTripsIncludingNonAscii() {
        String fragment = "<b>héllo ☃</b> & more";
        byte[] block = ClipboardData.cfHtml(fragment);
        assertEquals(0, block[block.length - 1], "NUL terminated");
        assertEquals(fragment, ClipboardData.cfHtmlFragment(block));
    }

    @Test
    void cfHtmlOffsetsThatPointOutsideTheDataAreRejected() {
        byte[] block = ClipboardData.cfHtml("<i>x</i>");
        String text = new String(block, StandardCharsets.UTF_8);
        String lying = text.replace("EndFragment:", "EndFragment:9");
        assertNull(ClipboardData.cfHtmlFragment(lying.getBytes(StandardCharsets.UTF_8)));
        assertNull(ClipboardData.cfHtmlFragment("no header at all".getBytes(StandardCharsets.UTF_8)));
        assertNull(ClipboardData.cfHtmlFragment(new byte[0]));
        String backwards = "Version:0.9\r\nStartFragment:0000000050\r\nEndFragment:0000000020\r\n<html>" + "x".repeat(80);
        assertNull(ClipboardData.cfHtmlFragment(backwards.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void htmlTargetBytesAreDecodedByTheirByteOrderMark() {
        assertEquals("<p>ü</p>", ClipboardData.decodeHtmlBytes("<p>ü</p>".getBytes(StandardCharsets.UTF_8)));
        byte[] utf16 = ("\uFEFF<p>ü</p>").getBytes(StandardCharsets.UTF_16LE);
        // getBytes(UTF_16LE) writes the BOM character as FF FE
        assertEquals("<p>ü</p>", ClipboardData.decodeHtmlBytes(utf16));
        assertEquals("a", ClipboardData.decodeHtmlBytes("a\0b".getBytes(StandardCharsets.UTF_8)));
    }

    // ---- text and files ----

    @Test
    void textIsCutAtTheFirstNul() {
        assertEquals("abc", ClipboardData.untilNul("abc\0def"));
        assertEquals("abc", ClipboardData.untilNul("abc"));
        assertTrue(ClipboardData.hasNul("a\0"));
        assertFalse(ClipboardData.hasNul("a"));
    }

    @Test
    void networkAndRelativePathsAreNotLocalAbsolutePaths() {
        assertFalse(ClipboardData.isLocalAbsolutePath(null));
        assertFalse(ClipboardData.isLocalAbsolutePath(Path.of("relative", "file.txt")));
        if (java.io.File.separatorChar == '\\') {
            assertTrue(ClipboardData.isLocalAbsolutePath(Path.of("C:\\Users\\a\\b.txt")));
            assertFalse(ClipboardData.isLocalAbsolutePath(Path.of("\\\\server\\share\\x.txt")));
        } else {
            assertTrue(ClipboardData.isLocalAbsolutePath(Path.of("/home/a/b.txt")));
        }
    }

    @Test
    void uriListsKeepOnlyLocalFileAddressesAndDecodeThem() {
        boolean windows = java.io.File.separatorChar == '\\';
        String a = windows ? "file:///C:/Users/a/My%20File.txt" : "file:///home/a/My%20File.txt";
        String b = windows ? "file://localhost/C:/b.txt" : "file://localhost/b.txt";
        String list = "# a comment\r\n" + a + "\r\n\r\nhttp://evil.example/x\r\nfile://server/share/x.txt\r\nftp://h/f\r\nfile:///x?query\r\n"
                + b + "\n";
        List<Path> files = ClipboardData.parseUriList(list);
        assertEquals(2, files.size(), files.toString());
        assertTrue(files.get(0).toString().endsWith("My File.txt"));
        assertTrue(files.get(1).toString().endsWith("b.txt"));
    }

    @Test
    void uriListsDoNotEscapeTheirDirectoryWithDotDot() {
        boolean windows = java.io.File.separatorChar == '\\';
        List<Path> files = ClipboardData.parseUriList(windows ? "file:///C:/a/../b.txt" : "file:///a/../b.txt");
        assertEquals(1, files.size());
        assertFalse(files.get(0).toString().contains(".."));
    }

    @Test
    void aHugeUriListIsCappedAtTheFileLimit() {
        boolean windows = java.io.File.separatorChar == '\\';
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < ClipboardData.MAX_FILES + 100; i++) {
            list.append(windows ? "file:///C:/f" : "file:///f").append(i).append("\r\n");
        }
        assertEquals(ClipboardData.MAX_FILES, ClipboardData.parseUriList(list.toString()).size());
    }

    @Test
    void writtenUriListsParseBackToTheSamePaths() {
        boolean windows = java.io.File.separatorChar == '\\';
        List<Path> paths = windows
                ? List.of(Path.of("C:\\a b\\c.txt"), Path.of("C:\\ü\\d#e.txt"))
                : List.of(Path.of("/a b/c.txt"), Path.of("/ü/d#e.txt"));
        assertEquals(paths, ClipboardData.parseUriList(ClipboardData.uriList(paths)));
    }
}
