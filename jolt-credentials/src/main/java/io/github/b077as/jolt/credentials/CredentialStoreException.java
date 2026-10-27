package io.github.b077as.jolt.credentials;

/** The operating system's keystore refused or failed to do what was asked. The message says what it reported. */
public class CredentialStoreException extends RuntimeException {

    public CredentialStoreException(String message) {
        super(message);
    }

    public CredentialStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
