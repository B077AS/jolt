package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.Tray;
import io.github.b077as.jolt.TrayMenu;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A notification-area icon ({@code Shell_NotifyIconW}) with a native popup menu. It owns a hidden window that receives the icon's
 * mouse messages; the application's window procedure hands those to {@link #handle}. Created and used on the UI thread; the public
 * {@link Tray} methods hop there.
 */
final class WindowsTray implements Tray {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.win32");

    private static final SymbolLookup SHELL32 = SymbolLookup.libraryLookup("shell32.dll", Arena.global());
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());

    private static final MethodHandle SHELL_NOTIFY_ICON = Native.downcall(SHELL32, "Shell_NotifyIconW",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle CREATE_POPUP_MENU = Native.downcall(USER32, "CreatePopupMenu", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle APPEND_MENU = Native.downcall(USER32, "AppendMenuW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS));
    private static final MethodHandle TRACK_POPUP_MENU = Native.downcall(USER32, "TrackPopupMenuEx",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle DESTROY_MENU = Native.downcall(USER32, "DestroyMenu", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle REGISTER_WINDOW_MESSAGE = Native.downcall(USER32, "RegisterWindowMessageW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle LOAD_LIBRARY = Native.downcall(KERNEL32, "LoadLibraryW", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GET_PROC_ADDRESS = Native.downcall(KERNEL32, "GetProcAddress",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    private static final int NIM_ADD = 0, NIM_MODIFY = 1, NIM_DELETE = 2;
    private static final int NIF_MESSAGE = 0x1, NIF_ICON = 0x2, NIF_TIP = 0x4;
    /** {@code sizeof(NOTIFYICONDATAW)} on 64-bit Windows (Vista and later layout). */
    private static final int NOTIFYICONDATA_SIZE = 976;
    private static final int TIP_CHARS = 127;

    /** The callback message of the icon, sent to the hidden window. */
    static final int WM_TRAY = Win32.WM_APP + 2;
    private static final int WM_LBUTTONUP = 0x0202, WM_RBUTTONUP = 0x0205, WM_CONTEXTMENU = 0x007B, WM_NULL = 0;

    private static final int MF_STRING = 0, MF_GRAYED = 1, MF_CHECKED = 8, MF_POPUP = 0x10, MF_SEPARATOR = 0x800;
    private static final int TPM_RIGHTBUTTON = 0x2, TPM_BOTTOMALIGN = 0x20, TPM_RETURNCMD = 0x100;
    private static final int WS_POPUP = 0x80000000, WS_EX_TOOLWINDOW = 0x80;

    private static boolean menusMadeThemeAware;

    private final WindowsApplication app;
    private final MemorySegment hwnd;
    private final int taskbarCreated;
    private MemorySegment icon = MemorySegment.NULL;
    private byte[] iconData;
    private String tooltip;
    private volatile TrayMenu menu;
    private volatile Runnable clickListener = () -> {};
    private boolean closed;

    /** UI thread. */
    WindowsTray(WindowsApplication app, byte[] iconData, String tooltip) {
        this.app = app;
        this.iconData = iconData == null ? null : iconData.clone();
        this.tooltip = tooltip;
        int[] error = new int[1];
        this.hwnd = Win32.createWindow(WS_EX_TOOLWINDOW, app.className(), app.className(), WS_POPUP, 0, 0, 0, 0,
                MemorySegment.NULL, app.hInstance(), error);
        if (hwnd.equals(MemorySegment.NULL)) {
            throw new io.github.b077as.jolt.JoltException("Creating the tray window failed: Win32 error " + error[0]);
        }
        try (Arena arena = Arena.ofConfined()) {
            this.taskbarCreated = invoke(() -> (int) REGISTER_WINDOW_MESSAGE.invokeExact(Native.allocWide(arena, "TaskbarCreated")));
        }
        makeMenusThemeAware();
        loadIcon();
        notify(NIM_ADD);
    }

    MemorySegment hwnd() {
        return hwnd;
    }

    boolean owns(MemorySegment window) {
        return window.address() == hwnd.address();
    }

    // ---- Tray (any thread) ----

    @Override
    public void setIcon(byte[] data) {
        byte[] copy = data == null ? null : data.clone();
        app.dispatch(() -> {
            if (!closed && copy != null) {
                iconData = copy;
                loadIcon();
                notify(NIM_MODIFY);
            }
        });
    }

    @Override
    public void setTooltip(String text) {
        app.dispatch(() -> {
            if (!closed) {
                tooltip = text;
                notify(NIM_MODIFY);
            }
        });
    }

    @Override
    public void setMenu(TrayMenu newMenu) {
        menu = newMenu;
    }

    @Override
    public void onClick(Runnable listener) {
        clickListener = listener == null ? () -> {} : listener;
    }

    @Override
    public void close() {
        app.dispatch(this::closeNow);
    }

    /** UI thread. */
    void closeNow() {
        if (closed) {
            return;
        }
        closed = true;
        notify(NIM_DELETE);
        if (!icon.equals(MemorySegment.NULL)) {
            Win32.destroyIcon(icon);
            icon = MemorySegment.NULL;
        }
        Win32.destroyWindow(hwnd);
        app.trayClosed(this);
    }

    // ---- window messages (UI thread) ----

    /** Messages for the hidden window; returns the result, or {@code null} for default handling. */
    Long handle(int msg, long wParam, long lParam) {
        if (closed) {
            return null;
        }
        if (msg == WM_TRAY) {
            switch ((int) (lParam & 0xFFFF)) {
                case WM_LBUTTONUP -> {
                    Runnable listener = clickListener;
                    app.dispatch(listener);
                }
                case WM_RBUTTONUP, WM_CONTEXTMENU -> showMenu();
                default -> { }
            }
            return 0L;
        }
        if (taskbarCreated != 0 && msg == taskbarCreated) {
            notify(NIM_ADD); // Explorer restarted: its tray is empty, put the icon back
            return 0L;
        }
        return null;
    }

    private void showMenu() {
        TrayMenu current = menu;
        if (current == null || current.items().isEmpty()) {
            return;
        }
        Map<Integer, Runnable> actions = new HashMap<>();
        MemorySegment popup = buildMenu(current.items(), actions, new int[] {1});
        try {
            int[] cursor = Win32.cursorPosition();
            Win32.setForegroundWindow(hwnd); // without it the menu does not close when the user clicks elsewhere
            int chosen = invoke(() -> (int) TRACK_POPUP_MENU.invokeExact(popup, TPM_RETURNCMD | TPM_RIGHTBUTTON | TPM_BOTTOMALIGN,
                    cursor[0], cursor[1], hwnd, MemorySegment.NULL));
            Win32.postMessage(hwnd, WM_NULL, 0, 0);
            Runnable action = actions.get(chosen);
            if (action != null) {
                app.dispatch(action);
            }
        } finally {
            invoke(() -> (int) DESTROY_MENU.invokeExact(popup));
        }
    }

    MemorySegment buildMenu(List<TrayMenu.Item> items, Map<Integer, Runnable> actions, int[] nextId) {
        MemorySegment popup = invoke(() -> (MemorySegment) CREATE_POPUP_MENU.invokeExact());
        try (Arena arena = Arena.ofConfined()) {
            for (TrayMenu.Item item : items) {
                switch (item) {
                    case TrayMenu.Separator separator -> append(popup, MF_SEPARATOR, 0, MemorySegment.NULL);
                    case TrayMenu.Action action -> {
                        int id = nextId[0]++;
                        append(popup, MF_STRING | (action.enabled() ? 0 : MF_GRAYED), id, label(arena, action.label()));
                        actions.put(id, action.onSelect());
                    }
                    case TrayMenu.Check check -> {
                        int id = nextId[0]++;
                        append(popup, MF_STRING | (check.checked() ? MF_CHECKED : 0) | (check.enabled() ? 0 : MF_GRAYED), id,
                                label(arena, check.label()));
                        boolean wanted = !check.checked();
                        actions.put(id, () -> check.onToggle().accept(wanted));
                    }
                    case TrayMenu.Submenu submenu -> {
                        MemorySegment child = buildMenu(submenu.items(), actions, nextId);
                        append(popup, MF_POPUP | (submenu.enabled() ? 0 : MF_GRAYED), child.address(), label(arena, submenu.label()));
                    }
                }
            }
        }
        return popup;
    }

    /** {@code &} would underline the next letter as a keyboard shortcut; a label is shown as written. */
    private static MemorySegment label(Arena arena, String text) {
        return Native.allocWide(arena, text.replace("&", "&&"));
    }

    private static void append(MemorySegment menu, int flags, long idOrSubmenu, MemorySegment text) {
        invoke(() -> (int) APPEND_MENU.invokeExact(menu, flags, idOrSubmenu, text));
    }

    // ---- the icon ----

    private void loadIcon() {
        if (iconData == null) {
            return;
        }
        int size = Win32.systemMetricForDpi(Win32.SM_CXSMICON, Win32.dpiForWindow(hwnd));
        MemorySegment fresh = WindowsWindow.iconOfSize(iconData, size);
        if (fresh.equals(MemorySegment.NULL)) {
            LOG.log(System.Logger.Level.WARNING, "The tray icon could not be decoded; it must be PNG or ICO data");
            return;
        }
        if (!icon.equals(MemorySegment.NULL)) {
            Win32.destroyIcon(icon);
        }
        icon = fresh;
    }

    private void notify(int command) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(NOTIFYICONDATA_SIZE, 8);
            data.set(JAVA_INT, 0, NOTIFYICONDATA_SIZE);     // cbSize
            data.set(ADDRESS, 8, hwnd);                     // hWnd
            data.set(JAVA_INT, 16, 1);                      // uID
            int flags = NIF_MESSAGE | NIF_TIP;
            data.set(JAVA_INT, 24, WM_TRAY);                // uCallbackMessage
            if (!icon.equals(MemorySegment.NULL)) {
                flags |= NIF_ICON;
                data.set(ADDRESS, 32, icon);                // hIcon
            }
            data.set(JAVA_INT, 20, flags);                  // uFlags
            if (tooltip != null) {
                String text = tooltip.length() > TIP_CHARS ? tooltip.substring(0, TIP_CHARS) : tooltip;
                for (int i = 0; i < text.length(); i++) {
                    data.set(java.lang.foreign.ValueLayout.JAVA_CHAR, 40 + 2L * i, text.charAt(i)); // szTip
                }
            }
            int ok = invoke(() -> (int) SHELL_NOTIFY_ICON.invokeExact(command, data));
            if (ok == 0 && command != NIM_DELETE) {
                LOG.log(System.Logger.Level.WARNING, "Shell_NotifyIconW({0}) failed", command);
            }
        }
    }

    /**
     * Popup menus follow the system's light or dark setting only if the process opts in; Windows has no documented call for it, but
     * two exports of uxtheme.dll (ordinals 135 and 136, in every release since 1809) do it. Best effort: if they are missing the
     * menus stay light.
     */
    private static void makeMenusThemeAware() {
        if (menusMadeThemeAware) {
            return;
        }
        menusMadeThemeAware = true;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment uxtheme = invoke(() -> (MemorySegment) LOAD_LIBRARY.invokeExact(Native.allocWide(arena, "uxtheme.dll")));
            if (uxtheme.equals(MemorySegment.NULL)) {
                return;
            }
            MemorySegment setMode = invoke(() -> (MemorySegment) GET_PROC_ADDRESS.invokeExact(uxtheme, MemorySegment.ofAddress(135)));
            MemorySegment flush = invoke(() -> (MemorySegment) GET_PROC_ADDRESS.invokeExact(uxtheme, MemorySegment.ofAddress(136)));
            if (setMode.equals(MemorySegment.NULL)) {
                return;
            }
            MethodHandle preferredAppMode = Native.LINKER.downcallHandle(setMode, FunctionDescriptor.of(JAVA_INT, JAVA_INT));
            int ignored = (int) preferredAppMode.invokeExact(1); // APPMODE_ALLOWDARK: follow the system setting
            if (!flush.equals(MemorySegment.NULL)) {
                MethodHandle flushMenuThemes = Native.LINKER.downcallHandle(flush, FunctionDescriptor.ofVoid());
                flushMenuThemes.invokeExact();
            }
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.DEBUG, "Dark popup menus are not available: {0}", t.toString());
        }
    }

    // ---- plumbing ----

    @FunctionalInterface
    private interface Call<T> {
        T run() throws Throwable;
    }

    private static <T> T invoke(Call<T> call) {
        try {
            return call.run();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
