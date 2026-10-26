package io.github.b077as.jolt.spi;

import io.github.b077as.jolt.AppOptions;
import io.github.b077as.jolt.Application;

/**
 * Service provider interface implemented once per operating system backend and registered through
 * {@link java.util.ServiceLoader} ({@code META-INF/services/io.github.b077as.jolt.spi.Platform}).
 */
public interface Platform {

    /** Short name for diagnostics, for example {@code "windows-webview2"}. */
    String name();

    /**
     * Whether this backend can run here: right operating system and architecture, and its native prerequisites
     * (for example the WebView2 runtime) present. Must be cheap and must not throw.
     */
    boolean isSupported();

    /** Why {@link #isSupported()} is false, for the error message when no backend fits. */
    default String unsupportedReason() {
        return "not supported on this system";
    }

    Application createApplication(AppOptions options);
}
