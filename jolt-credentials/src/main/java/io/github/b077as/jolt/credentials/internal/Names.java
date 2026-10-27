package io.github.b077as.jolt.credentials.internal;

/** Checks every string that is about to be handed to native code. Not part of the public API. */
public final class Names {

    public static final int MAX_NAME_LENGTH = 255;

    private Names() {}

    /** A service becomes the first part of a Windows target name ({@code service/account}), so it cannot contain the separator. */
    public static void checkService(String service) {
        checkName("service", service);
        if (service.indexOf('/') >= 0) {
            throw new IllegalArgumentException("service must not contain '/': " + service);
        }
    }

    public static void checkAccount(String account) {
        checkName("account", account);
    }

    public static void checkSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("secret must not be empty; use delete to remove one");
        }
        if (secret.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("secret must not contain NUL characters");
        }
    }

    private static void checkName(String what, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        if (value.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(what + " must be at most " + MAX_NAME_LENGTH + " characters");
        }
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(what + " must not contain NUL characters");
        }
    }
}
