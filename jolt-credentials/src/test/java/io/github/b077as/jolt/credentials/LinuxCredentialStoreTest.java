package io.github.b077as.jolt.credentials;

import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Runs the contract against the Secret Service of the machine running the tests. Needs libsecret and a running, unlocked
 * Secret Service; without one the tests are skipped with the reason. docker/linux-test has a setup with gnome-keyring.
 */
@EnabledOnOs(OS.LINUX)
class LinuxCredentialStoreTest extends CredentialStoreContract {

    @Override
    protected CredentialStore open(String service) {
        try {
            return CredentialStore.open(service);
        } catch (CredentialStoreUnavailableException e) {
            return abort("No usable Secret Service here: " + e.getMessage());
        }
    }
}
