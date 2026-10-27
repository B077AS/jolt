package io.github.b077as.jolt.credentials.internal;

import io.github.b077as.jolt.credentials.CredentialStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Checks that need no keystore, so they run on every machine. */
class NamesTest {

    @Test
    void aServiceMustBeUsable() {
        assertDoesNotThrow(() -> Names.checkService("com.example.mail"));
        assertThrows(IllegalArgumentException.class, () -> Names.checkService(null));
        assertThrows(IllegalArgumentException.class, () -> Names.checkService("  "));
        assertThrows(IllegalArgumentException.class, () -> Names.checkService("a/b"));
        assertThrows(IllegalArgumentException.class, () -> Names.checkService("nul\0inside"));
        assertThrows(IllegalArgumentException.class, () -> Names.checkService("x".repeat(256)));
    }

    @Test
    void anAccountMayContainASlashButNotNul() {
        assertDoesNotThrow(() -> Names.checkAccount("team/alice@example.com"));
        assertDoesNotThrow(() -> Names.checkAccount("x".repeat(255)));
        assertThrows(IllegalArgumentException.class, () -> Names.checkAccount(""));
        assertThrows(IllegalArgumentException.class, () -> Names.checkAccount("a\0b"));
        assertThrows(IllegalArgumentException.class, () -> Names.checkAccount("x".repeat(256)));
    }

    @Test
    void aSecretIsNonEmptyTextWithoutNul() {
        assertDoesNotThrow(() -> Names.checkSecret("hunter2"));
        assertThrows(IllegalArgumentException.class, () -> Names.checkSecret(null));
        assertThrows(IllegalArgumentException.class, () -> Names.checkSecret(""));
        assertThrows(IllegalArgumentException.class, () -> Names.checkSecret("a\0b"));
    }

    @Test
    void theNativeStructsHaveTheSizesOfTheCHeaders() {
        // CREDENTIALW (wincred.h) is 80 bytes on 64-bit Windows; SecretSchema (secret-schema.h) is 592 on 64-bit Linux.
        assertEquals(80, WindowsCredentialStore.CREDENTIAL.byteSize());
        assertEquals(592, LinuxSecretServiceStore.SCHEMA.byteSize());
    }

    @Test
    void aBadServiceIsRefusedBeforeAnyNativeCodeRuns() {
        assertThrows(IllegalArgumentException.class, () -> CredentialStore.open("bad/service"));
    }
}
