package io.github.b077as.jolt.internal;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Which addresses {@code Application.openExternal} hands to the operating system. Asking the shell to open a
 * {@code file:} path or a custom scheme can start a program, so only web and mail addresses go through.
 */
public final class ExternalUri {

    private static final Set<String> ALLOWED = Set.of("http", "https", "mailto");

    private ExternalUri() {}

    /** Returns the address as the string to give the shell; throws {@link IllegalArgumentException} if it may not be opened. */
    public static String check(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Only http, https and mailto addresses can be opened, not: " + scheme);
        }
        if (!"mailto".equalsIgnoreCase(scheme) && (uri.getHost() == null || uri.getHost().isBlank())) {
            throw new IllegalArgumentException("The address has no host: " + uri);
        }
        String text = uri.toASCIIString();
        if (text.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new IllegalArgumentException("The address contains control characters");
        }
        return text;
    }
}
