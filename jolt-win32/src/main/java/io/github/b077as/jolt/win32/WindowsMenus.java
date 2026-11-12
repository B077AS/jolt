package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.Menu;
import io.github.b077as.jolt.Shortcut;
import io.github.b077as.jolt.internal.MenuLabels;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Builds Win32 menus ({@code HMENU}) from a {@link Menu}: the menu bar of a window and the popup of a right click. UI thread only.
 */
final class WindowsMenus {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.win32");

    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());

    private static final MethodHandle CREATE_MENU = Native.downcall(USER32, "CreateMenu", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle CREATE_POPUP_MENU = Native.downcall(USER32, "CreatePopupMenu", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle APPEND_MENU = Native.downcall(USER32, "AppendMenuW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS));
    private static final MethodHandle CHECK_MENU_ITEM = Native.downcall(USER32, "CheckMenuItem",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle GET_MENU_STATE = Native.downcall(USER32, "GetMenuState",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle TRACK_POPUP_MENU_EX = Native.downcall(USER32, "TrackPopupMenuEx",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));

    static final int MF_STRING = 0, MF_GRAYED = 1, MF_CHECKED = 8, MF_POPUP = 0x10, MF_SEPARATOR = 0x800;
    private static final int MF_BYCOMMAND = 0, MF_UNCHECKED = 0;
    private static final int TPM_RIGHTBUTTON = 0x2, TPM_RETURNCMD = 0x100, TPM_NONOTIFY = 0x80;

    /** Command ids start here: above the ids of the taskbar buttons and well below the system commands ({@code 0xF000}). */
    static final int FIRST_COMMAND_ID = 0x1000;

    private WindowsMenus() {}

    /** A native menu together with what each of its command ids stands for. Destroy it with {@link #destroy()} unless it is a window's bar. */
    static final class Built {
        final MemorySegment handle;
        private final Map<Integer, Menu.Item> entries = new HashMap<>();
        private final Map<Shortcut, Integer> accelerators = new HashMap<>();
        private int nextId = FIRST_COMMAND_ID;

        private Built(MemorySegment handle) {
            this.handle = handle;
        }

        boolean handles(int commandId) {
            return entries.containsKey(commandId);
        }

        /** The command id the key combination triggers, or {@code null}. */
        Integer commandFor(Shortcut shortcut) {
            return accelerators.get(shortcut);
        }

        boolean hasAccelerators() {
            return !accelerators.isEmpty();
        }

        /**
         * Does what choosing the entry does: runs an action, or flips a check mark and tells the callback the new state. With
         * {@code live} (a window's menu bar, which stays) the mark is read from and written to the native menu; a popup is gone by the
         * time the choice is carried out, so a check there asks for the opposite of the state it was built with.
         */
        void activate(int commandId, boolean live) {
            Menu.Item item = entries.get(commandId);
            try {
                switch (item) {
                    case Menu.Action action -> {
                        if (action.enabled()) {
                            action.onSelect().run();
                        }
                    }
                    case Menu.Check check -> {
                        if (check.enabled()) {
                            boolean wanted = live ? !isChecked(handle, commandId) : !check.checked();
                            if (live) {
                                setChecked(handle, commandId, wanted);
                            }
                            check.onToggle().accept(wanted);
                        }
                    }
                    case null, default -> { }
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "a menu callback failed", e);
            }
        }

        /** Whether the entry is enabled; accelerators of a disabled entry do nothing. */
        boolean isEnabled(int commandId) {
            return switch (entries.get(commandId)) {
                case Menu.Action action -> action.enabled();
                case Menu.Check check -> check.enabled();
                case null, default -> false;
            };
        }

        void destroy() {
            Win32.destroyMenu(handle);
        }
    }

    /** Builds a menu bar ({@code bar}) or a popup menu. */
    static Built build(Menu menu, boolean bar) {
        MemorySegment root = invoke(() -> (MemorySegment) (bar ? CREATE_MENU : CREATE_POPUP_MENU).invokeExact());
        Built built = new Built(root);
        try {
            fill(root, menu.items(), built);
        } catch (RuntimeException | Error e) {
            built.destroy();
            throw e;
        }
        return built;
    }

    private static void fill(MemorySegment hmenu, List<Menu.Item> items, Built built) {
        try (Arena arena = Arena.ofConfined()) {
            for (Menu.Item item : items) {
                switch (item) {
                    case Menu.Separator separator -> append(hmenu, MF_SEPARATOR, 0, MemorySegment.NULL);
                    case Menu.Action action -> {
                        int id = built.nextId++;
                        built.entries.put(id, action);
                        remember(built, action.accelerator(), id, action.enabled());
                        append(hmenu, MF_STRING | (action.enabled() ? 0 : MF_GRAYED), id, text(arena, action.label(), action.accelerator()));
                    }
                    case Menu.Check check -> {
                        int id = built.nextId++;
                        built.entries.put(id, check);
                        remember(built, check.accelerator(), id, check.enabled());
                        append(hmenu, MF_STRING | (check.checked() ? MF_CHECKED : 0) | (check.enabled() ? 0 : MF_GRAYED), id,
                                text(arena, check.label(), check.accelerator()));
                    }
                    case Menu.Submenu submenu -> {
                        MemorySegment child = invoke(() -> (MemorySegment) CREATE_POPUP_MENU.invokeExact());
                        fill(child, submenu.items(), built);
                        append(hmenu, MF_POPUP | (submenu.enabled() ? 0 : MF_GRAYED), child.address(), text(arena, submenu.label(), null));
                    }
                }
            }
        }
    }

    /** The first entry to claim a key combination keeps it. */
    private static void remember(Built built, Shortcut shortcut, int id, boolean enabled) {
        if (shortcut != null && enabled) {
            Integer taken = built.accelerators.putIfAbsent(shortcut, id);
            if (taken != null) {
                LOG.log(System.Logger.Level.WARNING, "the accelerator {0} is on more than one menu entry; the first one keeps it", shortcut);
            }
        }
    }

    /** The label, then (after a tab, which right-aligns it) the accelerator, as Windows shows menu shortcuts. */
    private static MemorySegment text(Arena arena, String label, Shortcut accelerator) {
        String text = MenuLabels.forWin32(label);
        return Native.allocWide(arena, accelerator == null ? text : text + "\t" + accelerator);
    }

    private static void append(MemorySegment menu, int flags, long idOrSubmenu, MemorySegment text) {
        int ok = invoke(() -> (int) APPEND_MENU.invokeExact(menu, flags, idOrSubmenu, text));
        if (ok == 0) {
            throw new io.github.b077as.jolt.JoltException("AppendMenuW failed");
        }
    }

    static boolean isChecked(MemorySegment menu, int commandId) {
        int state = invoke(() -> (int) GET_MENU_STATE.invokeExact(menu, commandId, MF_BYCOMMAND));
        return state != -1 && (state & MF_CHECKED) != 0;
    }

    static void setChecked(MemorySegment menu, int commandId, boolean checked) {
        int ignored = invoke(() -> (int) CHECK_MENU_ITEM.invokeExact(menu, commandId, MF_BYCOMMAND | (checked ? MF_CHECKED : MF_UNCHECKED)));
    }

    /** Shows the popup at a screen position and returns the command id chosen, or 0. Blocks in the menu's own loop. */
    static int track(MemorySegment popup, MemorySegment owner, int x, int y) {
        return invoke(() -> (int) TRACK_POPUP_MENU_EX.invokeExact(popup, TPM_RETURNCMD | TPM_RIGHTBUTTON | TPM_NONOTIFY, x, y, owner,
                MemorySegment.NULL));
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
