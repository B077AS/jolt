package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.internal.ffi.Native;

import io.github.b077as.jolt.internal.ffi.Guid;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The slice of Win32 (x64/arm64 ABI) the backend needs. Handles are pointers, {@code WPARAM}/{@code LPARAM}/
 * {@code LRESULT} are 64-bit, {@code BOOL}/{@code UINT}/{@code DWORD} are 32-bit.
 */
final class Win32 {

    // window messages
    static final int WM_DESTROY = 0x0002;
    static final int WM_SIZE = 0x0005;
    static final int WM_GETMINMAXINFO = 0x0024;
    static final int WM_SETFOCUS = 0x0007;
    static final int WM_CLOSE = 0x0010;
    static final int WM_ERASEBKGND = 0x0014;
    static final int WM_SHOWWINDOW = 0x0018;
    static final int WM_SETICON = 0x0080;
    static final int WM_GETICON = 0x007F;
    static final int ICON_SMALL = 0;
    static final int ICON_BIG = 1;
    static final int SM_CXICON = 11;
    static final int SM_CXSMICON = 49;
    /** {@code dwVer} for icon resource bits. */
    static final int ICON_RESOURCE_VERSION = 0x00030000;
    static final int SWP_FRAMECHANGED = 0x0020;

    // DwmSetWindowAttribute attributes
    static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20; // Windows 10 2004 and later
    static final int DWMWA_BORDER_COLOR = 34;            // Windows 11
    static final int DWMWA_CAPTION_COLOR = 35;           // Windows 11
    static final int DWMWA_TEXT_COLOR = 36;              // Windows 11
    static final int WM_QUIT = 0x0012;
    static final int PM_REMOVE = 0x0001;
    static final int WM_DPICHANGED = 0x02E0;
    static final int WM_APP = 0x8000;

    // window styles
    static final int WS_OVERLAPPEDWINDOW = 0x00CF0000;
    static final int WS_CLIPSIBLINGS = 0x04000000;
    static final int WS_CLIPCHILDREN = 0x02000000;
    static final int CW_USEDEFAULT = 0x80000000;

    static final int SW_HIDE = 0;
    static final int SW_SHOWMAXIMIZED = 3;
    static final int SW_SHOW = 5;
    static final int SW_RESTORE = 9;

    /** {@code MONITOR_DEFAULTTONEAREST}: the monitor closest to the window if it is on none. */
    static final int MONITOR_DEFAULTTONEAREST = 2;

    static final int SWP_NOSIZE = 0x0001;
    static final int SWP_NOMOVE = 0x0002;
    static final int SWP_NOZORDER = 0x0004;
    static final int SWP_NOACTIVATE = 0x0010;

    static final int COINIT_APARTMENTTHREADED = 0x2;
    static final int IDC_ARROW = 32512;
    /** {@code DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2} is the pseudo-handle -4. */
    static final long DPI_PER_MONITOR_AWARE_V2 = -4;
    /** {@code HWND_MESSAGE}: parent for message-only windows. */
    static final long HWND_MESSAGE = -3;

    static final StructLayout RECT = MemoryLayout.structLayout(
            JAVA_INT.withName("left"), JAVA_INT.withName("top"),
            JAVA_INT.withName("right"), JAVA_INT.withName("bottom"));

    /** MONITORINFO: DWORD cbSize, RECT rcMonitor, RECT rcWork, DWORD dwFlags (40 bytes). rcWork excludes the taskbar. */
    static final long MONITORINFO_SIZE = 40;
    private static final long MONITORINFO_WORK_OFFSET = 20;

    /** WNDCLASSEXW on 64-bit Windows; natural alignment gives the documented offsets (size 80). */
    static final StructLayout WNDCLASSEXW = MemoryLayout.structLayout(
            JAVA_INT.withName("cbSize"), JAVA_INT.withName("style"),
            ADDRESS.withName("lpfnWndProc"),
            JAVA_INT.withName("cbClsExtra"), JAVA_INT.withName("cbWndExtra"),
            ADDRESS.withName("hInstance"), ADDRESS.withName("hIcon"), ADDRESS.withName("hCursor"),
            ADDRESS.withName("hbrBackground"), ADDRESS.withName("lpszMenuName"),
            ADDRESS.withName("lpszClassName"), ADDRESS.withName("hIconSm"));

