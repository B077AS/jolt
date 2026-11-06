package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.Clipboard;
import io.github.b077as.jolt.ClipboardContent;
import io.github.b077as.jolt.contract.ClipboardContract;
import io.github.b077as.jolt.internal.ClipboardData;
import io.github.b077as.jolt.internal.ffi.Native;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared clipboard contract on Windows, plus what only Windows can show: the formats a sensitive copy carries and CF_DIB. */
@EnabledOnOs(OS.WINDOWS)
class WindowsClipboardTest extends ClipboardContract {

    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
    private static final MethodHandle REGISTER_FORMAT = Native.downcall(USER32, "RegisterClipboardFormatW", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle IS_AVAILABLE = Native.downcall(USER32, "IsClipboardFormatAvailable", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final int CF_DIB = 8, CF_HDROP = 15, CF_TEXT = 1;

    private static boolean available(String registeredName) {
        try (Arena arena = Arena.ofConfined()) {
            int format = (int) REGISTER_FORMAT.invokeExact(Native.allocWide(arena, registeredName));
            return (int) IS_AVAILABLE.invokeExact(format) != 0;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static boolean available(int format) {
        try {
            return (int) IS_AVAILABLE.invokeExact(format) != 0;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    @Test
    void aSecretCarriesTheMarkersThatKeepItOutOfHistoryAndCloudSync() {
        boolean[] marked = run(c -> c.write(ClipboardContent.secret("hunter2", java.time.Duration.ofSeconds(30)))
                .thenApply(v -> new boolean[] {
                        available("CanIncludeInClipboardHistory"),
                        available("CanUploadToCloudClipboard"),
                        available("ExcludeClipboardContentFromMonitorProcessing")}));
        assertTrue(marked[0] && marked[1] && marked[2], "all three markers present");
    }

    @Test
    void anOrdinaryCopyCarriesNoSuchMarkers() {
        boolean marked = run(c -> c.writeText("not secret").thenApply(v -> available("CanIncludeInClipboardHistory")));
        assertFalse(marked);
    }

    @Test
    void anImageIsAlsoOfferedAsABitmapForProgramsThatKnowNothingElse() {
        byte[] png = ClipboardData.encodePng(new ClipboardData.Image(2, 2, new byte[16]));
        boolean dib = run(c -> c.write(ClipboardContent.image(png)).thenApply(v -> available(CF_DIB)));
        assertTrue(dib);
    }

    @Test
    void textIsAlsoAvailableToProgramsThatOnlyKnowAnsiText() {
        boolean ansi = run(c -> c.writeText("plain ascii").thenApply(v -> available(CF_TEXT)));
        assertTrue(ansi, "Windows derives CF_TEXT from CF_UNICODETEXT");
    }

    @Test
    void filesAreOfferedAsAFileDrop() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("jolt-clip", ".txt");
        try {
            boolean drop = run(c -> c.write(ClipboardContent.files(List.of(file.toAbsolutePath()))).thenApply(v -> available(CF_HDROP)));
            assertTrue(drop);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void aTextWriteDoesNotLeaveAnEarlierSecretsMarkersBehind() {
        boolean marked = run(c -> c.write(ClipboardContent.secret("hunter2", java.time.Duration.ofSeconds(30)))
                .thenCompose(v -> c.writeText("ordinary now"))
                .thenApply(v -> available("CanIncludeInClipboardHistory")));
        assertFalse(marked);
    }
}
