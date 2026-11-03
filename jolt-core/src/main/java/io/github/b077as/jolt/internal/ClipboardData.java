package io.github.b077as.jolt.internal;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The format work behind the clipboard that does not need the operating system: limits, validation and the conversions
 * between what applications put on a clipboard and what Jolt hands out. Pure logic, so it is unit tested everywhere.
 *
 * <p>Everything read from a clipboard is written by another program and must be treated as hostile. The rules here are:
 * every length is checked against the data actually present before it is used, sizes are capped, and anything that does
 * not parse is dropped rather than guessed at.
 */
public final class ClipboardData {

    /** The most bytes of any one clipboard format Jolt reads or writes. */
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    /** The most files in one list. */
    public static final int MAX_FILES = 4096;
    /** The most pixels in an image Jolt decodes itself (to build a bitmap format); larger PNGs are passed through untouched. */
    public static final long MAX_DECODED_PIXELS = 16L * 1024 * 1024;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    private ClipboardData() {}

    /** A decoded picture: top-down, 8-bit RGBA. */
    public record Image(int width, int height, byte[] rgba) {}

    // ---- text ----

    /** Whether {@code text} can be handed to the clipboard: the native text formats end at the first NUL, so one inside would cut it short. */
    public static boolean hasNul(String text) {
        return text.indexOf('\0') >= 0;
    }

    /** {@code text} up to its first NUL (the native formats are NUL terminated, and a hostile owner may put more after it). */
    public static String untilNul(String text) {
        int nul = text.indexOf('\0');
        return nul < 0 ? text : text.substring(0, nul);
    }

    // ---- files ----

    /**
     * Whether {@code path} is an absolute path on this machine. Network paths ({@code \\server\share}) and device paths
     * ({@code \\?\}, {@code \\.\}) are refused: touching one makes Windows send the user's credentials to a server somebody else
     * named, so a path that came off a clipboard must not be able to point there.
     */
    public static boolean isLocalAbsolutePath(Path path) {
        if (path == null || !path.isAbsolute()) {
            return false;
        }
        String text = path.toString();
        return text.indexOf('\0') < 0 && !text.startsWith("\\\\") && !text.startsWith("//") && text.length() <= 32767;
    }

