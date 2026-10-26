package io.github.b077as.jolt;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What a window lets web content do. The page can call every handler you register with {@code onMessage}, so the
 * policy decides which pages may reach that bridge and where the window may navigate.
 *
 * <p>The default, {@link #standard()}, trusts only your own content (what you pass to {@code loadHtml}) plus the origins
 * you list. Everything is a switch: widen the list with {@link #withTrustedOrigins}, turn single protections off with the
 * {@code with...} methods, or use {@link #permissive()} to turn them all off. Set it in
 * {@link WindowOptions#withSecurity} or change it while the window is open with {@link WebWindow#setSecurity}.
 *
 * <p>An <i>origin</i> is scheme, host and port, for example {@code https://example.com} or {@code http://localhost:5173}.
 *
 * @param trustedOrigins   origins trusted in addition to the window's own content
 * @param lockNavigation   block navigation (links, redirects, frames, {@code loadUrl}) to origins that are not trusted.
 *                         On Linux this is also what keeps untrusted frames out of a trusted page, because WebKitGTK
 *                         cannot tell which frame sent a message
 * @param blockNewWindows  block pages from opening new windows ({@code window.open}, {@code target=_blank}); Windows
 *                         only, as WebKitGTK never opens one without the application's help
 * @param denyPermissions  refuse page requests for camera, microphone, location, notifications and the like
 * @param restrictMessages ignore messages sent by pages whose origin is not trusted
 * @param bridgeInSubframes whether {@code window.jolt} also exists in frames inside the page. Read when the window is
 *                          created; changing it later has no effect on that window
 */
public record SecurityPolicy(Set<String> trustedOrigins, boolean lockNavigation, boolean blockNewWindows,
                             boolean denyPermissions, boolean restrictMessages, boolean bridgeInSubframes) {

    /** The origin of content passed to {@code loadHtml}. Always trusted. */
    public static final String APP_ORIGIN = "https://jolt.localhost";

    public SecurityPolicy {
        Set<String> normalized = new LinkedHashSet<>();
        for (String origin : trustedOrigins == null ? Set.<String>of() : trustedOrigins) {
            String o = originOf(origin);
            if (o == null) {
                throw new IllegalArgumentException("not an origin (expected scheme://host[:port]): " + origin);
            }
            normalized.add(o);
        }
        trustedOrigins = Collections.unmodifiableSet(normalized);
    }

    /** Recommended: trust only your own content, and block everything else listed here. */
    public static SecurityPolicy standard() {
        return new SecurityPolicy(Set.of(), true, true, true, true, false);
    }

    /** Every protection off: any page can navigate, open windows, ask for permissions and call your handlers. */
    public static SecurityPolicy permissive() {
        return new SecurityPolicy(Set.of(), false, false, false, false, true);
    }

    /** Adds origins to trust, for example a dev server ({@code http://localhost:5173}) or your own website. */
    public SecurityPolicy withTrustedOrigins(String... origins) {
        Set<String> all = new LinkedHashSet<>(trustedOrigins);
        all.addAll(java.util.Arrays.asList(origins));
        return new SecurityPolicy(all, lockNavigation, blockNewWindows, denyPermissions, restrictMessages, bridgeInSubframes);
    }

    /** Replaces the list of extra trusted origins. */
    public SecurityPolicy withOnlyTrustedOrigins(String... origins) {
        return new SecurityPolicy(new LinkedHashSet<>(java.util.Arrays.asList(origins)), lockNavigation, blockNewWindows, denyPermissions, restrictMessages,
                bridgeInSubframes);
    }

    public SecurityPolicy withLockNavigation(boolean lock) {
        return new SecurityPolicy(trustedOrigins, lock, blockNewWindows, denyPermissions, restrictMessages, bridgeInSubframes);
    }

    public SecurityPolicy withBlockNewWindows(boolean block) {
        return new SecurityPolicy(trustedOrigins, lockNavigation, block, denyPermissions, restrictMessages, bridgeInSubframes);
    }

    public SecurityPolicy withDenyPermissions(boolean deny) {
        return new SecurityPolicy(trustedOrigins, lockNavigation, blockNewWindows, deny, restrictMessages, bridgeInSubframes);
    }

    public SecurityPolicy withRestrictMessages(boolean restrict) {
        return new SecurityPolicy(trustedOrigins, lockNavigation, blockNewWindows, denyPermissions, restrict, bridgeInSubframes);
    }

    public SecurityPolicy withBridgeInSubframes(boolean subframes) {
        return new SecurityPolicy(trustedOrigins, lockNavigation, blockNewWindows, denyPermissions, restrictMessages, subframes);
    }

    /** Whether the page at {@code url} comes from the window's own content or a listed origin. */
    public boolean trusts(String url) {
        String origin = originOf(url);
        return origin != null && (origin.equals(APP_ORIGIN) || trustedOrigins.contains(origin));
    }

    /**
     * {@code scheme://host[:port]} in lower case with default ports dropped, or {@code null} if {@code url} has no
     * origin (relative, {@code about:blank}, {@code data:}, or not a URL). A {@code blob:} URL has the origin of the page
     * that made it.
     */
    public static String originOf(String url) {
        if (url == null) {
            return null;
        }
        String text = url.strip();
        if (text.regionMatches(true, 0, "blob:", 0, 5)) {
            text = text.substring(5);
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (java.net.URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port == (scheme.equals("https") ? 443 : scheme.equals("http") ? 80 : -1)) {
            port = -1;
        }
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + (port >= 0 ? ":" + port : "");
    }
}
