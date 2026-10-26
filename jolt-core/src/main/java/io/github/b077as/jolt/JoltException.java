package io.github.b077as.jolt;

/** Thrown when the platform layer fails or is unavailable. */
public class JoltException extends RuntimeException {

    public JoltException(String message) {
        super(message);
    }

    public JoltException(String message, Throwable cause) {
        super(message, cause);
    }
}
