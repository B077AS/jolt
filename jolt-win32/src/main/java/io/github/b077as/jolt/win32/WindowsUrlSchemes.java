package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.UrlSchemes;
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

/**
 * Links of a scheme start the application through {@code HKCU\Software\Classes\<scheme>}: the key's default value names the handler,
 * an empty {@code URL Protocol} value marks it as a protocol, and {@code shell\open\command} holds the command line with the link
 * ({@code "%1"}) last.
 */
final class WindowsUrlSchemes implements UrlSchemes {

    private static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32.dll", Arena.global());
    private static final MemorySegment HKEY_CURRENT_USER = MemorySegment.ofAddress(0xFFFFFFFF80000001L);
    private static final String CLASSES = "Software\\Classes\\";
    private static final int KEY_QUERY_VALUE = 0x1, KEY_SET_VALUE = 0x2, KEY_CREATE_SUB_KEY = 0x4, REG_SZ = 1, ERROR_SUCCESS = 0,
            ERROR_FILE_NOT_FOUND = 2;
    private static final int MAX_COMMAND_CHARS = 2000;

    private static final MethodHandle REG_CREATE_KEY_EX = Native.downcall(ADVAPI32, "RegCreateKeyExW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle REG_OPEN_KEY_EX = Native.downcall(ADVAPI32, "RegOpenKeyExW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle REG_SET_VALUE_EX = Native.downcall(ADVAPI32, "RegSetValueExW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle REG_DELETE_TREE = Native.downcall(ADVAPI32, "RegDeleteTreeW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle REG_CLOSE_KEY = Native.downcall(ADVAPI32, "RegCloseKey",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    @Override
    public boolean isRegistered(String scheme) {
        UrlSchemes.validate(scheme);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int status = (int) REG_OPEN_KEY_EX.invokeExact(HKEY_CURRENT_USER,
                    Native.allocWide(arena, CLASSES + scheme + "\\shell\\open\\command"), 0, KEY_QUERY_VALUE, out);
            if (status == ERROR_SUCCESS) {
                int ignored = (int) REG_CLOSE_KEY.invokeExact(out.get(ADDRESS, 0));
            }
            return status == ERROR_SUCCESS;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public void register(String scheme, String displayName, List<String> command) {
        UrlSchemes.validate(scheme);
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(command, "command");
        if (command.isEmpty() || command.get(0).isBlank()) {
            throw new IllegalArgumentException("the command must start with the program to run");
        }
        String line = CommandLines.windows(command) + " \"%1\"";
        if (line.length() > MAX_COMMAND_CHARS) {
            throw new IllegalArgumentException("the command line is too long for the registry (" + line.length() + " characters)");
        }
        String root = CLASSES + scheme;
        setString(root, null, "URL:" + displayName.replace('\0', ' '));
        setString(root, "URL Protocol", "");
        setString(root + "\\shell\\open\\command", null, line);
    }

    @Override
    public void unregister(String scheme) {
        UrlSchemes.validate(scheme);
        try (Arena arena = Arena.ofConfined()) {
            int status = (int) REG_DELETE_TREE.invokeExact(HKEY_CURRENT_USER, Native.allocWide(arena, CLASSES + scheme));
            if (status != ERROR_SUCCESS && status != ERROR_FILE_NOT_FOUND) {
                throw new JoltException("RegDeleteTreeW failed: Win32 error " + status);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Sets a string value ({@code name} null is the key's default value), creating the key and its parents if needed. */
    private static void setString(String subKey, String name, String value) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int status = (int) REG_CREATE_KEY_EX.invokeExact(HKEY_CURRENT_USER, Native.allocWide(arena, subKey), 0, MemorySegment.NULL, 0,
                    KEY_SET_VALUE | KEY_CREATE_SUB_KEY, MemorySegment.NULL, out, MemorySegment.NULL);
            if (status != ERROR_SUCCESS) {
                throw new JoltException("RegCreateKeyExW failed: Win32 error " + status);
            }
            MemorySegment key = out.get(ADDRESS, 0);
            try {
                byte[] utf16 = (value + "\0").getBytes(StandardCharsets.UTF_16LE);
                MemorySegment data = arena.allocateFrom(JAVA_BYTE, utf16);
                MemorySegment valueName = name == null ? MemorySegment.NULL : Native.allocWide(arena, name);
                status = (int) REG_SET_VALUE_EX.invokeExact(key, valueName, 0, REG_SZ, data, utf16.length);
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
