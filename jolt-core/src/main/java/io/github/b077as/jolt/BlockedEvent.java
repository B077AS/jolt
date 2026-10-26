package io.github.b077as.jolt;

/**
 * Something the window's {@link SecurityPolicy} stopped. Use {@link WebWindow#onBlocked} to react, for example by
 * opening a blocked link in the user's browser.
 *
 * @param kind what was blocked
 * @param url  the address involved: the target of a navigation or new window, or the page that sent a message or asked for
 *             a permission. May be {@code null} if the platform did not say
 */
public record BlockedEvent(Kind kind, String url) {

    public enum Kind {
        /** A navigation to an origin that is not trusted. */
        NAVIGATION,
        /** A page tried to open a new window. */
        NEW_WINDOW,
        /** A page asked for a permission such as camera or location. */
        PERMISSION,
        /** A message from an origin that is not trusted was ignored. */
        MESSAGE
    }
}
