package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.internal.ffi.Guid;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * A COM object implemented in Java: the callback interfaces WebView2 asks us to supply
 * ({@code ...CompletedHandler}, {@code ...EventHandler}). All of them have the same four-slot vtable:
 * {@code QueryInterface}, {@code AddRef}, {@code Release}, {@code Invoke}.
 *
 * <p><b>Lifetime.</b> The object starts with one reference owned by Java. Call {@link #release()} when Java no
 * longer needs it; WebView2 holds its own references while an event registration or pending call exists. When
 * the count reaches zero the native memory is freed <i>later</i>, via {@code deferFree}, because freeing an upcall
 * stub while a call through it is still on the stack would crash the process.
 */
final class ComHandler {

    /** {@code Invoke(HRESULT, T*)}: completion handlers. */
    @FunctionalInterface
    interface HrPtr {
        void invoke(int hresult, MemorySegment value);
    }

    /** {@code Invoke(sender*, args*)}: event handlers. */
    @FunctionalInterface
    interface PtrPtr {
        void invoke(MemorySegment sender, MemorySegment args);
    }

    private static final byte[] IID_IUNKNOWN = Guid.bytes("00000000-0000-0000-c000-000000000046");
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private final Arena arena = Arena.ofShared();
    private final byte[] iid;
    private final Consumer<Runnable> deferFree;
    private final AtomicInteger refs = new AtomicInteger(1);
    private final HrPtr hrPtr;
    private final PtrPtr ptrPtr;

    /** Pointer to hand to WebView2 (an {@code IFoo*}). */
    final MemorySegment object;

    static ComHandler hrPtr(String iid, HrPtr callback, Consumer<Runnable> deferFree) {
        return new ComHandler(iid, callback, null, deferFree);
    }

    static ComHandler ptrPtr(String iid, PtrPtr callback, Consumer<Runnable> deferFree) {
        return new ComHandler(iid, null, callback, deferFree);
    }

    private ComHandler(String iid, HrPtr hrPtr, PtrPtr ptrPtr, Consumer<Runnable> deferFree) {
        this.iid = Guid.bytes(iid);
        this.hrPtr = hrPtr;
        this.ptrPtr = ptrPtr;
        this.deferFree = deferFree;

        MemorySegment queryInterface = stub("queryInterface",
                MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), Com.E_NOINTERFACE);
        MemorySegment addRef = stub("addRef",
                MethodType.methodType(int.class, MemorySegment.class),
                FunctionDescriptor.of(JAVA_INT, ADDRESS), 1);
        MemorySegment releaseStub = stub("releaseFromNative",
                MethodType.methodType(int.class, MemorySegment.class),
                FunctionDescriptor.of(JAVA_INT, ADDRESS), 1);
        MemorySegment invoke = hrPtr != null
                ? stub("invokeHrPtr",
                        MethodType.methodType(int.class, MemorySegment.class, int.class, MemorySegment.class),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), Com.E_FAIL)
                : stub("invokePtrPtr",
                        MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), Com.E_FAIL);

        MemorySegment vtable = arena.allocate(4 * ADDRESS.byteSize(), ADDRESS.byteAlignment());
        vtable.setAtIndex(ADDRESS, 0, queryInterface);
        vtable.setAtIndex(ADDRESS, 1, addRef);
        vtable.setAtIndex(ADDRESS, 2, releaseStub);
        vtable.setAtIndex(ADDRESS, 3, invoke);
        this.object = arena.allocate(ADDRESS.byteSize(), ADDRESS.byteAlignment());
        object.set(ADDRESS, 0, vtable);
    }

    private MemorySegment stub(String method, MethodType type, FunctionDescriptor descriptor, long failureValue) {
        try {
            MethodHandle target = LOOKUP.findVirtual(ComHandler.class, method, type).bindTo(this);
            return Native.LINKER.upcallStub(Native.guarded(target, failureValue), descriptor, arena);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Drops Java's reference. The object is freed once WebView2 has dropped its references too. */
    void release() {
        dropReference();
    }

    private int dropReference() {
        int remaining = refs.decrementAndGet();
        if (remaining == 0) {
            deferFree.accept(arena::close);
        }
        return remaining;
    }

    // ---- called from native code (UI thread) ----

    private int queryInterface(MemorySegment self, MemorySegment riid, MemorySegment ppv) {
        MemorySegment out = ppv.reinterpret(ADDRESS.byteSize());
        if (Guid.matches(riid, IID_IUNKNOWN) || Guid.matches(riid, iid)) {
            refs.incrementAndGet();
            out.set(ADDRESS, 0, self);
            return Com.S_OK;
        }
        out.set(ADDRESS, 0, MemorySegment.NULL);
        return Com.E_NOINTERFACE;
    }

    private int addRef(MemorySegment self) {
        return refs.incrementAndGet();
    }

    private int releaseFromNative(MemorySegment self) {
        return dropReference();
    }

    private int invokeHrPtr(MemorySegment self, int hresult, MemorySegment value) {
        hrPtr.invoke(hresult, value);
        return Com.S_OK;
    }

    private int invokePtrPtr(MemorySegment self, MemorySegment sender, MemorySegment args) {
        ptrPtr.invoke(sender, args);
        return Com.S_OK;
    }
}
