package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.BlockedEvent;
import io.github.b077as.jolt.SecurityPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGuardTest {

    private static List<BlockedEvent> record(SecurityGuard guard) {
        List<BlockedEvent> events = new ArrayList<>();
        guard.onBlocked(events::add);
        return events;
    }

    @Test
    void standardAllowsOwnContentAndAboutPagesButBlocksOtherNavigation() {
        SecurityGuard guard = new SecurityGuard(SecurityPolicy.standard());
        List<BlockedEvent> events = record(guard);
        assertTrue(guard.allowNavigation("https://jolt.localhost/doc/1.html"));
        assertTrue(guard.allowNavigation("about:blank"));
        assertTrue(guard.allowNavigation("about:srcdoc"));
        assertTrue(events.isEmpty());
        assertFalse(guard.allowNavigation("https://example.com/"));
        assertFalse(guard.allowNavigation("data:text/html,x"));
        assertFalse(guard.allowNavigation(null));
        assertEquals(List.of(
                new BlockedEvent(BlockedEvent.Kind.NAVIGATION, "https://example.com/"),
                new BlockedEvent(BlockedEvent.Kind.NAVIGATION, "data:text/html,x"),
                new BlockedEvent(BlockedEvent.Kind.NAVIGATION, null)), events);
    }

    @Test
    void policyCanBeChangedWhileRunning() {
        SecurityGuard guard = new SecurityGuard(SecurityPolicy.standard());
        assertFalse(guard.allowNavigation("http://localhost:5173/"));
        guard.set(guard.policy().withTrustedOrigins("http://localhost:5173"));
        assertTrue(guard.allowNavigation("http://localhost:5173/"));
        guard.set(guard.policy().withLockNavigation(false));
        assertTrue(guard.allowNavigation("https://anything.example/"));
    }

    @Test
    void messagesFromUntrustedPagesAreDroppedUnlessRestrictionIsOff() {
        SecurityGuard guard = new SecurityGuard(SecurityPolicy.standard());
        List<BlockedEvent> events = record(guard);
        assertTrue(guard.allowMessageFrom("https://jolt.localhost/doc/2.html"));
        assertFalse(guard.allowMessageFrom("https://example.com/"));
        assertFalse(guard.allowMessageFrom(null));
        assertEquals(2, events.size());
        guard.set(guard.policy().withRestrictMessages(false));
        assertTrue(guard.allowMessageFrom("https://example.com/"));
        assertEquals(2, events.size());
    }

    @Test
    void newWindowsAndPermissionsFollowTheirSwitches() {
        SecurityGuard guard = new SecurityGuard(SecurityPolicy.standard());
        List<BlockedEvent> events = record(guard);
        assertFalse(guard.allowNewWindow("https://example.com/"));
        assertFalse(guard.allowPermission("https://jolt.localhost/doc/1.html"));
        assertEquals(BlockedEvent.Kind.NEW_WINDOW, events.get(0).kind());
        assertEquals(BlockedEvent.Kind.PERMISSION, events.get(1).kind());
        guard.set(SecurityPolicy.standard().withBlockNewWindows(false).withDenyPermissions(false));
        assertTrue(guard.allowNewWindow("https://example.com/"));
        assertTrue(guard.allowPermission("https://example.com/"));
    }

    @Test
    void aFailingListenerDoesNotStopTheDecision() {
        SecurityGuard guard = new SecurityGuard(SecurityPolicy.standard());
        guard.onBlocked(e -> {
            throw new IllegalStateException("boom");
        });
        List<BlockedEvent> events = record(guard);
        assertFalse(guard.allowNavigation("https://example.com/"));
        assertEquals(1, events.size());
    }
}
