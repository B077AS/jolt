package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.FileDrop;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The message a page sends when the user drops files on it, and the checks that decide whether the application gets them.
 *
 * <p>A web page is never told where a file lives, so the paths come from the platform, not from the page: WebView2 attaches the
 * dropped {@code File} objects to the message (with their paths), WebKitGTK reports the drop to the host window itself. The page
 * only adds where on the page it happened. {@link #accept} then makes sure the two agree: the same number of files with the same
 * names, so a page cannot attach a path of its own, and every path is absolute and local ({@link ClipboardData#isLocalAbsolutePath},
 * no {@code \\server}).
 *
 * <p>Wire format: {@link #PREFIX} and a JSON object {@code {"zone":string|null,"x":int,"y":int,"names":"name\u0000name"}}. Like the
 * frame messages it never reaches the application's message listeners.
 */
public final class FileDropProtocol {

    private FileDropProtocol() {}

    /** Starts every file-drop message. */
    public static final String PREFIX = "\u0001jolt-drop:";

    /** Longest zone name kept. */
    static final int MAX_ZONE = 128;

    /** Largest coordinate accepted; anything above is nonsense and becomes 0. */
    static final int MAX_COORDINATE = 1_000_000;

    /** Whether {@code message} is a file-drop message (and so must not reach the application's listeners). */
    public static boolean isDropMessage(String message) {
        return message.startsWith(PREFIX);
    }

    /** What the page said about a drop. */
    record Claim(String zone, int x, int y, List<String> names) {}

    /** The page's side of {@code message}, or {@code null} if it is not shaped as described above. */
    static Claim parse(String message) {
        if (!isDropMessage(message)) {
            return null;
        }
        Map<String, String> members;
        try {
            members = MiniJson.members(message.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String names = members.get("names");
        if (names == null || !names.startsWith("\"")) {
            return null;
        }
        try {
            String zone = null;
            String rawZone = members.get("zone");
            if (rawZone != null && rawZone.startsWith("\"")) {
                zone = zone(MiniJson.decodeString(rawZone));
            }
            List<String> list = Arrays.asList(MiniJson.decodeString(names).split("\0", -1));
            return new Claim(zone, coordinate(members.get("x")), coordinate(members.get("y")), list);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String zone(String value) {
        if (value.isEmpty() || value.length() > MAX_ZONE) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 0x20 || value.charAt(i) == 0x7f) {
                return null;
            }
        }
        return value;
    }

    private static int coordinate(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 9) {
            return 0;
        }
        try {
            int value = Integer.parseInt(raw);
            return Math.abs(value) > MAX_COORDINATE ? 0 : value;
        } catch (NumberFormatException e) {
            return 0; // a fraction or an exponent: the page rounds, so this is not our page
        }
    }

    /**
     * The drop to hand to the application, or {@code null} if the page's message is malformed or does not match {@code paths}, the
     * files the platform says were dropped.
     */
    public static FileDrop accept(String message, List<Path> paths) {
        Claim claim = parse(message);
        if (claim == null || paths.isEmpty() || paths.size() > ClipboardData.MAX_FILES || claim.names().size() != paths.size()) {
            return null;
        }
        List<String> claimed = new ArrayList<>();
        for (String name : claim.names()) {
            claimed.add(Normalizer.normalize(name, Normalizer.Form.NFC));
        }
        List<String> actual = new ArrayList<>();
        for (Path path : paths) {
            if (!ClipboardData.isLocalAbsolutePath(path) || path.getFileName() == null) {
                return null;
            }
            actual.add(Normalizer.normalize(path.getFileName().toString(), Normalizer.Form.NFC));
        }
        claimed.sort(null);
        actual.sort(null);
        return claimed.equals(actual) ? new FileDrop(paths, claim.zone(), claim.x(), claim.y()) : null;
    }
}
