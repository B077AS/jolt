package io.github.b077as.jolt;

import io.github.b077as.jolt.spi.Platforms;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A running desktop application: owns the native UI event loop and the windows on it.
 *
 * <p><b>Threading.</b> {@link #run(Runnable)} blocks until the application ends. Windows must be created on the UI
 * thread, which is where {@code onStart} runs. Which thread that is depends on the platform: on Windows it is the
 * thread that called {@code run}; on Linux, GTK and WebKit must stay on one thread for the life of the process, so
 * the backend runs the loop on its own thread and the caller just waits. Do not rely on either; use
 * {@link #isUiThread()}. Every other method on {@link WebWindow} and {@link Application} may be called from any
 * thread and is marshalled to the UI thread for you. Never block the UI thread waiting on a future that the UI
 * thread has to complete.
 *
 * <p>Only one application can run at a time in a process. Tasks passed to {@link #dispatch} after the loop has
 * ended are discarded or may never run.
 */
public interface Application extends AutoCloseable {

    /** Creates an application on the best supported platform backend found on the class path. */
    static Application create(AppOptions options) {
        return Platforms.select().createApplication(options);
    }

    /**
     * Initializes the platform on the calling thread, invokes {@code onStart} on it, then runs the event loop
     * until {@link #quit()} is called or the last window closes. Blocks.
     */
    void run(Runnable onStart);

    /** Equivalent to {@code run(() -> {})}. */
    default void run() {
        run(() -> {});
    }

    /** Asks the event loop to stop. Safe from any thread. */
    void quit();

    /** Runs {@code task} on the UI thread, in submission order. Safe from any thread. */
    void dispatch(Runnable task);

    /** Whether the calling thread is the UI thread. */
    boolean isUiThread();

    /** Runs {@code task} on the UI thread and completes the returned future with its result. */
    default <T> CompletableFuture<T> submit(Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        dispatch(() -> {
            try {
                result.complete(task.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    /**
     * Opens a web or mail address in the user's default browser or mail program. Only {@code http}, {@code https}
     * and {@code mailto} are accepted; anything else (for example {@code file:}) throws
     * {@link IllegalArgumentException}, because handing it to the shell could start a program. The future completes
     * with whether the system accepted the request, not whether the page loaded.
     */
    default CompletableFuture<Boolean> openExternal(java.net.URI uri) {
        throw new UnsupportedOperationException("this platform cannot open external addresses");
    }

    /**
     * Shows a system notification. The future completes with whether the system accepted it; it does not say whether
     * the user saw it (they may have notifications turned off, or be in a focus mode). Safe from any thread.
     *
     * <p>Windows: a WinRT toast. The first call registers {@code appId} under
     * {@code HKCU\Software\Classes\AppUserModelId} so Windows has a name to show; nothing is installed. Linux: the
     * desktop's notification service ({@code org.freedesktop.Notifications}); the future completes with {@code false}
     * when there is none.
     */
    default CompletableFuture<Boolean> notify(Notification notification) {
        throw new UnsupportedOperationException("this platform cannot show notifications");
    }

    /**
     * Whether the desktop can show a tray icon. Windows always can. On Linux it needs a status notifier host: KDE Plasma and many other
     * desktops have one, GNOME shows tray icons only with its AppIndicator extension. Check it before relying on
     * {@link CloseBehavior#HIDE}: with no tray, a hidden window has no way back. It is a snapshot; a desktop may gain or lose a host later.
     */
    default boolean isTraySupported() {
        return false;
    }

    /**
     * Adds an icon to the system tray. Must be called on the UI thread. Close the returned {@link Tray} to remove it; it goes away by
     * itself when the application ends.
     *
     * @param icon    PNG data or Windows {@code .ico} bytes, as for {@link WebWindow#setIcon}
     * @param tooltip the text shown when the pointer rests on it, or {@code null}
     * @throws UnsupportedOperationException if {@link #isTraySupported()} is false
     */
    default Tray createTray(byte[] icon, String tooltip) {
        throw new UnsupportedOperationException("this platform cannot show a tray icon");
    }

    /** The system clipboard, see {@link Clipboard}. Safe from any thread. */
    default Clipboard clipboard() {
        throw new UnsupportedOperationException("this platform has no clipboard support");
    }

    /**
     * Whether {@link #registerHotkey} can work here. Windows: yes. Linux: not yet (X11 would need key grabs and Wayland has no such API
     * outside the desktop portals), so it is {@code false} there.
     */
    default boolean supportsGlobalHotkeys() {
        return false;
    }

    /**
     * Registers a system-wide keyboard shortcut, such as {@code Ctrl+Alt+M} to bring a tray application to the front. {@code onPress}
     * runs on the UI thread whenever the user presses it, whichever application has the focus, and the key press is not passed on to
     * that application. The future completes with a {@link Hotkey} to close, or fails with {@link JoltException} if the combination is
     * already taken (by another program, or by an earlier hotkey of this one) or the system refused it. Safe from any thread.
     *
     * <pre>{@code
     * app.registerHotkey(Shortcut.parse("Ctrl+Alt+M"), window::focus)
     *    .exceptionally(t -> { log.warn("Ctrl+Alt+M is taken"); return null; });
     * }</pre>
     * A hotkey must include Ctrl, Alt or Meta, or be a function key (see {@link Shortcut#isGlobalHotkeyCandidate()}): a bare letter
     * would be taken away from every program. Hotkeys end when the application does.
     *
     * @throws IllegalArgumentException      if the shortcut is not fit to be a global hotkey
     * @throws UnsupportedOperationException if {@link #supportsGlobalHotkeys()} is false
     */
    default CompletableFuture<Hotkey> registerHotkey(Shortcut shortcut, Runnable onPress) {
        throw new UnsupportedOperationException("this platform cannot register global hotkeys");
    }

    /** Starting the application when the user signs in, see {@link Autostart}. */
    default Autostart autostart() {
        throw new UnsupportedOperationException("this platform cannot start applications at sign-in");
    }

    /**
     * Sets the extra entries of the menu that opens when the user right-clicks the application's taskbar or launcher icon: tasks
     * such as "New incognito window" and, on Windows, your own categories such as "Recent projects" (see {@link JumpList}).
     * Choosing an entry starts the program with the entry's arguments, so use {@link SingleInstance} to hand them to the running
     * copy. An empty list removes the entries. Safe from any thread; call it again whenever the entries change.
     *
     * <p><b>Windows</b>: the windows are given the application's id ({@code AppOptions.appId}) as their AppUserModelID, so the
     * entries sit on this application's icon whatever program started it. The id must be at most 128 characters without spaces.
     * <b>Linux</b>: best effort. The tasks are written as <em>desktop actions</em> into
     * {@code $XDG_DATA_HOME/applications/<appId>.desktop}, which GNOME, KDE and others show on the launcher icon when the program
     * is matched to that file (a user file that Jolt did not write is never changed). Categories are left out there.
     * {@link #supportsJumpList()} says whether anything can be shown.
     */
    default void setJumpList(JumpList list) {}

    /** Whether {@link #setJumpList} can do anything on this platform. */
    default boolean supportsJumpList() {
        return false;
    }

    /** Opening the application for links such as {@code myapp://...}, see {@link UrlSchemes}. */
    default UrlSchemes urlSchemes() {
        throw new UnsupportedOperationException("this platform cannot register URL schemes");
    }

    /** Creates a window hosting a web view. Must be called on the UI thread. */
    WebWindow createWindow(WindowOptions options);

    /**
     * The windows that are open right now, in no particular order. A snapshot: windows opened or closed afterwards are not
     * reflected in it. Safe from any thread.
     */
    java.util.List<WebWindow> windows();

    /** Releases native resources. Call after {@link #run(Runnable)} has returned. */
    @Override
    void close();
}
