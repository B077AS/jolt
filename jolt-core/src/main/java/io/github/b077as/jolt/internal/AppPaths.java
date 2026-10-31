package io.github.b077as.jolt.internal;

/** Checks for addresses that stay inside the app. */
public final class AppPaths {

    private static final int MAX_LENGTH = 2048;

    private AppPaths() {}

    /**
     * Whether {@code path} is an address inside the app: it starts with one {@code /} (so it cannot name another host, as
     * {@code //host} or {@code /\host} would) and holds no control characters or backslashes.
     */
    public static boolean isAppPath(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/' || path.length() > MAX_LENGTH) {
            return false;
        }
        if (path.length() > 1 && (path.charAt(1) == '/' || path.charAt(1) == '\\')) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\\') {
                return false;
            }
        }
        return true;
    }
}
