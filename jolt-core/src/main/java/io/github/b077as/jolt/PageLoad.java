package io.github.b077as.jolt;

/**
 * The window's top-level page finished loading. Use {@link WebWindow#onLoad} to hear about it.
 *
 * @param url        the address that was loaded, after any redirects
 * @param httpStatus the HTTP status of the document (below 400), or 0 if there was none or the web view did not say
 */
public record PageLoad(String url, int httpStatus) {}
