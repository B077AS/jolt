package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.ResourceOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppPathsTest {

    @Test
    void addressesInsideTheAppAreAccepted() {
        for (String path : new String[] {"/", "/message/42", "/message/42?folder=inbox&x=a%20b#top", "/a//b", "/é"}) {
            assertTrue(AppPaths.isAppPath(path), path);
        }
    }

    @Test
    void anythingThatCouldNameAnotherHostOrIsNotAPathIsRefused() {
        for (String path : new String[] {null, "", "message", "//evil.example/x", "/\\evil.example/x", "https://evil.example/",
                "javascript:alert(1)", "/a\\b", "/a\nb", "/a\u0000b", "/a\u007fb", "/" + "x".repeat(2100)}) {
            assertFalse(AppPaths.isAppPath(path), String.valueOf(path));
        }
    }

    @Test
    void resourceOptionsValidateTheStartPathAndDefaultToTheRoot() {
        assertEquals("/", ResourceOptions.defaults().startPath());
        assertEquals("/", new ResourceOptions(5).startPath());
        assertEquals("/m/1?x=2", ResourceOptions.defaults().withStartPath("/m/1?x=2").startPath());
        assertEquals("/", new ResourceOptions(5, null).startPath());
        assertThrows(IllegalArgumentException.class, () -> ResourceOptions.defaults().withStartPath("//evil.example"));
        assertThrows(IllegalArgumentException.class, () -> ResourceOptions.defaults().withStartPath("index.html"));
        assertEquals(7, ResourceOptions.defaults().withStartPath("/x").withMaxRequestBytes(7).maxRequestBytes());
        assertEquals("/x", ResourceOptions.defaults().withStartPath("/x").withMaxRequestBytes(7).startPath());
    }
}
