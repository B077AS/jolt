package io.github.b077as.jolt;

/**
 * The window's top-level page could not be loaded. Use {@link WebWindow#onLoadFailed} to react, for example by showing a
 * page of your own or trying again.
 *
 * <p>Not reported: navigations the {@link SecurityPolicy} blocked (see {@link WebWindow#onBlocked}), navigations that
 * became downloads, and loads that were abandoned because another one started.
 *
 * @param url        the address that failed
 * @param reason     what went wrong, as far as the web view says
 * @param httpStatus the status the server answered with for {@link Reason#HTTP_STATUS}, otherwise 0
 * @param detail     the web view's own description, for logs. Its wording differs between platforms
 */
public record LoadFailure(String url, Reason reason, int httpStatus, String detail) {

    public enum Reason {
        /** The server (or the app's own files and request handler) answered with a status of 400 or more. */
        HTTP_STATUS,
        /** The host name could not be resolved. */
        HOST_NOT_FOUND,
        /** No connection, or it broke: nothing listens on the port, the network is down, the connection was reset. */
        CONNECTION,
        /** The server took too long. */
        TIMEOUT,
        /** The server's certificate was rejected. */
        CERTIFICATE,
        /** Anything else. */
        OTHER
    }
}
