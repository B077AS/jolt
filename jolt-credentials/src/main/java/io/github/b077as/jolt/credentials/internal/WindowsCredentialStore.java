package io.github.b077as.jolt.credentials.internal;

import io.github.b077as.jolt.credentials.CredentialStore;
import io.github.b077as.jolt.credentials.CredentialStoreException;
import io.github.b077as.jolt.credentials.CredentialStoreUnavailableException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Windows Credential Manager through {@code advapi32}: {@code CredWriteW}, {@code CredReadW}, {@code CredDeleteW}.
 * Entries are generic credentials ({@code CRED_TYPE_GENERIC}) that persist for the user on this machine, named
 * {@code service/account}. Not part of the public API; use {@link CredentialStore#open}.
 */
public final class WindowsCredentialStore implements CredentialStore {

    /** The largest secret Credential Manager accepts ({@code CRED_MAX_CREDENTIAL_BLOB_SIZE}), in UTF-8 bytes. */
    public static final int MAX_SECRET_BYTES = 2560;

    private static final int CRED_TYPE_GENERIC = 1;
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    private static final int ERROR_NOT_FOUND = 1168;

    /** {@code CREDENTIALW} on 64-bit Windows, field by field, so a wrong offset shows up in a test and not as a crash. */
    static final StructLayout CREDENTIAL = MemoryLayout.structLayout(
            JAVA_INT.withName("Flags"),
            JAVA_INT.withName("Type"),
            ADDRESS.withName("TargetName"),
            ADDRESS.withName("Comment"),
            JAVA_LONG.withName("LastWritten"),
            JAVA_INT.withName("CredentialBlobSize"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("CredentialBlob"),
            JAVA_INT.withName("Persist"),
            JAVA_INT.withName("AttributeCount"),
            ADDRESS.withName("Attributes"),
            ADDRESS.withName("TargetAlias"),
            ADDRESS.withName("UserName"));

    private static long offset(String field) {
        return CREDENTIAL.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }

    private static final long TYPE = offset("Type");
    private static final long TARGET_NAME = offset("TargetName");
    private static final long BLOB_SIZE = offset("CredentialBlobSize");
    private static final long BLOB = offset("CredentialBlob");
    private static final long PERSIST = offset("Persist");
    private static final long USER_NAME = offset("UserName");

    /**
     * The native functions, bound once. Kept apart so that merely loading this class on another OS touches nothing
     * Windows-specific (the captured call state, for one, has no {@code GetLastError} member outside Windows).
     */
    private static final class Api {
        static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
        static final long LAST_ERROR = CAPTURE.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"));

        private static final Api INSTANCE = load();

        final MethodHandle write;
        final MethodHandle read;
        final MethodHandle delete;
        final MethodHandle free;

        private Api(SymbolLookup advapi32) {
            Linker linker = Linker.nativeLinker();
            Linker.Option lastError = Linker.Option.captureCallState("GetLastError");
            write = linker.downcallHandle(find(advapi32, "CredWriteW"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), lastError);
            read = linker.downcallHandle(find(advapi32, "CredReadW"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), lastError);
            delete = linker.downcallHandle(find(advapi32, "CredDeleteW"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), lastError);
            free = linker.downcallHandle(find(advapi32, "CredFree"), FunctionDescriptor.ofVoid(ADDRESS));
        }

        private static MemorySegment find(SymbolLookup lib, String symbol) {
            return lib.find(symbol).orElseThrow(() -> new UnsatisfiedLinkError("symbol not found: " + symbol));
        }

        private static Api load() {
            try {
                return new Api(SymbolLookup.libraryLookup("advapi32.dll", Arena.global()));
            } catch (IllegalArgumentException | UnsatisfiedLinkError e) {
                throw new CredentialStoreUnavailableException("Could not load Windows Credential Manager (advapi32.dll): " + e.getMessage(), e);
            }
        }
    }

    private final String service;
    private final Api api;

    public WindowsCredentialStore(String service) {
        Names.checkService(service);
        if (ADDRESS.byteSize() != 8) {
            throw new CredentialStoreUnavailableException("Credential Manager support needs 64-bit Windows");
        }
        this.service = service;
        this.api = Api.INSTANCE;
    }

    private String target(String account) {
        return service + "/" + account;
    }

    @Override
    public Optional<String> get(String account) {
        Names.checkAccount(account);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(Api.CAPTURE);
            MemorySegment out = arena.allocate(ADDRESS);
            int ok = (int) api.read.invokeExact(capture, wide(arena, target(account)), CRED_TYPE_GENERIC, 0, out);
            if (ok == 0) {
                int error = capture.get(JAVA_INT, Api.LAST_ERROR);
                if (error == ERROR_NOT_FOUND) {
                    return Optional.empty();
                }
                throw failure("read", error);
            }
            MemorySegment credential = out.get(ADDRESS, 0).reinterpret(CREDENTIAL.byteSize());
            try {
                int size = credential.get(JAVA_INT, BLOB_SIZE);
                byte[] bytes = credential.get(ADDRESS, BLOB).reinterpret(size).toArray(JAVA_BYTE);
                try {
                    return Optional.of(new String(bytes, StandardCharsets.UTF_8));
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            } finally {
                api.free.invokeExact(credential);
            }
        } catch (CredentialStoreException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected("read", t);
        }
    }

    @Override
    public void put(String account, String secret) {
        Names.checkAccount(account);
        Names.checkSecret(secret);
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        try (Arena arena = Arena.ofConfined()) {
            if (bytes.length > MAX_SECRET_BYTES) {
                throw new IllegalArgumentException("Credential Manager accepts at most " + MAX_SECRET_BYTES + " bytes; this secret has " + bytes.length);
            }
            MemorySegment blob = arena.allocateFrom(JAVA_BYTE, bytes);
            MemorySegment credential = arena.allocate(CREDENTIAL); // zero-filled
            credential.set(JAVA_INT, TYPE, CRED_TYPE_GENERIC);
            credential.set(ADDRESS, TARGET_NAME, wide(arena, target(account)));
            credential.set(JAVA_INT, BLOB_SIZE, bytes.length);
            credential.set(ADDRESS, BLOB, blob);
            credential.set(JAVA_INT, PERSIST, CRED_PERSIST_LOCAL_MACHINE);
            credential.set(ADDRESS, USER_NAME, wide(arena, account));
            MemorySegment capture = arena.allocate(Api.CAPTURE);
            try {
                int ok = (int) api.write.invokeExact(capture, credential, 0);
                if (ok == 0) {
                    throw failure("write", capture.get(JAVA_INT, Api.LAST_ERROR));
                }
            } finally {
                blob.fill((byte) 0);
            }
        } catch (CredentialStoreException | IllegalArgumentException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected("write", t);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public boolean delete(String account) {
        Names.checkAccount(account);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(Api.CAPTURE);
            int ok = (int) api.delete.invokeExact(capture, wide(arena, target(account)), CRED_TYPE_GENERIC, 0);
            if (ok != 0) {
                return true;
            }
            int error = capture.get(JAVA_INT, Api.LAST_ERROR);
            if (error == ERROR_NOT_FOUND) {
                return false;
            }
            throw failure("delete", error);
        } catch (CredentialStoreException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected("delete", t);
        }
    }

    private static MemorySegment wide(Arena arena, String text) {
        return arena.allocateFrom(text, StandardCharsets.UTF_16LE);
    }

    private static CredentialStoreException failure(String action, int error) {
        return new CredentialStoreException("Windows Credential Manager could not " + action + " the secret (Win32 error " + error + ")");
    }

    private static CredentialStoreException unexpected(String action, Throwable t) {
        return new CredentialStoreException("Windows Credential Manager failed to " + action + " the secret: " + t, t);
    }
}
