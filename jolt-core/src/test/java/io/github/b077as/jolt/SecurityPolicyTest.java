package io.github.b077as.jolt;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityPolicyTest {

    @Test
    void originsAreNormalised() {
        assertEquals("https://example.com", SecurityPolicy.originOf("HTTPS://Example.COM:443/a/b?q#f"));
        assertEquals("http://example.com", SecurityPolicy.originOf("http://example.com:80/"));
        assertEquals("http://localhost:5173", SecurityPolicy.originOf("http://localhost:5173/src/main.ts"));
        assertEquals("https://jolt.localhost", SecurityPolicy.originOf("blob:https://jolt.localhost/3f2a"));
    }

    @Test
    void thingsWithoutAnOriginHaveNone() {
        for (String url : new String[] {null, "", "about:blank", "data:text/html,hi", "/relative/path", "not a url", "file:///etc/passwd"}) {
            assertNull(SecurityPolicy.originOf(url), url);
        }
    }

    @Test
    void trustsTheWindowOwnContentAndListedOriginsOnly() {
        SecurityPolicy policy = SecurityPolicy.standard().withTrustedOrigins("http://localhost:5173", "https://Example.com:443");
        assertTrue(policy.trusts("https://jolt.localhost/doc/1.html"));
        assertTrue(policy.trusts("http://localhost:5173/"));
        assertTrue(policy.trusts("https://example.com/page"));
        assertFalse(policy.trusts("http://localhost:5174/"));
        assertFalse(policy.trusts("http://example.com/"));
        assertFalse(policy.trusts("https://evil.example.com/"));
        assertFalse(policy.trusts("https://jolt.localhost.evil.com/"));
        assertFalse(policy.trusts("about:blank"));
        assertFalse(policy.trusts(null));
    }

    @Test
    void standardIsStrictAndPermissiveIsOpen() {
        SecurityPolicy standard = SecurityPolicy.standard();
        assertTrue(standard.lockNavigation() && standard.blockNewWindows() && standard.denyPermissions() && standard.restrictMessages());
        assertFalse(standard.bridgeInSubframes());
        assertEquals(Set.of(), standard.trustedOrigins());
        SecurityPolicy permissive = SecurityPolicy.permissive();
        assertFalse(permissive.lockNavigation() || permissive.blockNewWindows() || permissive.denyPermissions()
                || permissive.restrictMessages());
        assertTrue(permissive.bridgeInSubframes());
    }

    @Test
    void withersChangeOneThingAndLeaveTheRest() {
        SecurityPolicy base = SecurityPolicy.standard().withTrustedOrigins("https://a.example");
        SecurityPolicy changed = base.withLockNavigation(false);
        assertFalse(changed.lockNavigation());
        assertTrue(changed.restrictMessages());
        assertEquals(base.trustedOrigins(), changed.trustedOrigins());
        assertEquals(Set.of("https://b.example"), base.withOnlyTrustedOrigins("https://b.example").trustedOrigins());
        assertTrue(base.withBridgeInSubframes(true).bridgeInSubframes());
    }

    @Test
    void aBadOriginIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SecurityPolicy.standard().withTrustedOrigins("example.com"));
        assertThrows(IllegalArgumentException.class, () -> SecurityPolicy.standard().withTrustedOrigins("about:blank"));
    }

    @Test
    void windowOptionsDefaultToStandardAndCarryAPolicy() {
        assertEquals(SecurityPolicy.standard(), WindowOptions.defaults().security());
        assertEquals(SecurityPolicy.standard(), WindowOptions.defaults().withSecurity(null).security());
        WindowOptions permissive = WindowOptions.defaults().withSecurity(SecurityPolicy.permissive());
        assertEquals(SecurityPolicy.permissive(), permissive.security());
        assertEquals(SecurityPolicy.permissive(), permissive.withTitle("x").withSize(10, 10).security());
    }
}
