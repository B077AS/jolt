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
 * <h2>Content-Security-Policy</h2>
 * Your own content is also given a <a href="https://developer.mozilla.org/docs/Web/HTTP/CSP">Content-Security-Policy</a>,
 * which limits what an injected piece of HTML or script could do. There are two, because the two ways of loading content
 * need different things: {@link #DEFAULT_APP_CSP} for {@link WebWindow#loadResources} (scripts only from your own files)
 * and {@link #DEFAULT_HTML_CSP} for {@link WebWindow#loadHtml} (inline scripts allowed, since a single string has nowhere
 * else to put them). Both refuse scripts, styles, images and requests to other hosts. Pages loaded with
 * {@link WebWindow#loadUrl} are not touched: their server sends their policy.
 *
 * @param trustedOrigins   origins trusted in addition to the window's own content
 * @param lockNavigation   block navigation (links, redirects, frames, {@code loadUrl}) to origins that are not trusted.
 *                         On Linux this is also what keeps untrusted frames out of a trusted page, because WebKitGTK
 *                         cannot tell which frame sent a message
 * @param blockNewWindows  block pages from opening new windows ({@code window.open}, {@code target=_blank}); Windows
 *                         only, as WebKitGTK never opens one without the application's help
 * @param denyPermissions  refuse page requests for camera, microphone, location, notifications and the like
 * @param restrictMessages ignore messages sent by pages whose origin is not trusted
 * @param grantedPermissions permissions given to your own content and trusted origins even while {@code denyPermissions}
 *                          is on (for example {@link Permission#MICROPHONE} for a voice chat). Other pages stay refused
 * @param bridgeInSubframes whether {@code window.jolt} also exists in frames inside the page. Read when the window is
 *                          created; changing it later has no effect on that window
 * @param blockDownloads    refuse downloads while the window has no {@link WebWindow#onDownload} handler; when off, the web
 *                          view handles them by itself
 * @param appContentSecurityPolicy  the policy sent with the files and handler answers of {@code loadResources}, or
 *                                  {@code null} for none. An answer that has its own {@code Content-Security-Policy} header
 *                                  keeps it. Applies to requests made after it is set
 * @param htmlContentSecurityPolicy the policy of documents given to {@code loadHtml}, or {@code null} for none. Applies to
 *                                  documents loaded after it is set
 */
public record SecurityPolicy(Set<String> trustedOrigins, boolean lockNavigation, boolean blockNewWindows,
                             boolean denyPermissions, Set<Permission> grantedPermissions, boolean restrictMessages,
                             boolean bridgeInSubframes, boolean blockDownloads, String appContentSecurityPolicy,
                             String htmlContentSecurityPolicy) {

    /** The origin of content passed to {@code loadHtml}. Always trusted. */
    public static final String APP_ORIGIN = "https://jolt.localhost";

    /**
     * Everything but the script rule. {@code ws://127.0.0.1:*} is for {@link BinaryChannel}. The CSP standard lets a
     * {@code ws:} source match {@code http:} too, and WebKitGTK does, so on Linux a page may also send HTTP requests to
     * 127.0.0.1; remove the source if the application opens no channels (and does not use {@code jolt.saveAs}).
     */
    private static final String COMMON_CSP = "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
            + "font-src 'self' data:; media-src 'self' data: blob:; connect-src 'self' data: blob: ws://127.0.0.1:*; "
            + "frame-src 'self' data: blob:; worker-src 'self' blob:; object-src 'none'; base-uri 'self'; form-action 'self'; "
            + "frame-ancestors 'self'; ";

    /**
     * The default policy for {@code loadResources}: scripts only from the app's own files, so no inline {@code <script>},
     * no {@code onclick="..."} attributes and no {@code eval}. Inline styles and WebAssembly are allowed.
     */
    public static final String DEFAULT_APP_CSP = COMMON_CSP + "script-src 'self' 'wasm-unsafe-eval'";

    /** The default policy for {@code loadHtml}: like {@link #DEFAULT_APP_CSP} but inline scripts and handlers run. */
    public static final String DEFAULT_HTML_CSP = COMMON_CSP + "script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'";

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
        grantedPermissions = grantedPermissions == null || grantedPermissions.isEmpty()
                ? Set.of() : Collections.unmodifiableSet(java.util.EnumSet.copyOf(grantedPermissions));
        appContentSecurityPolicy = checkPolicy(appContentSecurityPolicy);
        htmlContentSecurityPolicy = checkPolicy(htmlContentSecurityPolicy);
    }

    /** One line of printable text, because it becomes a header; blank means none. */
    private static String checkPolicy(String policy) {
        if (policy == null || policy.isBlank()) {
            return null;
        }
        if (policy.chars().anyMatch(c -> c < 0x20 || c > 0x7E)) {
            throw new IllegalArgumentException("a Content-Security-Policy must be one line of printable ASCII");
        }
        return policy.strip();
    }

    /** Recommended: trust only your own content, and block everything else listed here. */
    public static SecurityPolicy standard() {
        return new SecurityPolicy(Set.of(), true, true, true, Set.of(), true, false, true, DEFAULT_APP_CSP, DEFAULT_HTML_CSP);
    }

    /**
     * Every protection off: any page can navigate, open windows, ask for permissions, download files and call your
     * handlers, and no Content-Security-Policy is added.
     */
    public static SecurityPolicy permissive() {
        return new SecurityPolicy(Set.of(), false, false, false, Set.of(), false, true, false, null, null);
    }

    /** Adds origins to trust, for example a dev server ({@code http://localhost:5173}) or your own website. */
    public SecurityPolicy withTrustedOrigins(String... origins) {
        Set<String> all = new LinkedHashSet<>(trustedOrigins);
        all.addAll(java.util.Arrays.asList(origins));
        return new SecurityPolicy(all, lockNavigation, blockNewWindows, denyPermissions, grantedPermissions, restrictMessages,
                bridgeInSubframes, blockDownloads, appContentSecurityPolicy, htmlContentSecurityPolicy);
    }

    /** Replaces the list of extra trusted origins. */
    public SecurityPolicy withOnlyTrustedOrigins(String... origins) {
        return new SecurityPolicy(new LinkedHashSet<>(java.util.Arrays.asList(origins)), lockNavigation, blockNewWindows,
                denyPermissions, grantedPermissions, restrictMessages, bridgeInSubframes, blockDownloads,
                appContentSecurityPolicy, htmlContentSecurityPolicy);
    }

    public SecurityPolicy withLockNavigation(boolean lock) {
        return new SecurityPolicy(trustedOrigins, lock, blockNewWindows, denyPermissions, grantedPermissions, restrictMessages,
                bridgeInSubframes, blockDownloads, appContentSecurityPolicy, htmlContentSecurityPolicy);
    }

    public SecurityPolicy withBlockNewWindows(boolean block) {
        return new SecurityPolicy(trustedOrigins, lockNavigation, block, denyPermissions, grantedPermissions, restrictMessages,
                bridgeInSubframes, blockDownloads, appContentSecurityPolicy, htmlContentSecurityPolicy);
    }

    public SecurityPolicy withDenyPermissions(boolean deny) {
        return new SecurityPolicy(trustedOrigins, lockNavigation, blockNewWindows, deny, grantedPermissions, restrictMessages,
                bridgeInSubframes, blockDownloads, appContentSecurityPolicy, htmlContentSecurityPolicy);
    }

    /**
     * Permissions to give to the window's own content and trusted origins even though {@code denyPermissions} is on.
     * Replaces any earlier list; pass none to clear it.
     */
    public SecurityPolicy withGrantedPermissions(Permission... permissions) {
        Set<Permission> set = java.util.EnumSet.noneOf(Permission.class);
        set.addAll(java.util.Arrays.asList(permissions));
        return new SecurityPolicy(trustedOrigins, lockNavigation, blockNewWindows, denyPermissions, set, restrictMessages,
                bridgeInSubframes, blockDownloads, appContentSecurityPolicy, htmlContentSecurityPolicy);
    }

    /** Whether {@code permission} is granted to the page at {@code url}: listed in the policy and the page is trusted. */
    public boolean grants(String url, Permission permission) {
        return permission != null && grantedPermissions.contains(permission) && trusts(url);
    }

    public SecurityPolicy withRestrictMessages(boolean restrict) {
        return new SecurityPolicy(trustedOrigins, lockNavigation, blockNewWindows, denyPermissions, grantedPermissions, restrict,
                bridgeInSubframes, blockDownloads, appContentSecurityPolicy, htmlContentSecurityPolicy);
    }

    public SecurityPolicy withBridgeInSubframes(boolean subframes) {
        return new SecurityPolicy(trustedOrigins, lockNavigation, blockNewWindows, denyPermissions, grantedPermissions,
                restrictMessages, subframes, blockDownloads, appContentSecurityPolicy, htmlContentSecurityPolicy);
    }

    public SecurityPolicy withBlockDownloads(boolean block) {
        return new SecurityPolicy(trustedOrigins, lockNavigation, blockNewWindows, denyPermissions, grantedPermissions,
                restrictMessages, bridgeInSubframes, block, appContentSecurityPolicy, htmlContentSecurityPolicy);
    }

    /**
     * The same Content-Security-Policy for {@code loadResources} and {@code loadHtml} content, replacing both defaults;
     * {@code null} turns it off.
     */
    public SecurityPolicy withContentSecurityPolicy(String policy) {
        return new SecurityPolicy(trustedOrigins, lockNavigation, blockNewWindows, denyPermissions, grantedPermissions,
                restrictMessages, bridgeInSubframes, blockDownloads, policy, policy);
    }

    /** The Content-Security-Policy for {@code loadResources} content only; {@code null} turns it off. */
    public SecurityPolicy withAppContentSecurityPolicy(String policy) {
        return new SecurityPolicy(trustedOrigins, lockNavigation, blockNewWindows, denyPermissions, grantedPermissions,
                restrictMessages, bridgeInSubframes, blockDownloads, policy, htmlContentSecurityPolicy);
    }

    /** The Content-Security-Policy for {@code loadHtml} content only; {@code null} turns it off. */
    public SecurityPolicy withHtmlContentSecurityPolicy(String policy) {
        return new SecurityPolicy(trustedOrigins, lockNavigation, blockNewWindows, denyPermissions, grantedPermissions,
                restrictMessages, bridgeInSubframes, blockDownloads, appContentSecurityPolicy, policy);
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
