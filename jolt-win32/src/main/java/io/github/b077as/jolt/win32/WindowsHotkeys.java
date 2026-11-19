package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.Hotkey;
import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.Shortcut;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;

/**
 * The application's global hotkeys ({@code RegisterHotKey}). They belong to the hidden dispatch window, which receives
 * {@code WM_HOTKEY}. UI thread only, except {@link Registered#close()}.
 */
final class WindowsHotkeys {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.win32");

    private static final int MOD_NOREPEAT = 0x4000;
    private static final int ERROR_HOTKEY_ALREADY_REGISTERED = 1409;

    private final WindowsApplication app;
    private final Map<Integer, Registered> active = new HashMap<>();
    private int nextId = 1;

    WindowsHotkeys(WindowsApplication app) {
        this.app = app;
    }

    /** UI thread. */
    Hotkey register(MemorySegment window, Shortcut shortcut, Runnable onPress) {
        if (window == null || window.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("hotkeys can only be registered while the application is running");
        }
        for (Registered other : active.values()) {
            if (other.shortcut.equals(shortcut)) {
                throw new JoltException(shortcut + " is already registered by this application");
            }
        }
        int id = nextId++;
        int[] error = new int[1];
        if (!Win32.registerHotKey(window, id, WindowsKeys.hotkeyModifiers(shortcut) | MOD_NOREPEAT, WindowsKeys.virtualKey(shortcut), error)) {
            throw new JoltException(error[0] == ERROR_HOTKEY_ALREADY_REGISTERED
                    ? shortcut + " is already in use by another program"
                    : "Windows refused the hotkey " + shortcut + " (error " + error[0] + ")");
        }
        Registered hotkey = new Registered(id, window, shortcut, onPress);
        active.put(id, hotkey);
        return hotkey;
    }

    /** {@code WM_HOTKEY} arrived for {@code id}. UI thread. */
    void fire(int id) {
        Registered hotkey = active.get(id);
        if (hotkey == null) {
            return;
        }
        try {
            hotkey.onPress.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "a hotkey callback failed", e);
        }
    }

    /** Gives every hotkey back; the application is ending. UI thread. */
    void releaseAll() {
        for (Registered hotkey : new java.util.ArrayList<>(active.values())) {
            hotkey.release();
        }
    }

    private final class Registered implements Hotkey {
        private final int id;
        private final MemorySegment window;
        private final Shortcut shortcut;
        private final Runnable onPress;

        private Registered(int id, MemorySegment window, Shortcut shortcut, Runnable onPress) {
            this.id = id;
            this.window = window;
            this.shortcut = shortcut;
            this.onPress = onPress;
        }

        @Override
        public Shortcut shortcut() {
            return shortcut;
        }

        @Override
        public void close() {
            app.dispatch(this::release);
        }

        /** UI thread. */
        private void release() {
            if (active.remove(id) != null) {
                Win32.unregisterHotKey(window, id);
            }
        }

        @Override
        public String toString() {
            return "Hotkey[" + shortcut + "]";
        }
    }
}
