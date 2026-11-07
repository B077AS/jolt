package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.UrlSchemes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Writes a throwaway scheme under HKCU\Software\Classes, reads it back with reg.exe, and removes it. */
@EnabledOnOs(OS.WINDOWS)
class WindowsUrlSchemesTest {

    private static String reg(String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "reg";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return output;
    }

    @Test
    void registerWritesTheKeysWindowsReadsAndUnregisterRemovesThem() throws Exception {
        String scheme = "jolt-test-" + UUID.randomUUID();
        UrlSchemes schemes = new WindowsUrlSchemes();
        String key = "HKCU\\Software\\Classes\\" + scheme;
        try {
            assertFalse(schemes.isRegistered(scheme));
            schemes.register(scheme, "Jolt Test", List.of("C:\\Program Files\\MyApp\\MyApp.exe", "--url"));
            assertTrue(schemes.isRegistered(scheme));
            String root = reg("query", key, "/ve");
            assertTrue(root.contains("URL:Jolt Test"), root);
            assertTrue(reg("query", key, "/v", "URL Protocol").contains("URL Protocol"));
            String command = reg("query", key + "\\shell\\open\\command", "/ve");
            assertTrue(command.contains("\"C:\\Program Files\\MyApp\\MyApp.exe\" --url \"%1\""), command);
            schemes.register(scheme, "Jolt Test", List.of("C:\\MyApp.exe")); // replaces
            assertTrue(reg("query", key + "\\shell\\open\\command", "/ve").contains("C:\\MyApp.exe \"%1\""));
            schemes.unregister(scheme);
            assertFalse(schemes.isRegistered(scheme));
            schemes.unregister(scheme); // nothing to remove is not an error
        } finally {
            reg("delete", key, "/f");
        }
    }

    @Test
    void badInputIsRefused() {
        UrlSchemes schemes = new WindowsUrlSchemes();
        assertThrows(IllegalArgumentException.class, () -> schemes.register("https", "x", List.of("C:\\a.exe")));
        assertThrows(IllegalArgumentException.class, () -> schemes.register("Has Space", "x", List.of("C:\\a.exe")));
        assertThrows(IllegalArgumentException.class, () -> schemes.register("jolt-test-none", "x", List.of()));
        assertEquals(false, schemes.isRegistered("jolt-test-never-registered"));
    }
}
