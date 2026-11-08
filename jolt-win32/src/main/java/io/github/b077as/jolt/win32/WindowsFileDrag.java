package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.internal.ffi.Guid;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Drags files out of a window with the shell's own drag loop. The data object is the shell's (what Explorer offers for the same
 * files), so every target that takes files from Explorer takes them from here, and {@code SHDoDragDrop} with no drop source of ours
 * gives the default one: Esc cancels, the shell draws the drag image, no COM class of our own is needed.
 *
 * <p>Only {@code DROPEFFECT_COPY} is offered, so a drop into a folder copies; the user's file is never moved away. The call blocks
 * in a modal loop until the button is released, on the UI thread, which keeps pumping messages.
 */
final class WindowsFileDrag {

    private static final SymbolLookup SHELL32 = SymbolLookup.libraryLookup("shell32.dll", Arena.global());
    private static final String IID_IDATAOBJECT = "0000010e-0000-0000-c000-000000000046";
    private static final String BHID_DATA_OBJECT = "b8c0bd9f-ed24-455c-83e6-d5390c4fe8c4";
    /** {@code IShellItemArray::BindToHandler(IBindCtx*, REFGUID handler, REFIID, void**)}, the first method after IUnknown. */
    private static final int BIND_TO_HANDLER = 3;
    private static final FunctionDescriptor BIND_TO_HANDLER_SIGNATURE =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final int DROPEFFECT_COPY = 1;
    private static final int DRAGDROP_S_DROP = 0x00040100;

    /** {@code HRESULT SHParseDisplayName(PCWSTR name, IBindCtx*, PIDLIST_ABSOLUTE* pidl, SFGAOF in, SFGAOF* out)}. */
    private static final MethodHandle SH_PARSE_DISPLAY_NAME = Native.downcall(SHELL32, "SHParseDisplayName",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    /** {@code HRESULT SHCreateShellItemArrayFromIDLists(UINT count, PCIDLIST_ABSOLUTE_ARRAY pidls, IShellItemArray** array)}. */
    private static final MethodHandle SH_CREATE_ITEM_ARRAY = Native.downcall(SHELL32, "SHCreateShellItemArrayFromIDLists",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    /** {@code HRESULT SHDoDragDrop(HWND, IDataObject*, IDropSource*, DWORD okEffects, DWORD* effect)}. */
    private static final MethodHandle SH_DO_DRAG_DROP = Native.downcall(SHELL32, "SHDoDragDrop",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));

    private WindowsFileDrag() {}

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }

    /**
     * Drags {@code files} from {@code hwnd} until the user drops or cancels. Blocks. UI thread, with the mouse button down.
     *
     * @return whether a target accepted a copy
     */
    static boolean run(MemorySegment hwnd, List<Path> files) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pidls = arena.allocate(ADDRESS.byteSize() * files.size());
            int parsed = 0;
            MemorySegment dataObject = MemorySegment.NULL;
            MemorySegment itemArray = MemorySegment.NULL;
            try {
                for (Path file : files) {
                    MemorySegment out = arena.allocate(ADDRESS);
                    int hr = (int) SH_PARSE_DISPLAY_NAME.invokeExact(Native.allocWide(arena, file.toString()), MemorySegment.NULL, out,
                            0, MemorySegment.NULL);
                    if (hr < 0) {
                        return false; // not a path the shell knows: drag nothing rather than a part of what was asked
                    }
                    pidls.setAtIndex(ADDRESS, parsed++, Native.readPointer(out));
                }
                MemorySegment arrayOut = arena.allocate(ADDRESS);
                int hr = (int) SH_CREATE_ITEM_ARRAY.invokeExact(parsed, pidls, arrayOut);
                if (hr < 0) {
                    return false;
                }
                itemArray = Native.readPointer(arrayOut);
                // the data object Explorer itself offers for these items (CF_HDROP and the other file formats)
                MemorySegment dataOut = arena.allocate(ADDRESS);
                hr = Com.call(itemArray, BIND_TO_HANDLER, BIND_TO_HANDLER_SIGNATURE, MemorySegment.NULL,
                        arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, Guid.bytes(BHID_DATA_OBJECT)),
                        arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, Guid.bytes(IID_IDATAOBJECT)), dataOut);
                if (hr < 0) {
                    return false;
                }
                dataObject = Native.readPointer(dataOut);
                MemorySegment effect = arena.allocate(JAVA_INT);
                hr = (int) SH_DO_DRAG_DROP.invokeExact(hwnd, dataObject, MemorySegment.NULL, DROPEFFECT_COPY, effect);
                return hr == DRAGDROP_S_DROP && effect.get(JAVA_INT, 0) != 0;
            } catch (Throwable t) {
                throw rethrow(t);
            } finally {
                if (!dataObject.equals(MemorySegment.NULL)) {
                    Com.release(dataObject);
                }
                if (!itemArray.equals(MemorySegment.NULL)) {
                    Com.release(itemArray);
                }
                for (int i = 0; i < parsed; i++) {
                    Win32.coTaskMemFree(pidls.getAtIndex(ADDRESS, i));
                }
            }
        }
    }
}