    /**
     * The local files named by a {@code text/uri-list} (RFC 2483). Comments, blank lines, other schemes, remote hosts and
     * anything that does not turn into an absolute local path are skipped; at most {@link #MAX_FILES} are returned.
     */
    public static List<Path> parseUriList(String text) {
        List<Path> files = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            if (files.size() >= MAX_FILES) {
                break;
            }
            String entry = line.strip();
            if (entry.isEmpty() || entry.charAt(0) == '#') {
                continue;
            }
            try {
                URI uri = new URI(entry);
                String authority = uri.getRawAuthority();
                if (!"file".equalsIgnoreCase(uri.getScheme())
                        || (authority != null && !authority.isEmpty() && !authority.equalsIgnoreCase("localhost"))
                        || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                    continue;
                }
                Path path = Path.of(new URI("file", null, uri.getPath(), null)).normalize();
                if (isLocalAbsolutePath(path)) {
                    files.add(path);
                }
            } catch (java.net.URISyntaxException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
                // not a usable file address: skip it
            }
        }
        return files;
    }

    /** {@code files} as a {@code text/uri-list}: one {@code file:} address per line, each line ended by CRLF. */
    public static String uriList(List<Path> files) {
        StringBuilder out = new StringBuilder();
        for (Path file : files) {
            out.append(file.toUri().toASCIIString()).append("\r\n");
        }
        return out.toString();
    }

    // ---- HTML ----

    /** Windows' "HTML Format" (CF_HTML): a header of byte offsets, then the document with the fragment marked. UTF-8, NUL terminated. */
    public static byte[] cfHtml(String fragment) {
        String before = "<html>\r\n<body>\r\n<!--StartFragment-->";
        String after = "<!--EndFragment-->\r\n</body>\r\n</html>";
        int headerLength = header(0, 0, 0, 0).length();
        int startHtml = headerLength;
        int startFragment = startHtml + before.length();
        int endFragment = startFragment + fragment.getBytes(StandardCharsets.UTF_8).length;
        int endHtml = endFragment + after.length();
        String all = header(startHtml, endHtml, startFragment, endFragment) + before + fragment + after;
        byte[] utf8 = all.getBytes(StandardCharsets.UTF_8);
        return java.util.Arrays.copyOf(utf8, utf8.length + 1); // trailing NUL
    }

    private static String header(int startHtml, int endHtml, int startFragment, int endFragment) {
        return String.format(Locale.ROOT, "Version:0.9\r\nStartHTML:%010d\r\nEndHTML:%010d\r\nStartFragment:%010d\r\nEndFragment:%010d\r\n",
                startHtml, endHtml, startFragment, endFragment);
    }

    /** The marked fragment of a CF_HTML block, or {@code null} if its offsets do not describe a range inside the data. */
    public static String cfHtmlFragment(byte[] data) {
        int length = data.length;
        while (length > 0 && data[length - 1] == 0) {
            length--;
        }
        // the header is plain ASCII at the start; stop reading it at the first byte that is not
        int headerEnd = 0;
        while (headerEnd < length && headerEnd < 1024 && (data[headerEnd] & 0x80) == 0 && data[headerEnd] != '<') {
            headerEnd++;
        }
        String header = new String(data, 0, headerEnd, StandardCharsets.US_ASCII);
        long start = number(header, "StartFragment:");
        long end = number(header, "EndFragment:");
        if (start < 0 || end < start || end > length) {
            return null;
        }
        return new String(data, (int) start, (int) (end - start), StandardCharsets.UTF_8);
    }

    private static long number(String header, String key) {
        int at = header.indexOf(key);
        if (at < 0) {
            return -1;
        }
        int from = at + key.length();
        int to = from;
        while (to < header.length() && Character.isDigit(header.charAt(to)) && to - from < 10) {
            to++;
        }
        return to == from ? -1 : Long.parseLong(header.substring(from, to));
    }

    /** Text of a {@code text/html} clipboard target: UTF-16 when it starts with a byte order mark, UTF-8 otherwise. Stops at a NUL. */
    public static String decodeHtmlBytes(byte[] data) {
        String text;
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            text = new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        } else if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            text = new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        } else {
            text = new String(data, StandardCharsets.UTF_8);
        }
        return untilNul(text);
    }

    // ---- images ----

    /** {@code {width, height}} if {@code data} is a PNG whose header makes sense and which is within {@link #MAX_BYTES}; otherwise {@code null}. */
    public static int[] pngSize(byte[] data) {
        if (data == null || data.length < 33 || data.length > MAX_BYTES) {
            return null;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) {
                return null;
            }
        }
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        if (b.getInt(8) != 13 || b.getInt(12) != 0x49484452) { // the first chunk must be IHDR, 13 bytes long
            return null;
        }
        int width = b.getInt(16);
        int height = b.getInt(20);
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535) {
            return null;
        }
        return new int[] {width, height};
    }

    /**
     * {@code data} cut after its IEND chunk (native clipboard blocks are often padded), or {@code null} if it is not a PNG with
     * a sane header, or its chunk lengths run past the data, or it has no IEND.
     */
    public static byte[] trimPng(byte[] data) {
        if (pngSize(data) == null) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        long at = 8;
        while (at + 12 <= data.length) {
            long chunkLength = b.getInt((int) at) & 0xFFFFFFFFL;
            int type = b.getInt((int) at + 4);
            long next = at + 12 + chunkLength;
            if (next > data.length) {
                return null;
            }
            at = next;
            if (type == 0x49454E44) { // IEND
                return java.util.Arrays.copyOf(data, (int) at);
            }
        }
        return null;
    }

    /** A PNG for {@code image}. */
    public static byte[] encodePng(Image image) {
        return Icons.encodePng(image.width(), image.height(), image.rgba());
    }

    /**
     * Decodes a PNG that is 8 bits per channel, RGB or RGBA, not interlaced, and at most {@link #MAX_DECODED_PIXELS} pixels.
     * Returns {@code null} for anything else (the caller then does without the bitmap form of the picture). Decompression is
     * bounded by the size the header promises, so a PNG that lies about it cannot use more memory than that.
     */
    public static Image decodePng(byte[] png) {
        int[] size = pngSize(png);
        if (size == null) {
            return null;
        }
        int width = size[0];
        int height = size[1];
        int depth = png[24] & 0xFF;
        int colorType = png[25] & 0xFF;
        int interlace = png[28] & 0xFF;
        if (depth != 8 || (colorType != 2 && colorType != 6) || interlace != 0 || (long) width * height > MAX_DECODED_PIXELS) {
            return null;
        }
        int channels = colorType == 6 ? 4 : 3;
        int stride = width * channels;
        long expected = (long) (stride + 1) * height;

        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        ByteBuffer b = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN);
        int at = 8;
        while (at + 12 <= png.length) {
            long chunkLength = b.getInt(at) & 0xFFFFFFFFL;
            int type = b.getInt(at + 4);
            if (chunkLength > png.length - at - 12L) {
                return null;
            }
            if (type == 0x49444154) { // IDAT
                idat.write(png, at + 8, (int) chunkLength);
            } else if (type == 0x49454E44) { // IEND
                break;
            }
            at += 12 + (int) chunkLength;
        }

        byte[] raw = new byte[(int) expected];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(idat.toByteArray());
            int filled = 0;
            while (filled < raw.length) {
                int n = inflater.inflate(raw, filled, raw.length - filled);
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                filled += n;
            }
            if (filled != raw.length) {
                return null;
            }
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }

        byte[] rgba = new byte[width * height * 4];
        byte[] previous = new byte[stride];
        byte[] row = new byte[stride];
        for (int y = 0; y < height; y++) {
            int filter = raw[y * (stride + 1)] & 0xFF;
            System.arraycopy(raw, y * (stride + 1) + 1, row, 0, stride);
            for (int i = 0; i < stride; i++) {
                int a = i >= channels ? row[i - channels] & 0xFF : 0;
                int up = previous[i] & 0xFF;
                int c = i >= channels ? previous[i - channels] & 0xFF : 0;
                int add;
                switch (filter) {
                    case 0 -> add = 0;
                    case 1 -> add = a;
                    case 2 -> add = up;
                    case 3 -> add = (a + up) / 2;
                    case 4 -> add = paeth(a, up, c);
                    default -> {
                        return null;
                    }
                }
                row[i] = (byte) (row[i] + add);
            }
            for (int x = 0; x < width; x++) {
                int o = (y * width + x) * 4;
                rgba[o] = row[x * channels];
                rgba[o + 1] = row[x * channels + 1];
                rgba[o + 2] = row[x * channels + 2];
                rgba[o + 3] = channels == 4 ? row[x * channels + 3] : (byte) 0xFF;
            }
            byte[] swap = previous;
            previous = row;
            row = swap;
        }
        return new Image(width, height, rgba);
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    private static final int BI_RGB = 0, BI_BITFIELDS = 3;

    /**
     * A device independent bitmap as the Windows clipboard holds it (a BITMAPINFOHEADER, BITMAPV4HEADER or BITMAPV5HEADER
     * followed by the pixels) turned into RGBA. Handles 24 and 32 bits per pixel, uncompressed or with the usual BGRA masks;
     * returns {@code null} for palette, compressed or oversized bitmaps and for any whose header promises more than the data holds.
     *
     * <p>A 32-bit bitmap without a mask has no defined alpha channel (screenshots leave it at zero), so it is read as opaque.
     */
    public static Image dibToRgba(byte[] dib, int length) {
        if (dib == null || length < 40 || length > dib.length) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(dib, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = b.getInt(0);
        int width = b.getInt(4);
        int rawHeight = b.getInt(8);
        int planes = b.getShort(12);
        int bits = b.getShort(14);
        int compression = b.getInt(16);
        if (headerSize < 40 || headerSize > length || width <= 0 || rawHeight == 0 || rawHeight == Integer.MIN_VALUE || planes != 1
                || (bits != 24 && bits != 32) || (compression != BI_RGB && compression != BI_BITFIELDS)) {
            return null;
        }
        int height = Math.abs(rawHeight);
        boolean topDown = rawHeight < 0;
        if ((long) width * height > MAX_DECODED_PIXELS) {
            return null;
        }
        boolean alphaMask = false;
        int pixelsAt = headerSize;
        if (compression == BI_BITFIELDS) {
            // Masks follow a 40-byte header (three of them) or sit inside a V4/V5 header at offset 40. Only plain BGR(A) is understood.
            if (bits != 32 && bits != 24) {
                return null;
            }
            if (headerSize == 40) {
                if (length < 52) {
                    return null;
                }
                pixelsAt = 52;
            } else if (headerSize < 56) {
                return null;
            }
            int red = b.getInt(40);
            int green = b.getInt(44);
            int blue = b.getInt(48);
            if (red != 0x00FF0000 || green != 0x0000FF00 || blue != 0x000000FF) {
                return null;
            }
            alphaMask = headerSize >= 56 && b.getInt(52) == 0xFF000000;
        }
        int bytesPerPixel = bits / 8;
        long stride = ((long) width * bits + 31) / 32 * 4;
        if (pixelsAt + stride * height > length) {
            return null;
        }
        byte[] rgba = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            long rowStart = pixelsAt + stride * (topDown ? y : height - 1 - y);
            for (int x = 0; x < width; x++) {
                int p = (int) rowStart + x * bytesPerPixel;
                int o = (y * width + x) * 4;
                rgba[o] = dib[p + 2];
                rgba[o + 1] = dib[p + 1];
                rgba[o + 2] = dib[p];
                rgba[o + 3] = bits == 32 && alphaMask ? dib[p + 3] : (byte) 0xFF;
            }
        }
        return new Image(width, height, rgba);
    }

    /** A BITMAPV5HEADER bitmap (32 bits per pixel, alpha in the top byte, rows bottom-up) for {@code image}: the clipboard's CF_DIBV5. */
    public static byte[] rgbaToDibV5(Image image) {
        int width = image.width();
        int height = image.height();
        byte[] dib = new byte[124 + width * height * 4];
        ByteBuffer b = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(124).putInt(width).putInt(height).putShort((short) 1).putShort((short) 32).putInt(BI_BITFIELDS);
        b.putInt(width * height * 4);                       // biSizeImage
        b.putInt(0).putInt(0).putInt(0).putInt(0);          // pixels per metre, colours used and important
        b.putInt(0x00FF0000).putInt(0x0000FF00).putInt(0x000000FF).putInt(0xFF000000); // R, G, B, A masks
        b.putInt(0x73524742);                               // LCS_sRGB
        // the colour space endpoints (36 bytes), gamma (12), intent, profile data and size and reserved stay zero except the intent:
        b.position(108);
        b.putInt(4);                                        // LCS_GM_IMAGES
        for (int y = 0; y < height; y++) {
            int row = 124 + (height - 1 - y) * width * 4;
            for (int x = 0; x < width; x++) {
                int o = (y * width + x) * 4;
                int p = row + x * 4;
                dib[p] = image.rgba()[o + 2];
                dib[p + 1] = image.rgba()[o + 1];
                dib[p + 2] = image.rgba()[o];
                dib[p + 3] = image.rgba()[o + 3];
            }
        }
        return dib;
    }
}
