package io.github.b077as.jolt.credentials;

import io.github.b077as.jolt.credentials.internal.WindowsCredentialStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Runs the contract against the real Windows Credential Manager of the machine running the tests. */
@EnabledOnOs(OS.WINDOWS)
class WindowsCredentialStoreTest extends CredentialStoreContract {

    @Override
    protected CredentialStore open(String service) {
        return CredentialStore.open(service);
    }

    @Test
    void aSecretOverTheCredentialManagerLimitIsRefusedBeforeItIsSent() {
        CredentialStore store = open("jolt-test-limit");
        assertThrows(IllegalArgumentException.class,
                () -> store.put("alice@example.com", "x".repeat(WindowsCredentialStore.MAX_SECRET_BYTES + 1)));
    }

    @Test
    void aSecretAtTheLimitIsAccepted() {
        CredentialStore store = open("jolt-test-limit-ok");
        String secret = "y".repeat(WindowsCredentialStore.MAX_SECRET_BYTES);
        try {
            store.put("alice@example.com", secret);
            org.junit.jupiter.api.Assertions.assertEquals(java.util.Optional.of(secret), store.get("alice@example.com"));
        } finally {
            store.delete("alice@example.com");
        }
    }
}
