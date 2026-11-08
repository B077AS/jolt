package io.github.b077as.jolt.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileDragProtocolTest {

    @Test
    void theTokenIsTakenFromTheMessage() {
        assertEquals("attachment:42", FileDragProtocol.token(FileDragProtocol.PREFIX + "{\"token\":\"attachment:42\"}"));
        assertEquals("a \"b\" \u00e9", FileDragProtocol.token(FileDragProtocol.PREFIX + "{\"token\":\"a \\\"b\\\" \\u00e9\"}"));
    }

    @Test
    void malformedEmptyOrOversizedTokensAreRefused() {
        assertNull(FileDragProtocol.token("hello"));
        assertNull(FileDragProtocol.token(FileDragProtocol.PREFIX));
        assertNull(FileDragProtocol.token(FileDragProtocol.PREFIX + "{\"token\":\"\"}"));
        assertNull(FileDragProtocol.token(FileDragProtocol.PREFIX + "{\"token\":7}"));
        assertNull(FileDragProtocol.token(FileDragProtocol.PREFIX + "{\"other\":\"x\"}"));
        assertNull(FileDragProtocol.token(FileDragProtocol.PREFIX + "[]"));
        assertNull(FileDragProtocol.token(FileDragProtocol.PREFIX + "{\"token\":\"" + "t".repeat(FileDragProtocol.MAX_TOKEN + 1) + "\"}"));
        assertEquals("t".repeat(FileDragProtocol.MAX_TOKEN),
                FileDragProtocol.token(FileDragProtocol.PREFIX + "{\"token\":\"" + "t".repeat(FileDragProtocol.MAX_TOKEN) + "\"}"));
    }

    @Test
    void onlyExistingAbsoluteLocalFilesMayBeOffered() throws Exception {
        Path file = Files.createTempFile("jolt-drag", ".txt");
        try {
            assertTrue(PageFileDrag.acceptable(List.of(file)));
            assertTrue(PageFileDrag.acceptable(List.of(file.getParent())), "a folder can be dragged too");
            assertFalse(PageFileDrag.acceptable(null));
            assertFalse(PageFileDrag.acceptable(List.of()));
            assertFalse(PageFileDrag.acceptable(List.of(Path.of("relative.txt"))));
            assertFalse(PageFileDrag.acceptable(List.of(file, file.resolveSibling("does-not-exist-" + System.nanoTime()))));
            List<Path> many = new ArrayList<>();
            for (int i = 0; i <= ClipboardData.MAX_FILES; i++) {
                many.add(file);
            }
            assertFalse(PageFileDrag.acceptable(many));
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                assertFalse(PageFileDrag.acceptable(List.of(Path.of("\\\\server\\share\\a.txt"))));
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void theScriptCancelsTheWebViewsDragAndAsksTheHost() {
        String script = FileDragScript.build();
        for (String needed : new String[] {"\\u0001jolt-dragout:", "[data-jolt-drag-file]", "isTrusted", "preventDefault", "'dragstart'"}) {
            assertTrue(script.contains(needed), "missing: " + needed);
        }
        assertFalse(script.contains("\u0001"));
    }
}
