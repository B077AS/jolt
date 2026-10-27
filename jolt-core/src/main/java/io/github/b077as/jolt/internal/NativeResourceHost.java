package io.github.b077as.jolt.internal;

/**
 * Implemented by windows whose web view can answer requests for the app's own files itself, with no socket involved
 * (a WebView2 request interceptor, a WebKitGTK URI scheme). {@code WebWindow.loadResources} prefers it and falls back to the
 * loopback server ({@link AssetServer}) for a window that does not implement it, or that reports it cannot.
 */
public interface NativeResourceHost {

    /**
     * Makes the web view serve {@code service} under the app's own origin, trusts that origin, and navigates to its root.
     * Safe to call from any thread.
     *
     * @return false if this platform or web view version cannot do it (nothing has changed then), true otherwise
     */
    boolean loadResources(ResourceService service);
}
