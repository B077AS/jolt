package io.github.b077as.jolt.spring.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets only the application's own window use its web server in web mode.
 *
 * <p>The server listens on a loopback port, which every program on the machine can reach, and every web page in the user's
 * browser can send requests to. So each request must carry a secret that only the window has:
 *
 * <ol>
 *   <li>The window first opens {@value #ENTER_PATH}{@code ?token=<one-time token>}. The token is random, made at startup, and
 *       works once.</li>
 *   <li>That answer sets the session cookie ({@value #COOKIE}, {@code HttpOnly}, {@code SameSite=Strict}, random) and redirects
 *       to the start page. The cookie lives in the window's own web view profile, not in the user's browser.</li>
 *   <li>Every other request needs that cookie, or it gets 403.</li>
 * </ol>
 *
 * <p>Also refused: a {@code Host} that is not a loopback name (DNS rebinding: a site whose name points at 127.0.0.1), and a
 * state-changing request whose {@code Origin} is not loopback. Nothing is logged with the secrets in it.
 */
public final class LoopbackGuard extends HttpFilter {

    /** Where the window starts. */
    public static final String ENTER_PATH = "/.jolt/enter";
    public static final String COOKIE = "JOLT_SESSION";

    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final String enterToken = secret();
    private final String session = secret();
    private final AtomicBoolean entered = new AtomicBoolean();
    private final String startPath;

    public LoopbackGuard(String startPath) {
        this.startPath = startPath == null || startPath.isBlank() ? "/" : (startPath.startsWith("/") ? startPath : "/" + startPath);
    }

    /** The one-time token for {@value #ENTER_PATH}. */
    public String enterToken() {
        return enterToken;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (!isLoopbackHost(request.getHeader("Host"))) {
            deny(response);
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean hasSession = hasSession(request);
        if (ENTER_PATH.equals(path)) {
            if (hasSession || (same(request.getParameter("token"), enterToken) && entered.compareAndSet(false, true))) {
                response.setStatus(HttpServletResponse.SC_SEE_OTHER);
                response.setHeader("Location", request.getContextPath() + startPath);
                response.setHeader("Cache-Control", "no-store");
                response.addHeader("Set-Cookie", COOKIE + "=" + session + "; Path=/; HttpOnly; SameSite=Strict");
                return;
            }
            deny(response);
            return;
        }
        if (!hasSession) {
            deny(response);
            return;
        }
        if (!SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            String origin = request.getHeader("Origin");
            if (origin != null && !isLoopbackOrigin(origin)) {
                deny(response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean hasSession(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName()) && same(cookie.getValue(), session)) {
                return true;
            }
        }
        return false;
    }

    /** {@code 127.0.0.1:8080}, {@code localhost}, {@code [::1]:8080}. */
    static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String name = host.strip().toLowerCase(Locale.ROOT);
        if (name.startsWith("[")) {
            int end = name.indexOf(']');
            if (end < 0) {
                return false;
            }
            String rest = name.substring(end + 1);
            return LOOPBACK_HOSTS.contains(name.substring(0, end + 1)) && (rest.isEmpty() || rest.matches(":\\d{1,5}"));
        }
        int colon = name.indexOf(':');
        if (colon >= 0) {
            if (!name.substring(colon + 1).matches("\\d{1,5}")) {
                return false;
            }
            name = name.substring(0, colon);
        }
        return LOOPBACK_HOSTS.contains(name);
    }

    static boolean isLoopbackOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            String authority = uri.getRawAuthority();
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && isLoopbackHost(authority);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void deny(HttpServletResponse response) throws IOException {
        // Not sendError: that would run the application's error page (and this filter again) for a request it must not serve.
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/plain;charset=UTF-8");
        response.getOutputStream().write("This server only answers its own application window.\n".getBytes(StandardCharsets.UTF_8));
    }

    private static boolean same(String given, String expected) {
        return given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
