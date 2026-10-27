package io.github.b077as.jolt.credentials;

import io.github.b077as.jolt.credentials.internal.LinuxSecretServiceStore;
import io.github.b077as.jolt.credentials.internal.Names;
import io.github.b077as.jolt.credentials.internal.WindowsCredentialStore;

import java.util.Locale;
import java.util.Optional;

/**
 * Keeps small secrets (a password, an OAuth refresh token) in the operating system's own keystore, so an application
 * never has to write them to a file of its own.
 *
 * <pre>{@code
 * CredentialStore store = CredentialStore.open("com.example.mail");
 * store.put("alice@example.com", password);
 * Optional<String> saved = store.get("alice@example.com");
 * store.delete("alice@example.com");
 * }</pre>
 *
 * <p>A store is a namespace: the <em>service</em> you open it with (use something unique to your app, such as its
 * reverse-DNS id) plus an <em>account</em> name identify one secret. Two apps that pick different services never see
 * each other's entries.
 *
 * <p>Backends, chosen automatically:
 * <ul>
 *   <li><b>Windows</b>: Credential Manager ({@code CredWriteW} and friends in {@code advapi32}). A secret may be at most
 *       {@value WindowsCredentialStore#MAX_SECRET_BYTES} bytes in UTF-8.</li>
 *   <li><b>Linux</b>: the freedesktop.org Secret Service through {@code libsecret} (GNOME Keyring, KWallet, KeePassXC).
 *       Needs {@code libsecret-1.so.0} and a running Secret Service; {@link #open} fails with
 *       {@link CredentialStoreUnavailableException} when either is missing, so the application can fall back.</li>
 * </ul>
 * macOS is not supported.
 *
 * <p>What this protects against: other users of the machine and someone reading the disk. It does not protect against a
 * program running as the same user, which can ask the keystore for the same secrets. Secrets are text (UTF-8, no NUL
 * characters), and a Java {@link String} cannot be wiped from memory, so do not keep one longer than you need to.
 *
 * <p>All methods block, and on Linux they may wait for the user to unlock the keyring. Call them off the UI thread.
 * A store is thread safe.
 */
public interface CredentialStore {

    /**
     * Opens the keystore of this platform.
     *
     * @param service the namespace for this application's secrets: not blank, at most 255 characters, no {@code /}
     * @throws CredentialStoreUnavailableException if this platform has no usable keystore (unsupported OS, no
     *         libsecret, no Secret Service running); the message says why
     * @throws IllegalArgumentException            if {@code service} is not valid
     */
    static CredentialStore open(String service) {
        Names.checkService(service);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new WindowsCredentialStore(service);
        }
        if (os.contains("linux")) {
            return new LinuxSecretServiceStore(service);
        }
        throw new CredentialStoreUnavailableException(
                "There is no credential store for " + System.getProperty("os.name") + ": Jolt supports Windows and Linux");
    }

    /**
     * Returns the secret saved for {@code account}, or empty if there is none.
     *
     * @throws CredentialStoreException if the keystore failed
     */
    Optional<String> get(String account);

    /**
     * Saves {@code secret} for {@code account}, replacing any earlier one.
     *
     * @param account the name to file it under, for example an email address: not blank, at most 255 characters
     * @param secret  not empty, no NUL characters (on Windows also at most
     *                {@value WindowsCredentialStore#MAX_SECRET_BYTES} bytes in UTF-8)
     * @throws IllegalArgumentException if an argument is not valid
     * @throws CredentialStoreException if the keystore failed
     */
    void put(String account, String secret);

    /**
     * Removes the secret saved for {@code account}.
     *
     * @return {@code true} if there was one
     * @throws CredentialStoreException if the keystore failed
     */
    boolean delete(String account);
}
