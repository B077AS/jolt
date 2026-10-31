package io.github.b077as.jolt;

/**
 * Settings for {@link WebWindow#loadResources(AppResources, RequestHandler, ResourceOptions)}.
 *
 * @param maxRequestBytes the largest request body a {@link RequestHandler} receives; a bigger one is answered with 413 and never
 *                        reaches it. Bodies are held in memory (once while they are read and once in the {@link AppRequest}), so
 *                        set this to what your uploads need, not higher
 * @param startPath       the address, inside the app, the window opens on: a path that starts with a single {@code /}, optionally
 *                        followed by a query and a fragment (for example {@code /message/42?theme=dark}). The default is {@code /}.
 *                        A pop-out window uses it to open on its own page, see {@link OpenWindowRequest#path()}
 */
public record ResourceOptions(int maxRequestBytes, String startPath) {

    /** 8 MB. */
    public static final int DEFAULT_MAX_REQUEST_BYTES = 8 * 1024 * 1024;
    /** 1 GB: the bodies live in a Java byte array, so there is a ceiling whatever the machine has. */
    public static final int LIMIT_MAX_REQUEST_BYTES = 1024 * 1024 * 1024;

    public ResourceOptions {
        if (maxRequestBytes < 0 || maxRequestBytes > LIMIT_MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("maxRequestBytes must be between 0 and " + LIMIT_MAX_REQUEST_BYTES + ": " + maxRequestBytes);
        }
        startPath = startPath == null ? "/" : startPath;
        if (!io.github.b077as.jolt.internal.AppPaths.isAppPath(startPath)) {
            throw new IllegalArgumentException("startPath must be a path inside the app that starts with a single '/': " + startPath);
        }
    }

    public ResourceOptions(int maxRequestBytes) {
        this(maxRequestBytes, "/");
    }

    public static ResourceOptions defaults() {
        return new ResourceOptions(DEFAULT_MAX_REQUEST_BYTES, "/");
    }

    public ResourceOptions withMaxRequestBytes(int bytes) {
        return new ResourceOptions(bytes, startPath);
    }

    /** The address the window opens on, see {@link #startPath()}. */
    public ResourceOptions withStartPath(String path) {
        return new ResourceOptions(maxRequestBytes, path);
    }
}
