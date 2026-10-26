package io.github.b077as.jolt.internal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads Windows {@code .ico} files, which hold the same icon pre-drawn at several sizes. Giving the operating system the
 * image that already has the size it wants means nothing has to be scaled, which is what makes small icons look crisp
 * instead of grainy.
 *
 * <p>Layout: a 6-byte header (reserved 0, type 1, image count), then one 16-byte directory entry per image (width, height,
 * colours, reserved, planes, bits per pixel, byte length, byte offset), then the images, each either a bitmap or a PNG.
 */
public final class Icons {

    /** One image inside an icon file. */
    public record Entry(int width, int height, int offset, int length) {}

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G'};

    private Icons() {}

    /** Whether {@code data} starts like an icon file (reserved 0, type 1) rather than a PNG or something else. */
    public static boolean isIco(byte[] data) {
        return data != null && data.length >= 6 && data[0] == 0 && data[1] == 0 && data[2] == 1 && data[3] == 0;
    }

    public static boolean isPng(byte[] data, int offset) {
        return data != null && offset >= 0 && offset + PNG_SIGNATURE.length <= data.length
                && Arrays.equals(Arrays.copyOfRange(data, offset, offset + PNG_SIGNATURE.length), PNG_SIGNATURE);
    }

    /**
     * The images in an icon file, in file order. Entries that point outside the file are skipped, and a damaged or
     * non-icon input gives an empty list rather than an exception.
     */
    public static List<Entry> entries(byte[] ico) {
        if (!isIco(ico)) {
            return List.of();
        }
        ByteBuffer b = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
        int count = b.getShort(4) & 0xFFFF;
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int at = 6 + i * 16;
            if (at + 16 > ico.length) {
                break;
            }
            int width = (ico[at] & 0xFF) == 0 ? 256 : ico[at] & 0xFF;   // a stored 0 means 256
            int height = (ico[at + 1] & 0xFF) == 0 ? 256 : ico[at + 1] & 0xFF;
            long length = b.getInt(at + 8) & 0xFFFFFFFFL;
            long offset = b.getInt(at + 12) & 0xFFFFFFFFL;
            if (length > 0 && offset >= 6 && offset + length <= ico.length) {
                result.add(new Entry(width, height, (int) offset, (int) length));
            }
        }
        return result;
    }

    /**
     * The image to use when about {@code desiredWidth} pixels are wanted: the smallest one that is at least that wide (so it
     * is only ever scaled down, never up), or the largest one if none is big enough. {@code null} if there are no images.
     */
    public static Entry best(byte[] ico, int desiredWidth) {
        Entry bestFit = null;
        Entry largest = null;
        for (Entry e : entries(ico)) {
            if (largest == null || e.width() > largest.width()) {
                largest = e;
            }
            if (e.width() >= desiredWidth && (bestFit == null || e.width() < bestFit.width())) {
                bestFit = e;
            }
        }
        return bestFit != null ? bestFit : largest;
    }

    /** The bytes of one image. A PNG entry is a complete PNG file. */
    public static byte[] image(byte[] ico, Entry entry) {
        return Arrays.copyOfRange(ico, entry.offset(), entry.offset() + entry.length());
    }

    /**
     * A single PNG for platforms that decode PNG but not icon files: the largest PNG image in the icon file. {@code null}
     * if it holds none (all bitmaps).
     */
    public static byte[] largestPng(byte[] ico) {
        Entry largest = null;
        for (Entry e : entries(ico)) {
            if (isPng(ico, e.offset()) && (largest == null || e.width() > largest.width())) {
                largest = e;
            }
        }
        return largest == null ? null : image(ico, largest);
    }

    /**
     * One image as PNG data. PNG entries are returned as they are. Classic bitmap entries (32 or 24 bits per pixel) are
     * converted, because Windows draws an icon made from a bitmap entry as the generic "application" placeholder on the
     * taskbar, while the same picture as a PNG shows up correctly. Returns {@code null} for a bitmap it cannot convert
     * (palette or compressed formats); callers then use the raw bytes.
     */
    public static byte[] asPng(byte[] ico, Entry entry) {
        byte[] raw = image(ico, entry);
        if (isPng(raw, 0)) {
            return raw;
        }
        if (raw.length < 40) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = b.getInt(0);
        int width = b.getInt(4);
        int height = b.getInt(8) / 2; // the header stores twice the height: the colour rows, then the transparency mask
        int bits = b.getShort(14);
        int compression = b.getInt(16);
        if (headerSize < 40 || width <= 0 || height <= 0 || width > 1024 || height > 1024 || b.getShort(12) != 1
                || compression != 0 || (bits != 32 && bits != 24)) {
            return null;
        }
        int colourStride = ((width * bits + 31) / 32) * 4;
        int maskStride = ((width + 31) / 32) * 4;
        int colourStart = headerSize;
        int maskStart = colourStart + colourStride * height;
        if (maskStart > raw.length) {
            return null;
        }
        boolean hasMask = maskStart + maskStride * height <= raw.length;

        // Rows are stored bottom-up as B, G, R (and A). Collect top-down RGBA.
        byte[] rgba = new byte[width * height * 4];
        boolean anyAlpha = false;
        for (int y = 0; y < height; y++) {
            int row = colourStart + colourStride * (height - 1 - y);
            for (int x = 0; x < width; x++) {
                int p = row + x * (bits / 8);
                int o = (y * width + x) * 4;
                rgba[o] = raw[p + 2];
                rgba[o + 1] = raw[p + 1];
                rgba[o + 2] = raw[p];
                rgba[o + 3] = bits == 32 ? raw[p + 3] : (byte) 0xFF;
                anyAlpha |= bits == 32 && raw[p + 3] != 0;
            }
        }
        if ((bits == 24 || !anyAlpha) && hasMask) { // no usable alpha channel: transparency comes from the mask (1 = transparent)
            for (int y = 0; y < height; y++) {
                int row = maskStart + maskStride * (height - 1 - y);
                for (int x = 0; x < width; x++) {
                    boolean transparent = (raw[row + x / 8] & (0x80 >> (x % 8))) != 0;
                    rgba[(y * width + x) * 4 + 3] = transparent ? 0 : (byte) 0xFF;
                }
            }
        }
        return encodePng(width, height, rgba);
    }

    /** A minimal PNG writer: 8-bit RGBA, no interlacing, every scanline unfiltered. */
    static byte[] encodePng(int width, int height, byte[] rgba) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        ByteBuffer header = ByteBuffer.allocate(13);
        header.putInt(width).putInt(height).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        writeChunk(out, "IHDR", header.array());

        byte[] scanlines = new byte[(width * 4 + 1) * height]; // each row starts with filter type 0 (none)
        for (int y = 0; y < height; y++) {
            System.arraycopy(rgba, y * width * 4, scanlines, y * (width * 4 + 1) + 1, width * 4);
        }
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(scanlines);
        deflater.finish();
        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        writeChunk(out, "IDAT", compressed.toByteArray());
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void writeChunk(java.io.ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(name);
        out.writeBytes(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(name);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
