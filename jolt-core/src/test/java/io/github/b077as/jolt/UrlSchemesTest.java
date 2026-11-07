package io.github.b077as.jolt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlSchemesTest {

    @Test
    void anyReasonableSchemeNameIsAccepted() {
        for (String scheme : List.of("myapp", "my-app", "my.app", "app+x", "a", "app2")) {
            assertEquals(scheme, UrlSchemes.validate(scheme));
        }
    }

    @Test
    void invalidAndReservedSchemesAreRefused() {
        for (String scheme : List.of("", "MyApp", "2app", "my app", "myapp://", "myapp:", "a/b", "a\\b", "-x", "x".repeat(65))) {
            assertThrows(IllegalArgumentException.class, () -> UrlSchemes.validate(scheme), scheme);
        }
        assertThrows(IllegalArgumentException.class, () -> UrlSchemes.validate(null));
        for (String scheme : List.of("http", "https", "file", "mailto", "javascript")) {
            assertThrows(IllegalArgumentException.class, () -> UrlSchemes.validate(scheme), scheme);
        }
    }

    @Test
    void theLinkIsPickedOutOfTheArguments() {
        List<String> args = List.of("--minimized", "myapp://invite/ABC123", "other");
        assertEquals("invite", UrlSchemes.findUrl(args, "myapp").orElseThrow().getHost());
        assertEquals("/ABC123", UrlSchemes.findUrl(args, "myapp").orElseThrow().getPath());
        assertTrue(UrlSchemes.findUrl(List.of("MYAPP://Invite/x"), "myapp").isPresent(), "the scheme is case-insensitive");
        assertTrue(UrlSchemes.findUrl(args, "other").isEmpty());
        assertTrue(UrlSchemes.findUrl(List.of(), "myapp").isEmpty());
        assertTrue(UrlSchemes.findUrl(List.of("myapp://bad uri"), "myapp").isEmpty(), "an argument that is not a valid URI is skipped");
        assertEquals("myapp://ok", UrlSchemes.findUrl(List.of("myapp://bad uri", "myapp://ok"), "myapp").orElseThrow().toString());
    }
}
