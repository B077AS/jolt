package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.TaskbarButton;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The thumbnail toolbar of one window: the strip of buttons under its taskbar preview ({@code ITaskbarList3}). UI thread only,
 * except {@link #reserve}.
 *
 * <p>Windows creates the toolbar when the window's taskbar button exists, and says so with the registered message
 * {@code TaskbarButtonCreated} (again after Explorer restarts, when the toolbar is gone and has to be added anew). Buttons can be
 * added only once, so the number is fixed by the first list; later lists update those buttons and hide the ones they do not use.
 */
final class WindowsTaskbarButtons {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.win32");

    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
    private static final MethodHandle REGISTER_WINDOW_MESSAGE = Native.downcall(USER32, "RegisterWindowMessageW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final String CLSID_TASKBAR_LIST = "56fdf344-fd6d-11d0-958a-006097c9a090";
    private static final String IID_TASKBAR_LIST3 = "ea1afb91-9e28-4b86-90e9-9e9f8a5eefaf";
    // vtable slots of ITaskbarList3 (IUnknown 0-2, ITaskbarList 3-7, ITaskbarList2 8)
    private static final int HR_INIT = 3, THUMB_BAR_ADD_BUTTONS = 15, THUMB_BAR_UPDATE_BUTTONS = 16;
    private static final FunctionDescriptor HR_HWND_INT_PTR = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);

    // THUMBBUTTON on 64-bit Windows: DWORD mask (0), UINT id (4), UINT bitmap (8), HICON icon (16), WCHAR tip[260] (24), DWORD flags (544); 552 bytes
    private static final int BUTTON_SIZE = 552, OFFSET_ICON = 16, OFFSET_TIP = 24, OFFSET_FLAGS = 544;
    private static final int THB_ICON = 0x2, THB_TOOLTIP = 0x4, THB_FLAGS = 0x8;
    private static final int THBF_DISABLED = 0x1, THBF_DISMISSONCLICK = 0x2, THBF_HIDDEN = 0x8;
    /** HIWORD of {@code wParam} in the {@code WM_COMMAND} a press sends. */
    static final int THBN_CLICKED = 0x1800;

    private final MemorySegment hwnd;
    private final int buttonCreatedMessage;
    private MemorySegment taskbar = MemorySegment.NULL;
    private List<TaskbarButton> buttons = List.of();
    private final List<MemorySegment> icons = new ArrayList<>();
    private boolean buttonExists;
    private boolean added;
    private int slots; // guarded by this, read on the UI thread through the same lock
    /** The HRESULT of the last add or update, or {@code Integer.MIN_VALUE} before there was one. For tests. */
    volatile int lastResult = Integer.MIN_VALUE;

    WindowsTaskbarButtons(MemorySegment hwnd) {
        this.hwnd = hwnd;
        try (Arena arena = Arena.ofConfined()) {
            this.buttonCreatedMessage = (int) REGISTER_WINDOW_MESSAGE.invokeExact(Native.allocWide(arena, "TaskbarButtonCreated"));
        } catch (Throwable t) {
            throw t instanceof RuntimeException r ? r : new IllegalStateException(t);
        }
    }

    /** Checks {@code count} against the limits and, for the first non-empty list, fixes how many buttons there are. Any thread. */
    synchronized void reserve(int count) {
        if (count > TaskbarButton.MAX_BUTTONS) {
            throw new IllegalArgumentException("at most " + TaskbarButton.MAX_BUTTONS + " taskbar buttons");
        }
        if (slots == 0) {
            slots = count;
        } else if (count > slots) {
            throw new IllegalArgumentException("Windows allows adding the taskbar buttons only once: this window has " + slots
                    + " and cannot get " + count);
        }
    }

    /** {@code msg} is the message Windows sends when the window's taskbar button (and so its toolbar) is created. */
    boolean isButtonCreatedMessage(int msg) {
        return buttonCreatedMessage != 0 && msg == buttonCreatedMessage;
    }

    void onButtonCreated() {
        buttonExists = true;
        added = false; // a new button has no toolbar yet
        apply();
    }

    void set(List<TaskbarButton> list) {
        buttons = List.copyOf(list);
        apply();
    }

    /** A {@code WM_COMMAND}: runs the callback if it is a press on one of our buttons. */
    boolean onCommand(long wParam) {
        if (((wParam >> 16) & 0xFFFF) != THBN_CLICKED) {
            return false;
        }
        int index = (int) (wParam & 0xFFFF);
        if (index < buttons.size()) {
            try {
                buttons.get(index).onClick().run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "taskbar button callback failed", e);
            }
        }
        return true;
    }

    private void apply() {
        int total;
        synchronized (this) {
            total = slots;
        }
        if (!buttonExists || total == 0) {
            return; // nothing to show yet, or the button is not there yet: onButtonCreated comes back here
        }
        if (taskbar.equals(MemorySegment.NULL)) {
            taskbar = Win32.coCreateInstance(CLSID_TASKBAR_LIST, IID_TASKBAR_LIST3);
            if (taskbar.equals(MemorySegment.NULL) || Com.call(taskbar, HR_INIT, Com.HR_VOID) < 0) {
                LOG.log(System.Logger.Level.WARNING, "The taskbar interface is not available; taskbar buttons are not shown");
                release();
                return;
            }
        }
        List<MemorySegment> previous = new ArrayList<>(icons);
        icons.clear();
        int dpi = Win32.dpiForWindow(hwnd);
        int pixels = Math.max(16, 16 * dpi / 96);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment array = arena.allocate((long) BUTTON_SIZE * total, 8);
            for (int i = 0; i < total; i++) {
                MemorySegment item = array.asSlice((long) BUTTON_SIZE * i, BUTTON_SIZE);
                item.set(JAVA_INT, 4, i); // the id comes back in WM_COMMAND
                if (i >= buttons.size()) {
                    item.set(JAVA_INT, 0, THB_FLAGS);
                    item.set(JAVA_INT, OFFSET_FLAGS, THBF_HIDDEN | THBF_DISABLED);
                    continue;
                }
                TaskbarButton button = buttons.get(i);
                MemorySegment icon = WindowsWindow.iconOfSize(button.icon(), pixels);
                int mask = THB_TOOLTIP | THB_FLAGS;
                if (icon.equals(MemorySegment.NULL)) {
                    LOG.log(System.Logger.Level.WARNING, "A taskbar button icon could not be decoded; it must be PNG or ICO data");
                } else {
                    icons.add(icon);
                    mask |= THB_ICON;
                    item.set(ADDRESS, OFFSET_ICON, icon);
                }
                item.set(JAVA_INT, 0, mask);
                byte[] tip = button.tooltip().getBytes(StandardCharsets.UTF_16LE);
                MemorySegment.copy(MemorySegment.ofArray(tip), 0, item, OFFSET_TIP, tip.length); // the rest stays zero: NUL terminated
                item.set(JAVA_INT, OFFSET_FLAGS, (button.enabled() ? 0 : THBF_DISABLED) | (button.dismissOnClick() ? THBF_DISMISSONCLICK : 0));
            }
            int slot = added ? THUMB_BAR_UPDATE_BUTTONS : THUMB_BAR_ADD_BUTTONS;
            int hr = Com.call(taskbar, slot, HR_HWND_INT_PTR, hwnd, total, array);
            lastResult = hr;
            if (hr < 0) {
                LOG.log(System.Logger.Level.WARNING, "Setting the taskbar buttons failed: " + Com.hex(hr));
            } else {
                added = true;
            }
        } finally {
            for (MemorySegment icon : previous) {
                Win32.destroyIcon(icon);
            }
        }
    }

    private void release() {
        if (!taskbar.equals(MemorySegment.NULL)) {
            Com.release(taskbar);
            taskbar = MemorySegment.NULL;
        }
    }

    /** The window is going: frees the interface and the icons. */
    void dispose() {
        release();
        for (MemorySegment icon : icons) {
            Win32.destroyIcon(icon);
        }
        icons.clear();
    }
}
