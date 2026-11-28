package io.github.b077as.jolt.internal;

import java.util.Locale;
import java.util.Set;

/** Turns a file name suggested by a page or a server into one that is safe to put in a folder on Windows and Linux. */
public final class FileNames {

    private static final String FALLBACK = "download";
    /** In UTF-8 bytes: Linux file systems allow 255, and the web view may add a temporary suffix. */
    private static final int MAX_BYTES = 200;
    /** Characters Windows does not allow in a name; the separators are among them. */
    private static final String FORBIDDEN = "<>:\"/\\|?*";
    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul",
            "com0", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9", "com¹", "com²", "com³",
            "lpt0", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9", "lpt¹", "lpt²", "lpt³",
            "conin$", "conout$");

    private FileNames() {}

    public static String safe(String suggested) {
        if (suggested == null) {
            return FALLBACK;
        }
        // only the last segment: a suggestion with a path in it must not choose the folder
        String last = suggested.substring(Math.max(suggested.lastIndexOf('/'), suggested.lastIndexOf('\\')) + 1);
        StringBuilder clean = new StringBuilder();
        last.codePoints().forEach(c -> {
            int type = Character.getType(c);
            // controls, and invisible format characters such as the right-to-left override, which disguises an extension
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                return;
            }
            clean.appendCodePoint(c < 128 && FORBIDDEN.indexOf(c) >= 0 ? '_' : c);
        });
        String name = trim(clean.toString());
        if (name.isEmpty()) {
            return FALLBACK;
        }
        int dot = name.indexOf('.');
        String stem = (dot < 0 ? name : name.substring(0, dot)).strip().toLowerCase(Locale.ROOT);
        if (RESERVED.contains(stem)) {
            name = "_" + name;
        }
        return shorten(name);
    }

    /** No leading dots or spaces (a hidden file), no trailing ones (Windows drops them silently). */
    private static String trim(String name) {
        int start = 0;
        int end = name.length();
        while (start < end && (name.charAt(start) == '.' || Character.isWhitespace(name.charAt(start)))) {
            start++;
        }
        while (end > start && (name.charAt(end - 1) == '.' || Character.isWhitespace(name.charAt(end - 1)))) {
            end--;
        }
        return name.substring(start, end);
    }

    /** Cuts to {@link #MAX_BYTES}, keeping the extension if it is a normal one. */
    private static String shorten(String name) {
        if (bytes(name) <= MAX_BYTES) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && bytes(name.substring(dot)) <= 20 ? name.substring(dot) : "";
        String stem = name.substring(0, name.length() - extension.length());
        int room = MAX_BYTES - bytes(extension);
        StringBuilder cut = new StringBuilder();
        for (int i = 0; i < stem.length(); ) {
            int c = stem.codePointAt(i);
            int size = bytes(Character.toString(c));
            if (size > room) {
                break;
            }
            room -= size;
            cut.appendCodePoint(c);
            i += Character.charCount(c);
        }
        return trim(cut + extension);
    }

    private static int bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
