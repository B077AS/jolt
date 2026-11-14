package io.github.b077as.jolt.spring.internal;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only the window, which entered with the one-time token and holds the session cookie, gets through. */
class LoopbackGuardTest {

    private final LoopbackGuard guard = new LoopbackGuard("/start");

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Host", "127.0.0.1:51234");
        return request;
    }

    /** Runs the guard; returns the chain (its request is null when the guard did not let the request through). */
    private MockFilterChain filter(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        guard.doFilter(request, response, chain);
        return chain;
    }

    /** Enters like the window does and returns the session cookie it was given. */
    private Cookie enter() throws Exception {
        MockHttpServletRequest request = request("GET", LoopbackGuard.ENTER_PATH);
        request.setParameter("token", guard.enterToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(request, response);
        assertEquals(303, response.getStatus());
        assertEquals("/start", response.getHeader("Location"));
        String setCookie = response.getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict") && setCookie.contains("Path=/"), setCookie);
        String value = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        return new Cookie(LoopbackGuard.COOKIE, value);
    }

    @Test
    void aRequestWithoutTheSessionIsRefused() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = filter(request("GET", "/api/notes"), response);
        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest(), "the application never saw it");
    }

    @Test
    void theWindowEntersOnceWithTheTokenAndIsLetThroughWithItsCookieAfterwards() throws Exception {
        Cookie session = enter();

        MockHttpServletRequest request = request("GET", "/api/notes");
        request.setCookies(session);
        MockFilterChain chain = filter(request, new MockHttpServletResponse());
        assertNotNull(chain.getRequest(), "let through");

        // The token works once: whoever reads it later (without the cookie) gets nothing.
        MockHttpServletRequest again = request("GET", LoopbackGuard.ENTER_PATH);
        again.setParameter("token", guard.enterToken());
        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter(again, refused);
        assertEquals(403, refused.getStatus());

        // The window itself may come back to the entry page (a reload): it has the cookie.
        MockHttpServletRequest back = request("GET", LoopbackGuard.ENTER_PATH);
        back.setCookies(session);
        MockHttpServletResponse redirected = new MockHttpServletResponse();
        filter(back, redirected);
        assertEquals(303, redirected.getStatus());
    }

    @Test
    void aWrongTokenOrAWrongCookieIsRefused() throws Exception {
        MockHttpServletRequest wrongToken = request("GET", LoopbackGuard.ENTER_PATH);
        wrongToken.setParameter("token", "guess");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(wrongToken, response);
        assertEquals(403, response.getStatus());

        MockHttpServletRequest wrongCookie = request("GET", "/");
        wrongCookie.setCookies(new Cookie(LoopbackGuard.COOKIE, "guess"));
        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter(wrongCookie, refused);
        assertEquals(403, refused.getStatus());
    }

    @Test
    void aHostThatIsNotLoopbackIsRefusedEvenWithTheCookie() throws Exception {
        Cookie session = enter();
        MockHttpServletRequest rebound = new MockHttpServletRequest("GET", "/api/notes");
        rebound.addHeader("Host", "attacker.example:51234"); // a name that resolves to 127.0.0.1
        rebound.setCookies(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = filter(rebound, response);
        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void aStateChangingRequestFromAnotherOriginIsRefused() throws Exception {
        Cookie session = enter();

        MockHttpServletRequest foreign = request("POST", "/api/notes");
        foreign.setCookies(session);
        foreign.addHeader("Origin", "https://evil.example");
        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter(foreign, refused);
        assertEquals(403, refused.getStatus());

        MockHttpServletRequest own = request("POST", "/api/notes");
        own.setCookies(session);
        own.addHeader("Origin", "http://127.0.0.1:51234");
        assertNotNull(filter(own, new MockHttpServletResponse()).getRequest());

        MockHttpServletRequest noOrigin = request("POST", "/api/notes");
        noOrigin.setCookies(session);
        assertNotNull(filter(noOrigin, new MockHttpServletResponse()).getRequest());
    }

    @Test
    void loopbackHostNames() {
        for (String host : new String[] {"127.0.0.1", "127.0.0.1:8080", "localhost:1", "LOCALHOST", "[::1]", "[::1]:443"}) {
            assertTrue(LoopbackGuard.isLoopbackHost(host), host);
        }
        for (String host : new String[] {"", "example.com", "127.0.0.1.example.com", "localhost.evil:80", "127.0.0.1:x", "[::2]:80",
                "[::1]x", "127.0.0.2"}) {
            assertFalse(LoopbackGuard.isLoopbackHost(host), host);
        }
        assertFalse(LoopbackGuard.isLoopbackHost(null));
        assertTrue(LoopbackGuard.isLoopbackOrigin("http://localhost:5173"));
        assertFalse(LoopbackGuard.isLoopbackOrigin("null"));
        assertFalse(LoopbackGuard.isLoopbackOrigin("file://localhost"));
    }
}
