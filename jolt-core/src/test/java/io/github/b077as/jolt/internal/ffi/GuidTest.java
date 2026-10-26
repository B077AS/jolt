package io.github.b077as.jolt.internal.ffi;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuidTest {

    @Test
    void iunknownHasWindowsMemoryLayout() {
        // {00000000-0000-0000-C000-000000000046} is IID_IUnknown; Data4 starts C0 00 ... 46
        byte[] expected = {0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xC0, 0, 0, 0, 0, 0, 0, 0x46};
        assertArrayEquals(expected, Guid.bytes("00000000-0000-0000-c000-000000000046"));
    }

    @Test
    void firstThreeFieldsAreLittleEndianAndData4IsNot() {
        // {01020304-0506-0708-090A-0B0C0D0E0F10}
        byte[] expected = {4, 3, 2, 1, 6, 5, 8, 7, 9, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10};
        assertArrayEquals(expected, Guid.bytes("01020304-0506-0708-090a-0b0c0d0e0f10"));
    }

    @Test
    void matchesComparesNativeMemory() {
        try (Arena arena = Arena.ofConfined()) {
            byte[] guid = Guid.bytes("76eceacb-0462-4d94-ac83-423a6793775e");
            MemorySegment seg = arena.allocate(16);
            MemorySegment.copy(guid, 0, seg, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, 16);
            assertTrue(Guid.matches(seg, guid));
            seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 15, (byte) 0);
            assertFalse(Guid.matches(seg, guid));
        }
    }
}