    /** MSG: hwnd, UINT message, WPARAM, LPARAM, DWORD time, POINT pt (size 48 with padding). */
    static final long MSG_SIZE = 48;

    private static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
    private static final VarHandle LAST_ERROR = CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));
    private static final Linker.Option CAPTURE_LAST_ERROR = Linker.Option.captureCallState("GetLastError");

    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
    private static final SymbolLookup OLE32 = SymbolLookup.libraryLookup("ole32.dll", Arena.global());

    private static final SymbolLookup DWMAPI = SymbolLookup.libraryLookup("dwmapi.dll", Arena.global());
    /** {@code HRESULT DwmSetWindowAttribute(HWND, DWORD attribute, LPCVOID value, DWORD size)}. */
    private static final MethodHandle DWM_SET_WINDOW_ATTRIBUTE = Native.downcall(DWMAPI, "DwmSetWindowAttribute",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle DWM_GET_WINDOW_ATTRIBUTE = Native.downcall(DWMAPI, "DwmGetWindowAttribute",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    /** {@code HICON CreateIconFromResourceEx(PBYTE bits, DWORD size, BOOL icon, DWORD version, int cx, int cy, UINT flags)}. */
    private static final MethodHandle CREATE_ICON_FROM_RESOURCE_EX = Native.downcall(USER32, "CreateIconFromResourceEx",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final SymbolLookup GDI32 = SymbolLookup.libraryLookup("gdi32.dll", Arena.global());
    private static final MethodHandle GET_ICON_INFO = Native.downcall(USER32, "GetIconInfo",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GET_OBJECT = Native.downcall(GDI32, "GetObjectW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle DELETE_OBJECT = Native.downcall(GDI32, "DeleteObject",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle DESTROY_ICON =Native.downcall(USER32, "DestroyIcon",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SEND_MESSAGE = Native.downcall(USER32, "SendMessageW",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GET_SYSTEM_METRICS_FOR_DPI = Native.downcall(USER32, "GetSystemMetricsForDpi",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final SymbolLookup SHLWAPI = SymbolLookup.libraryLookup("shlwapi.dll", Arena.global());
    private static final MethodHandle SH_CREATE_MEM_STREAM = Native.downcall(SHLWAPI, "SHCreateMemStream",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle GET_MODULE_HANDLE =Native.downcall(KERNEL32, "GetModuleHandleW",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GET_CURRENT_THREAD_ID = Native.downcall(KERNEL32, "GetCurrentThreadId",
            FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle REGISTER_CLASS = Native.downcall(USER32, "RegisterClassExW",
            FunctionDescriptor.of(JAVA_SHORT, ADDRESS), CAPTURE_LAST_ERROR);
    private static final MethodHandle UNREGISTER_CLASS = Native.downcall(USER32, "UnregisterClassW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CREATE_WINDOW =Native.downcall(USER32, "CreateWindowExW",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS), CAPTURE_LAST_ERROR);
    private static final MethodHandle DEF_WINDOW_PROC = Native.downcall(USER32, "DefWindowProcW",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GET_MESSAGE = Native.downcall(USER32, "GetMessageW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle PEEK_MESSAGE = Native.downcall(USER32, "PeekMessageW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle TRANSLATE_MESSAGE =Native.downcall(USER32, "TranslateMessage",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle DISPATCH_MESSAGE = Native.downcall(USER32, "DispatchMessageW",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle POST_MESSAGE = Native.downcall(USER32, "PostMessageW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle POST_QUIT_MESSAGE = Native.downcall(USER32, "PostQuitMessage",
            FunctionDescriptor.ofVoid(JAVA_INT));
    private static final MethodHandle SHOW_WINDOW = Native.downcall(USER32, "ShowWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle UPDATE_WINDOW = Native.downcall(USER32, "UpdateWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle DESTROY_WINDOW = Native.downcall(USER32, "DestroyWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SET_WINDOW_TEXT = Native.downcall(USER32, "SetWindowTextW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GET_CLIENT_RECT = Native.downcall(USER32, "GetClientRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle SET_WINDOW_POS = Native.downcall(USER32, "SetWindowPos",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    /** {@code HWND GetWindow(HWND, UINT)}. */
    private static final MethodHandle GET_WINDOW = Native.downcall(USER32, "GetWindow",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle GET_WINDOW_RECT = Native.downcall(USER32, "GetWindowRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle IS_ZOOMED = Native.downcall(USER32, "IsZoomed",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle IS_WINDOW_VISIBLE = Native.downcall(USER32, "IsWindowVisible",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle MONITOR_FROM_WINDOW = Native.downcall(USER32, "MonitorFromWindow",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle GET_MONITOR_INFO = Native.downcall(USER32, "GetMonitorInfoW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle FILL_RECT = Native.downcall(USER32, "FillRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CREATE_SOLID_BRUSH = Native.downcall(GDI32, "CreateSolidBrush",
            FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle LOAD_CURSOR = Native.downcall(USER32, "LoadCursorW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GET_DPI_FOR_WINDOW = Native.downcall(USER32, "GetDpiForWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SET_DPI_AWARENESS_CONTEXT = Native.downcall(USER32, "SetProcessDpiAwarenessContext",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle ADJUST_WINDOW_RECT_EX_FOR_DPI = Native.downcall(USER32, "AdjustWindowRectExForDpi",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle CO_INITIALIZE_EX = Native.downcall(OLE32, "CoInitializeEx",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle CO_UNINITIALIZE = Native.downcall(OLE32, "CoUninitialize",
            FunctionDescriptor.ofVoid());
    private static final SymbolLookup SHELL32 = SymbolLookup.libraryLookup("shell32.dll", Arena.global());
    /** {@code HRESULT CoCreateInstance(REFCLSID, IUnknown* outer, DWORD context, REFIID, void** object)}. */
    private static final MethodHandle CO_CREATE_INSTANCE = Native.downcall(OLE32, "CoCreateInstance",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    /** {@code HRESULT SHCreateItemFromParsingName(PCWSTR path, IBindCtx*, REFIID, void** item)}. */
    private static final MethodHandle SH_CREATE_ITEM_FROM_PARSING_NAME = Native.downcall(SHELL32, "SHCreateItemFromParsingName",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CO_TASK_MEM_FREE = Native.downcall(OLE32, "CoTaskMemFree",
            FunctionDescriptor.ofVoid(ADDRESS));

    private Win32() {}

    /** Sets a DWM window attribute holding a 32-bit value; returns the HRESULT (negative if unsupported here). */
    static int dwmSetAttribute(MemorySegment hwnd, int attribute, int value) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment holder = arena.allocate(JAVA_INT);
            holder.set(JAVA_INT, 0, value);
            return (int) DWM_SET_WINDOW_ATTRIBUTE.invokeExact(hwnd, attribute, holder, 4);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Reads a 32-bit DWM window attribute, or {@code null} if it cannot be read on this system. */
    static Integer dwmGetAttribute(MemorySegment hwnd, int attribute) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment holder = arena.allocate(JAVA_INT);
            int hr = (int) DWM_GET_WINDOW_ATTRIBUTE.invokeExact(hwnd, attribute, holder, 4);
            return hr < 0 ? null : holder.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Makes an icon of {@code cx} by {@code cy} pixels from one image: PNG data, or the bytes of one entry of an icon file
     * (a bitmap or a PNG). Returns {@link MemorySegment#NULL} if Windows cannot decode it. If the image already has the
     * requested size nothing is scaled.
     */
    static MemorySegment createIcon(byte[] image, int cx, int cy) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bits = arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, image);
            return (MemorySegment) CREATE_ICON_FROM_RESOURCE_EX.invokeExact(bits, image.length, 1, ICON_RESOURCE_VERSION, cx, cy, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The width in pixels of an icon's colour bitmap, or -1 if it cannot be read. For tests. */
    static int iconWidth(MemorySegment icon) {
        try (Arena arena = Arena.ofConfined()) {
            // ICONINFO on 64-bit Windows: BOOL fIcon (0), DWORD xHotspot (4), DWORD yHotspot (8), HBITMAP hbmMask (16), HBITMAP hbmColor (24)
            MemorySegment info = arena.allocate(32, 8);
            if ((int) GET_ICON_INFO.invokeExact(icon, info) == 0) {
                return -1;
            }
            MemorySegment mask = info.get(ADDRESS, 16);
            MemorySegment color = info.get(ADDRESS, 24);
            try {
                // BITMAP: LONG bmType (0), LONG bmWidth (4), LONG bmHeight (8), LONG bmWidthBytes (12), WORD planes, WORD bpp, LPVOID bits (24)
                MemorySegment bitmap = arena.allocate(32, 8);
                MemorySegment source = color.equals(MemorySegment.NULL) ? mask : color;
                if ((int) GET_OBJECT.invokeExact(source, 32, bitmap) == 0) {
                    return -1;
                }
                return bitmap.get(JAVA_INT, 4);
            } finally {
                int ignored1 = (int) DELETE_OBJECT.invokeExact(mask);
                if (!color.equals(MemorySegment.NULL)) {
                    int ignored2 = (int) DELETE_OBJECT.invokeExact(color);
                }
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void destroyIcon(MemorySegment icon) {
        try {
            int ignored = (int) DESTROY_ICON.invokeExact(icon);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long sendMessage(MemorySegment hwnd, int msg, long wParam, long lParam) {
        try {
            return (long) SEND_MESSAGE.invokeExact(hwnd, msg, wParam, lParam);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** A system metric (for example the icon size) scaled for {@code dpi}. */
    static int systemMetricForDpi(int index, int dpi) {
        try {
            return (int) GET_SYSTEM_METRICS_FOR_DPI.invokeExact(index, dpi);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code SHCreateMemStream}: an {@code IStream*} over a copy of {@code data}; the caller owns one reference. */
    static MemorySegment createMemStream(MemorySegment data, int length) {
        try {
            return (MemorySegment) SH_CREATE_MEM_STREAM.invokeExact(data, length);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static MemorySegment pointer(long value) {
        return MemorySegment.ofAddress(value);
    }

    static MemorySegment getModuleHandle() {
        try {
            return (MemorySegment) GET_MODULE_HANDLE.invokeExact(MemorySegment.NULL);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int currentThreadId() {
        try {
            return (int) GET_CURRENT_THREAD_ID.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Registers the class described by {@code wndClassEx}; returns the atom, or 0 and the Win32 error in {@code errorOut[0]}. */
    static short registerClass(MemorySegment wndClassEx, int[] errorOut) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            short atom = (short) REGISTER_CLASS.invokeExact(state, wndClassEx);
            errorOut[0] = (int) LAST_ERROR.get(state, 0L);
            return atom;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns the new window handle or {@link MemorySegment#NULL}, with the Win32 error in {@code errorOut[0]}. */
    static MemorySegment createWindow(int exStyle, MemorySegment className, MemorySegment title, int style,
                                      int x, int y, int width, int height, MemorySegment parent,
                                      MemorySegment hInstance, int[] errorOut) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            // Typed local, not an inline ternary: inside invokeExact a conditional is typed Object and breaks the exact match.
            MemorySegment parentWindow = parent == null ? MemorySegment.NULL : parent;
            MemorySegment hwnd = (MemorySegment) CREATE_WINDOW.invokeExact(state, exStyle, className, title, style,
                    x, y, width, height, parentWindow, MemorySegment.NULL, hInstance, MemorySegment.NULL);
            errorOut[0] = (int) LAST_ERROR.get(state, 0L);
            return hwnd;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void unregisterClass(MemorySegment className, MemorySegment hInstance) {
        try {
            int ignored = (int) UNREGISTER_CLASS.invokeExact(className, hInstance);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long defWindowProc(MemorySegment hwnd, int msg, long wParam, long lParam) {
        try {
            return (long) DEF_WINDOW_PROC.invokeExact(hwnd, msg, wParam, lParam);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code GetMessageW}: positive for a message, 0 for WM_QUIT, -1 on error. */
    static int getMessage(MemorySegment msg) {
        try {
            return (int) GET_MESSAGE.invokeExact(msg, MemorySegment.NULL, 0, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void translateAndDispatch(MemorySegment msg) {
        try {
            int ignoredTranslate = (int) TRANSLATE_MESSAGE.invokeExact(msg);
            long ignoredResult = (long) DISPATCH_MESSAGE.invokeExact(msg);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Removes a quit request left on this thread's queue, if any ({@code PeekMessageW} for WM_QUIT only). */
    static void discardPendingQuit(MemorySegment msgBuffer) {
        try {
            int ignored = (int) PEEK_MESSAGE.invokeExact(msgBuffer, MemorySegment.NULL, WM_QUIT, WM_QUIT, PM_REMOVE);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The enabled popup window owned by {@code owner} (an open modal dialog), or NULL. */
    static MemorySegment enabledPopupOf(MemorySegment owner) {
        try {
            return (MemorySegment) GET_WINDOW.invokeExact(owner, 6 /* GW_ENABLEDPOPUP */);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static boolean postMessage(MemorySegment hwnd, int msg, long wParam, long lParam) {
        try {
            return (int) POST_MESSAGE.invokeExact(hwnd, msg, wParam, lParam) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void postQuitMessage(int exitCode) {
        try {
            POST_QUIT_MESSAGE.invokeExact(exitCode);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void showWindow(MemorySegment hwnd, int command) {
        try {
            int ignored = (int) SHOW_WINDOW.invokeExact(hwnd, command);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void updateWindow(MemorySegment hwnd) {
        try {
            int ignored = (int) UPDATE_WINDOW.invokeExact(hwnd);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void destroyWindow(MemorySegment hwnd) {
        try {
            int ignored = (int) DESTROY_WINDOW.invokeExact(hwnd);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void setWindowText(MemorySegment hwnd, MemorySegment wideText) {
        try {
            int ignored = (int) SET_WINDOW_TEXT.invokeExact(hwnd, wideText);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void getClientRect(MemorySegment hwnd, MemorySegment rect) {
        try {
            int ignored = (int) GET_CLIENT_RECT.invokeExact(hwnd, rect);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void setWindowPos(MemorySegment hwnd, int x, int y, int cx, int cy, int flags) {
        try {
            int ignored = (int) SET_WINDOW_POS.invokeExact(hwnd, MemorySegment.NULL, x, y, cx, cy, flags);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The window's outer rectangle in screen coordinates, written to {@code rect} (a {@link #RECT}). */
    static void getWindowRect(MemorySegment hwnd, MemorySegment rect) {
        try {
            int ignored = (int) GET_WINDOW_RECT.invokeExact(hwnd, rect);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Whether the window is maximized. Safe to call from any thread: it only reads the window's state. */
    static boolean isZoomed(MemorySegment hwnd) {
        try {
            return (int) IS_ZOOMED.invokeExact(hwnd) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static boolean isWindowVisible(MemorySegment hwnd) {
        try {
            return (int) IS_WINDOW_VISIBLE.invokeExact(hwnd) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * The usable area of the monitor the window is on (its work area, without the taskbar) as {left, top, right, bottom} in
     * screen coordinates, or {@code null} if Windows cannot say.
     */
    static int[] workAreaOf(MemorySegment hwnd) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment monitor = (MemorySegment) MONITOR_FROM_WINDOW.invokeExact(hwnd, MONITOR_DEFAULTTONEAREST);
            if (monitor.equals(MemorySegment.NULL)) {
                return null;
            }
            MemorySegment info = arena.allocate(MONITORINFO_SIZE);
            info.set(JAVA_INT, 0, (int) MONITORINFO_SIZE); // cbSize must be set before the call
            if ((int) GET_MONITOR_INFO.invokeExact(monitor, info) == 0) {
                return null;
            }
            return new int[] {info.get(JAVA_INT, MONITORINFO_WORK_OFFSET), info.get(JAVA_INT, MONITORINFO_WORK_OFFSET + 4),
                    info.get(JAVA_INT, MONITORINFO_WORK_OFFSET + 8), info.get(JAVA_INT, MONITORINFO_WORK_OFFSET + 12)};
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** A solid brush of the given COLORREF ({@code 0x00BBGGRR}); free it with {@link #deleteObject}. */
    static MemorySegment createSolidBrush(int colorRef) {
        try {
            return (MemorySegment) CREATE_SOLID_BRUSH.invokeExact(colorRef);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void fillRect(MemorySegment hdc, MemorySegment rect, MemorySegment brush) {
        try {
            int ignored = (int) FILL_RECT.invokeExact(hdc, rect, brush);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The COLORREF of a solid brush, or -1 if it cannot be read. For tests. */
    static int brushColorRef(MemorySegment brush) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment logBrush = arena.allocate(16); // LOGBRUSH: UINT style, COLORREF color, ULONG_PTR hatch
            int written = (int) GET_OBJECT.invokeExact(brush, 16, logBrush);
            return written == 0 ? -1 : logBrush.get(JAVA_INT, 4);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void deleteObject(MemorySegment object) {
        try {
            int ignored = (int) DELETE_OBJECT.invokeExact(object);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static MemorySegment loadCursor(int resourceId) {
        try {
            return (MemorySegment) LOAD_CURSOR.invokeExact(MemorySegment.NULL, MemorySegment.ofAddress(resourceId));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int dpiForWindow(MemorySegment hwnd) {
        try {
            int dpi = (int) GET_DPI_FOR_WINDOW.invokeExact(hwnd);
            return dpi == 0 ? 96 : dpi;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Best effort: fails harmlessly if the process already has a DPI awareness setting. */
    static boolean setPerMonitorDpiAwareness() {
        try {
            return (int) SET_DPI_AWARENESS_CONTEXT.invokeExact(MemorySegment.ofAddress(DPI_PER_MONITOR_AWARE_V2)) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void adjustWindowRectExForDpi(MemorySegment rect, int style, int exStyle, int dpi) {
        try {
            int ignored = (int) ADJUST_WINDOW_RECT_EX_FOR_DPI.invokeExact(rect, style, 0, exStyle, dpi);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int coInitializeEx(int flags) {
        try {
            return (int) CO_INITIALIZE_EX.invokeExact(MemorySegment.NULL, flags);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void coUninitialize() {
        try {
            CO_UNINITIALIZE.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Creates an in-process COM object of class {@code clsid} and returns its interface {@code iid}; throws if that fails. */
    static MemorySegment coCreateInstance(String clsid, String iid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) CO_CREATE_INSTANCE.invokeExact(arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, Guid.bytes(clsid)),
                    MemorySegment.NULL, 1 /* CLSCTX_INPROC_SERVER */, arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, Guid.bytes(iid)), out);
            Com.check("CoCreateInstance " + clsid, hr);
            return Native.readPointer(out);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The shell item for a file system path as interface {@code iid}, or NULL if the path cannot be resolved. */
    static MemorySegment shCreateItemFromParsingName(Arena arena, String path, String iid) {
        try {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) SH_CREATE_ITEM_FROM_PARSING_NAME.invokeExact(Native.allocWide(arena, path), MemorySegment.NULL,
                    arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, Guid.bytes(iid)), out);
            return hr < 0 ? MemorySegment.NULL : Native.readPointer(out);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void coTaskMemFree(MemorySegment ptr) {
        try {
            CO_TASK_MEM_FREE.invokeExact(ptr);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }
}
