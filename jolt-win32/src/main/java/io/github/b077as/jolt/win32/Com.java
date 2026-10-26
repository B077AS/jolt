package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Calls methods on COM objects through their vtables. A COM object is a pointer to a pointer to an array of
 * function pointers; slot 0-2 are {@code IUnknown}, so interface methods start at 3.
 *
 * <p>Every descriptor here starts with the {@code this} pointer; callers pass the remaining arguments only.
 */
final class Com {

    static final int S_OK = 0;
    static final int E_NOINTERFACE = 0x80004002;
    static final int E_FAIL = 0x80004005;

    // IUnknown slots
    static final int QUERY_INTERFACE = 0;
    static final int ADD_REF = 1;
    static final int RELEASE = 2;

    /** {@code HRESULT m(this, void* out)}: property getters, {@code Navigate(LPCWSTR)}, and similar. */
    static final FunctionDescriptor HR_PTR = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    /** {@code HRESULT m(this, void* a, void* b)}: {@code ExecuteScript}, {@code add_*(handler, token*)}, and similar. */
    static final FunctionDescriptor HR_PTR_PTR = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    /** {@code HRESULT m(this, void* a, void* b, void* c)}: {@code CallDevToolsProtocolMethod(method, params, handler)}. */
    static final FunctionDescriptor HR_PTR_PTR_PTR = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    /** {@code HRESULT m(this, void* a, int b)}: {@code AddWebResourceRequestedFilter(uri, context)}. */
    static final FunctionDescriptor HR_PTR_INT = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT);
    /** {@code CreateWebResourceResponse(IStream* content, int status, LPCWSTR reason, LPCWSTR headers, response**)}. */
    static final FunctionDescriptor HR_RESPONSE =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    /** {@code HRESULT m(this, int)}: {@code BOOL}/enum setters. */
    static final FunctionDescriptor HR_INT = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
    /** {@code HRESULT m(this)}. */
    static final FunctionDescriptor HR_VOID = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    /** {@code HRESULT m(this, RECT by value)}: {@code put_Bounds}. */
    static final FunctionDescriptor HR_RECT = FunctionDescriptor.of(JAVA_INT, ADDRESS, Win32.RECT);
    /** {@code COREWEBVIEW2_COLOR}: four bytes in the order A, R, G, B, passed by value. */
    static final StructLayout COLOR = MemoryLayout.structLayout(
            JAVA_BYTE.withName("a"), JAVA_BYTE.withName("r"), JAVA_BYTE.withName("g"), JAVA_BYTE.withName("b"));
    /** {@code HRESULT m(this, COREWEBVIEW2_COLOR by value)}: {@code put_DefaultBackgroundColor}. */
    static final FunctionDescriptor HR_COLOR = FunctionDescriptor.of(JAVA_INT, ADDRESS, COLOR);
    private static final FunctionDescriptor ULONG_VOID = FunctionDescriptor.of(JAVA_INT, ADDRESS);

    private static final Map<FunctionDescriptor, MethodHandle> HANDLES = new ConcurrentHashMap<>();

    private Com() {}

    /** Invokes vtable slot {@code slot} of {@code self}; returns the raw HRESULT. */
    static int call(MemorySegment self, int slot, FunctionDescriptor descriptor, Object... args) {
        MemorySegment vtable = Native.readPointer(self);
        MemorySegment function = vtable.reinterpret((slot + 1L) * ADDRESS.byteSize()).getAtIndex(ADDRESS, slot);
        MethodHandle handle = HANDLES.computeIfAbsent(descriptor, Native.LINKER::downcallHandle);
        Object[] all = new Object[args.length + 2];
        all[0] = function;
        all[1] = self;
        System.arraycopy(args, 0, all, 2, args.length);
        try {
            return (int) handle.invokeWithArguments(all);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Like {@link #call} but throws {@link JoltException} on a failed HRESULT. */
    static void callChecked(String what, MemorySegment self, int slot, FunctionDescriptor descriptor, Object... args) {
        check(what, call(self, slot, descriptor, args));
    }

    static void check(String what, int hresult) {
        if (hresult < 0) {
            throw new JoltException(what + " failed: " + hex(hresult));
        }
    }

    static String hex(int hresult) {
        return "HRESULT 0x" + Integer.toHexString(hresult).toUpperCase();
    }

    /**
     * Asks {@code self} for another interface it implements. Returns the new pointer (one reference the caller must
     * release), or {@link MemorySegment#NULL} if the object does not support it.
     */
    static MemorySegment queryInterface(MemorySegment self, String iid) {
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            MemorySegment riid = arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, io.github.b077as.jolt.internal.ffi.Guid.bytes(iid));
            MemorySegment out = arena.allocate(ADDRESS);
            return call(self, QUERY_INTERFACE, HR_PTR_PTR, riid, out) < 0 ? MemorySegment.NULL : Native.readPointer(out);
        }
    }

    static int addRef(MemorySegment self) {
        return call(self, ADD_REF, ULONG_VOID);
    }

    static int release(MemorySegment self) {
        return call(self, RELEASE, ULONG_VOID);
    }
}
