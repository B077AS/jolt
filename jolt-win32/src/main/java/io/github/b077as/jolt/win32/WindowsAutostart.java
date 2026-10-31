package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.Autostart;
import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.internal.CommandLines;
import io.github.b077as.jolt.internal.ffi.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Starts the application at sign-in through a value under {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run}. */
final class WindowsAutostart implements Autostart {

    private static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32.dll", Arena.global());
    private static final String RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    /** {@code HKEY_CURRENT_USER}: the 32-bit constant 0x80000001 sign-extended to a pointer. */
    private static final MemorySegment HKEY_CURRENT_USER = MemorySegment.ofAddress(0xFFFFFFFF80000001L);
    private static final int KEY_QUERY_VALUE = 0x1, KEY_SET_VALUE = 0x2, REG_SZ = 1, ERROR_SUCCESS = 0;
    /** The registry keeps a command line shorter than this; a longer one would be refused by the shell anyway. */
    private static final int MAX_COMMAND_CHARS = 2000;

    private static final MethodHandle REG_CREATE_KEY_EX = Native.downcall(ADVAPI32, "RegCreateKeyExW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle REG_SET_VALUE_EX = Native.downcall(ADVAPI32, "RegSetValueExW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle REG_QUERY_VALUE_EX = Native.downcall(ADVAPI32, "RegQueryValueExW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle REG_DELETE_VALUE = Native.downcall(ADVAPI32, "RegDeleteValueW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle REG_CLOSE_KEY = Native.downcall(ADVAPI32, "RegCloseKey",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private final String valueName;

    WindowsAutostart(String appId) {
        this.valueName = appId.replace('\\', '_');
    }

    @Override
    public boolean isEnabled() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment key = openRunKey(arena);
            try {
                return (int) REG_QUERY_VALUE_EX.invokeExact(key, Native.allocWide(arena, valueName), MemorySegment.NULL,
                        MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL) == ERROR_SUCCESS;
            } finally {
                int ignored = (int) REG_CLOSE_KEY.invokeExact(key);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public void enable(List<String> command) {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty() || command.get(0).isBlank()) {
            throw new IllegalArgumentException("the command must start with the program to run");
        }
        String line = CommandLines.windows(command);
        if (line.length() > MAX_COMMAND_CHARS) {
            throw new IllegalArgumentException("the command line is too long for the registry (" + line.length() + " characters)");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment key = openRunKey(arena);
            try {
                byte[] utf16 = (line + "\0").getBytes(StandardCharsets.UTF_16LE);
                MemorySegment data = arena.allocateFrom(JAVA_BYTE, utf16);
                int status = (int) REG_SET_VALUE_EX.invokeExact(key, Native.allocWide(arena, valueName), 0, REG_SZ, data, utf16.length);
                if (status != ERROR_SUCCESS) {
                    throw new JoltException("RegSetValueExW failed: Win32 error " + status);
                }
            } finally {
                int ignored = (int) REG_CLOSE_KEY.invokeExact(key);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public void disable() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment key = openRunKey(arena);
            try {
                int status = (int) REG_DELETE_VALUE.invokeExact(key, Native.allocWide(arena, valueName));
                if (status != ERROR_SUCCESS && status != 2 /* ERROR_FILE_NOT_FOUND: nothing to remove */) {
                    throw new JoltException("RegDeleteValueW failed: Win32 error " + status);
                }
            } finally {
                int ignored = (int) REG_CLOSE_KEY.invokeExact(key);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static MemorySegment openRunKey(Arena arena) throws Throwable {
        MemorySegment out = arena.allocate(ADDRESS);
        int status = (int) REG_CREATE_KEY_EX.invokeExact(HKEY_CURRENT_USER, Native.allocWide(arena, RUN_KEY), 0, MemorySegment.NULL, 0,
                KEY_QUERY_VALUE | KEY_SET_VALUE, MemorySegment.NULL, out, MemorySegment.NULL);
        if (status != ERROR_SUCCESS) {
            throw new JoltException("RegCreateKeyExW failed: Win32 error " + status);
        }
        return out.get(ADDRESS, 0);
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new JoltException("registry access failed", t);
    }
}
