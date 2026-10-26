package io.github.b077as.jolt.internal.ffi;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/** COM GUIDs in their in-memory layout (Data1 int, Data2 short, Data3 short, Data4 8 bytes; little-endian). */
public final class Guid {

    public static final int SIZE = 16;

    private Guid() {}

    /** Converts {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx} to its 16-byte in-memory form. */
    public static byte[] bytes(String guid) {
        UUID u = UUID.fromString(guid);
        long hi = u.getMostSignificantBits();
        long lo = u.getLeastSignificantBits();
        ByteBuffer b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt((int) (hi >>> 32));
        b.putShort((short) (hi >>> 16));
        b.putShort((short) hi);
        b.order(ByteOrder.BIG_ENDIAN).putLong(lo); // Data4 is a byte array, so no swapping
        return b.array();
    }

    /** Whether the 16 bytes at {@code ptr} equal {@code expected}. {@code ptr} may be a zero-length segment. */
    public static boolean matches(MemorySegment ptr, byte[] expected) {
        MemorySegment seg = ptr.reinterpret(SIZE);
        for (int i = 0; i < SIZE; i++) {
            if (seg.get(ValueLayout.JAVA_BYTE, i) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
