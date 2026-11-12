package io.github.b077as.jolt;

/**
 * A system-wide keyboard shortcut registered with {@link Application#registerHotkey}. It works while another application has the focus.
 * Close it to give the key combination back.
 */
public interface Hotkey extends AutoCloseable {

    /** The key combination. */
    Shortcut shortcut();

    /** Unregisters the shortcut. Safe from any thread, and harmless when called again. */
    @Override
    void close();
}
