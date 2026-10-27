package io.github.b077as.jolt.credentials;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every backend must do. Each platform's test class extends this and opens its own store, so Windows and Linux are
 * held to the same behaviour. The tests use a throwaway service name and delete everything they create, but they do talk
 * to the real keystore of the machine.
 */
public abstract class CredentialStoreContract {

    private final String service = "jolt-test-" + UUID.randomUUID();
    private final Set<String> created = new LinkedHashSet<>();
    private final Set<CredentialStore> opened = new LinkedHashSet<>();
    private CredentialStore store;

    /** Opens the platform's store for {@code service}; may abort the test (assumption) when the machine has none. */
    protected abstract CredentialStore open(String service);

    @BeforeEach
    void openStore() {
        store = open(service);
    }

    @AfterEach
    void removeWhatWeCreated() {
        for (String entry : created) {
            int split = entry.indexOf('\n');
            open(entry.substring(0, split)).delete(entry.substring(split + 1));
        }
    }

    private void put(CredentialStore target, String serviceName, String account, String secret) {
        target.put(account, secret);
        created.add(serviceName + "\n" + account);
    }

    private void put(String account, String secret) {
        put(store, service, account, secret);
    }

    @Test
    void aMissingAccountIsEmpty() {
        assertEquals(Optional.empty(), store.get("nobody@example.com"));
    }

    @Test
    void aStoredSecretComesBack() {
        put("alice@example.com", "correct horse battery staple");
        assertEquals(Optional.of("correct horse battery staple"), store.get("alice@example.com"));
    }

    @Test
    void storingAgainReplacesTheSecret() {
        put("alice@example.com", "first");
        put("alice@example.com", "second");
        assertEquals(Optional.of("second"), store.get("alice@example.com"));
    }

    @Test
    void deleteRemovesTheSecretAndSaysSo() {
        put("alice@example.com", "secret");
        assertTrue(store.delete("alice@example.com"));
        assertEquals(Optional.empty(), store.get("alice@example.com"));
    }

    @Test
    void deletingAMissingAccountReturnsFalse() {
        assertFalse(store.delete("nobody@example.com"));
    }

    @Test
    void unicodeSecretsAndAccountsSurvive() {
        String account = "wíll@例え.example";
        String secret = "pässwörd 日本語 🔑";
        put(account, secret);
        assertEquals(Optional.of(secret), store.get(account));
    }

    @Test
    void quotesBackslashesAndSpacesSurvive() {
        String secret = "a \"quoted\" 'one' \\ back\\slash  two  spaces; $HOME %PATH% `tick` <tag> & more";
        put("odd account name", secret);
        assertEquals(Optional.of(secret), store.get("odd account name"));
    }

    @Test
    void aTwoThousandCharacterSecretSurvives() {
        String secret = "x".repeat(2000);
        put("long", secret);
        assertEquals(Optional.of(secret), store.get("long"));
    }

    @Test
    void accountsAreIndependent() {
        put("alice@example.com", "alice's");
        put("bob@example.com", "bob's");
        assertEquals(Optional.of("alice's"), store.get("alice@example.com"));
        assertEquals(Optional.of("bob's"), store.get("bob@example.com"));
        assertTrue(store.delete("alice@example.com"));
        assertEquals(Optional.of("bob's"), store.get("bob@example.com"));
    }

    @Test
    void servicesAreIndependent() {
        String otherService = service + "-other";
        CredentialStore other = open(otherService);
        put("alice@example.com", "in the first service");
        put(other, otherService, "alice@example.com", "in the second service");
        assertEquals(Optional.of("in the first service"), store.get("alice@example.com"));
        assertEquals(Optional.of("in the second service"), other.get("alice@example.com"));
    }

    @Test
    void aNewStoreObjectSeesEarlierSecrets() {
        put("alice@example.com", "persisted");
        assertEquals(Optional.of("persisted"), open(service).get("alice@example.com"));
    }
}
