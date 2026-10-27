package io.github.b077as.jolt;

/**
 * Settings for {@link WebWindow#loadResources(AppResources, RequestHandler, ResourceOptions)}.
 *
 * @param maxRequestBytes the largest request body a {@link RequestHandler} receives; a bigger one is answered with 413 and never
 *                        reaches it. Bodies are held in memory (once while they are read and once in the {@link AppRequest}), so
 *                        set this to what your uploads need, not higher
 */
public record ResourceOptions(int maxRequestBytes) {

    /** 8 MB. */
    public static final int DEFAULT_MAX_REQUEST_BYTES = 8 * 1024 * 1024;
    /** 1 GB: the bodies live in a Java byte array, so there is a ceiling whatever the machine has. */
    public static final int LIMIT_MAX_REQUEST_BYTES = 1024 * 1024 * 1024;

    public ResourceOptions {
        if (maxRequestBytes < 0 || maxRequestBytes > LIMIT_MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("maxRequestBytes must be between 0 and " + LIMIT_MAX_REQUEST_BYTES + ": " + maxRequestBytes);
        }
    }

    public static ResourceOptions defaults() {
        return new ResourceOptions(DEFAULT_MAX_REQUEST_BYTES);
    }

    public ResourceOptions withMaxRequestBytes(int bytes) {
        return new ResourceOptions(bytes);
    }
}
