package io.github.b077as.jolt.internal.ffi;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Small shared helpers for talking to native libraries. */
public final class Native {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.native");

    public static final Linker LINKER = Linker.nativeLinker();

    private Native() {}

    /** Looks up {@code symbol} in {@code lib} and binds it; fails with the symbol name if it is missing. */
    public static MethodHandle downcall(SymbolLookup lib, String symbol, FunctionDescriptor fd, Linker.Option... options) {
        MemorySegment address = lib.find(symbol)
                .orElseThrow(() -> new UnsatisfiedLinkError("symbol not found: " + symbol));
        return LINKER.downcallHandle(address, fd, options);
    }

    /** Like {@link #downcall} but returns empty when the symbol is absent (optional newer API). */
    public static Optional<MethodHandle> optionalDowncall(SymbolLookup lib, String symbol, FunctionDescriptor fd, Linker.Option... options) {
        return lib.find(symbol).map(address -> LINKER.downcallHandle(address, fd, options));
    }

    /**
     * Wraps an upcall target so that no {@link Throwable} can escape into native frames (which would terminate
     * the JVM). Failures are logged and the call returns {@code failureValue} (ignored for void, used as the
     * int/long result otherwise, and {@link MemorySegment#NULL} for pointers).
     */
    public static MethodHandle guarded(MethodHandle target, long failureValue) {
        MethodType type = target.type();
        Class<?> ret = type.returnType();
        MethodHandle onError;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            if (ret == void.class) {
                onError = lookup.findStatic(Native.class, "failVoid", MethodType.methodType(void.class, Throwable.class));
            } else if (ret == int.class) {
                onError = MethodHandles.insertArguments(
                        lookup.findStatic(Native.class, "failInt", MethodType.methodType(int.class, Throwable.class, int.class)),
                        1, (int) failureValue);
            } else if (ret == long.class) {
                onError = MethodHandles.insertArguments(
                        lookup.findStatic(Native.class, "failLong", MethodType.methodType(long.class, Throwable.class, long.class)),
                        1, failureValue);
            } else if (ret == MemorySegment.class) {
                onError = lookup.findStatic(Native.class, "failPointer", MethodType.methodType(MemorySegment.class, Throwable.class));
            } else {
                throw new IllegalArgumentException("unsupported upcall return type: " + ret);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        MethodHandle handler = MethodHandles.dropArguments(onError, 1, type.parameterList());
        return MethodHandles.catchException(target, Throwable.class, handler);
    }

    private static void failVoid(Throwable t) {
        LOG.log(System.Logger.Level.ERROR, "uncaught exception in native callback", t);
    }

    private static int failInt(Throwable t, int value) {
        failVoid(t);
        return value;
    }

    private static long failLong(Throwable t, long value) {
        failVoid(t);
        return value;
    }

    private static MemorySegment failPointer(Throwable t) {
        failVoid(t);
        return MemorySegment.NULL;
    }

    /** Reads a NUL-terminated UTF-16LE string at {@code ptr} (which may be a zero-length segment). */
    public static String readWide(MemorySegment ptr) {
        if (ptr.equals(MemorySegment.NULL)) {
            return null;
        }
        return ptr.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
    }

    /** Allocates {@code s} as a NUL-terminated UTF-16LE string. */
    public static MemorySegment allocWide(Arena arena, String s) {
        return arena.allocateFrom(s, StandardCharsets.UTF_16LE);
    }

    /** Reads the pointer stored at {@code location}. */
    public static MemorySegment readPointer(MemorySegment location) {
        return location.reinterpret(ValueLayout.ADDRESS.byteSize()).get(ValueLayout.ADDRESS, 0);
    }
}
