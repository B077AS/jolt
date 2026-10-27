package io.github.b077as.jolt.credentials;

/**
 * This machine has no usable keystore: the operating system is not supported, {@code libsecret} is not installed, or no
 * Secret Service is running (typical on a minimal window manager or in a container). An application can catch this and
 * fall back to something else, such as asking the user each time.
 */
public class CredentialStoreUnavailableException extends CredentialStoreException {

    public CredentialStoreUnavailableException(String message) {
        super(message);
    }

    public CredentialStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
