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
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The freedesktop.org Secret Service (GNOME Keyring, KWallet, KeePassXC) through {@code libsecret}'s simple password API:
 * {@code secret_password_store_sync}, {@code secret_password_lookup_sync}, {@code secret_password_clear_sync}.
 * Entries live in the default collection and are found by two attributes, {@code service} and {@code account}.
 * Not part of the public API; use {@link CredentialStore#open}.
 */
public final class LinuxSecretServiceStore implements CredentialStore {

    private static final String SCHEMA_NAME = "io.github.b077as.jolt.credentials";
    private static final String COLLECTION_DEFAULT = "default";

    private static final StructLayout SCHEMA_ATTRIBUTE = MemoryLayout.structLayout(
            ADDRESS.withName("name"),
            JAVA_INT.withName("type"),
            MemoryLayout.paddingLayout(4));

    /** {@code SecretSchema} from {@code secret-schema.h}: name, flags, 32 attributes (a NULL name ends the list), reserved fields. */
    static final StructLayout SCHEMA = MemoryLayout.structLayout(
            ADDRESS.withName("name"),
            JAVA_INT.withName("flags"),
            MemoryLayout.paddingLayout(4),
            MemoryLayout.sequenceLayout(32, SCHEMA_ATTRIBUTE).withName("attributes"),
            JAVA_INT.withName("reserved"),
            MemoryLayout.paddingLayout(4),
            MemoryLayout.sequenceLayout(7, ADDRESS).withName("reservedPointers"));

    private static final long SCHEMA_NAME_OFFSET = SCHEMA.byteOffset(MemoryLayout.PathElement.groupElement("name"));
    private static final long SCHEMA_ATTRIBUTES_OFFSET = SCHEMA.byteOffset(MemoryLayout.PathElement.groupElement("attributes"));
    private static final long ATTRIBUTE_SIZE = SCHEMA_ATTRIBUTE.byteSize();
    /** {@code SECRET_SERVICE_LOAD_COLLECTIONS}: the service object also loads its collections, which looking one up by alias needs. */
    private static final int SECRET_SERVICE_LOAD_COLLECTIONS = 1 << 1;
    private static final int SECRET_SCHEMA_NONE = 0;
    private static final int SECRET_SCHEMA_ATTRIBUTE_STRING = 0;
    /** {@code GError}: a guint32 domain, a gint code, then the message. */
    private static final long GERROR_MESSAGE = 8;
    private static final long GERROR_SIZE = 16;

    /** The native functions, bound once. Kept apart so that merely loading this class on another OS touches no library. */
    private static final class Api {
        private static final Api INSTANCE = load();

        final MethodHandle store;
        final MethodHandle lookup;
        final MethodHandle clear;
        final MethodHandle free;
        final MethodHandle errorFree;
        final MethodHandle serviceGet;
        final MethodHandle collectionForAlias;
        final MethodHandle unref;

        private Api(SymbolLookup secret, SymbolLookup glib, SymbolLookup gobject) {
            Linker linker = Linker.nativeLinker();
            // The three password calls take attribute name/value pairs as C varargs, ended by NULL. A variadic downcall
            // must say how many it passes, so each handle is built for exactly two pairs plus the terminator.
            store = linker.downcallHandle(find(secret, "secret_password_store_sync"),
                    descriptor(JAVA_INT, 6, 5), Linker.Option.firstVariadicArg(6));
            lookup = linker.downcallHandle(find(secret, "secret_password_lookup_sync"),
                    descriptor(ADDRESS, 3, 5), Linker.Option.firstVariadicArg(3));
            clear = linker.downcallHandle(find(secret, "secret_password_clear_sync"),
                    descriptor(JAVA_INT, 3, 5), Linker.Option.firstVariadicArg(3));
            free = linker.downcallHandle(find(secret, "secret_password_free"), FunctionDescriptor.ofVoid(ADDRESS));
            errorFree = linker.downcallHandle(find(glib, "g_error_free"), FunctionDescriptor.ofVoid(ADDRESS));
            // SecretCollection *secret_collection_for_alias_sync(SecretService *service, const gchar *alias,
            //                                                    SecretCollectionFlags flags, GCancellable *cancellable, GError **error)
            // SecretService *secret_service_get_sync(SecretServiceFlags flags, GCancellable *cancellable, GError **error)
            serviceGet = linker.downcallHandle(find(secret, "secret_service_get_sync"),
                    FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
            collectionForAlias = linker.downcallHandle(find(secret, "secret_collection_for_alias_sync"),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
            unref = linker.downcallHandle(find(gobject, "g_object_unref"), FunctionDescriptor.ofVoid(ADDRESS));
        }

        private static FunctionDescriptor descriptor(MemoryLayout result, int fixed, int variadic) {
            MemoryLayout[] arguments = new MemoryLayout[fixed + variadic];
            java.util.Arrays.fill(arguments, ADDRESS);
            return FunctionDescriptor.of(result, arguments);
        }

        private static MemorySegment find(SymbolLookup lib, String symbol) {
            return lib.find(symbol).orElseThrow(() -> new UnsatisfiedLinkError("symbol not found: " + symbol));
        }

        private static Api load() {
            try {
                return new Api(SymbolLookup.libraryLookup("libsecret-1.so.0", Arena.global()),
                        SymbolLookup.libraryLookup("libglib-2.0.so.0", Arena.global()),
                        SymbolLookup.libraryLookup("libgobject-2.0.so.0", Arena.global()));
            } catch (IllegalArgumentException | UnsatisfiedLinkError e) {
                throw new CredentialStoreUnavailableException(
                        "libsecret is not installed (Debian and Ubuntu: libsecret-1-0): " + e.getMessage(), e);
            }
        }
    }

    private final String service;
    private final Api api;

    /**
     * @throws CredentialStoreUnavailableException if libsecret is missing, no Secret Service answers, or the service has no
     *         default keyring to put secrets in. It writes nothing; it only asks the service for its default keyring.
     */
    public LinuxSecretServiceStore(String service) {
        Names.checkService(service);
        if (ADDRESS.byteSize() != 8) {
            throw new CredentialStoreUnavailableException("Secret Service support needs a 64-bit system");
        }
        this.service = service;
        this.api = Api.INSTANCE;
        try (Arena arena = Arena.ofConfined()) {
            // An error means no Secret Service answered. A NULL without an error means it answered but has no default
            // keyring (a service can run with none, and then every store fails): not usable either.
            MemorySegment error = arena.allocate(ADDRESS);
            MemorySegment secretService = (MemorySegment) api.serviceGet.invokeExact(SECRET_SERVICE_LOAD_COLLECTIONS, MemorySegment.NULL, error);
            String message = takeError(error);
            if (message != null || secretService.equals(MemorySegment.NULL)) {
                throw new CredentialStoreUnavailableException("No Secret Service is available" + (message == null ? "" : ": " + message));
            }
            try {
                MemorySegment collection = (MemorySegment) api.collectionForAlias.invokeExact(secretService,
                        arena.allocateFrom(COLLECTION_DEFAULT), 0, MemorySegment.NULL, error);
                message = takeError(error);
                if (message != null) {
                    throw new CredentialStoreUnavailableException("The Secret Service has no usable default keyring: " + message);
                }
                if (collection.equals(MemorySegment.NULL)) {
                    throw new CredentialStoreUnavailableException(
                            "The Secret Service has no default keyring. Create one (for example with Seahorse or by logging in to a desktop session).");
                }
                api.unref.invokeExact(collection);
            } finally {
                api.unref.invokeExact(secretService);
            }
        } catch (CredentialStoreException e) {
            throw e;
        } catch (Throwable t) {
            throw new CredentialStoreUnavailableException("Could not reach the Secret Service: " + t, t);
        }
    }

    @Override
    public Optional<String> get(String account) {
        Names.checkAccount(account);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(ADDRESS);
            MemorySegment found = lookup(arena, account, error);
            String message = takeError(error);
            if (message != null) {
                throw failure("read", message);
            }
            if (found.equals(MemorySegment.NULL)) {
                return Optional.empty();
            }
            try {
                return Optional.of(found.reinterpret(Long.MAX_VALUE).getString(0));
            } finally {
                api.free.invokeExact(found); // libsecret wipes the memory before freeing it
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
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(ADDRESS);
            int ok = (int) api.store.invokeExact(schema(arena), arena.allocateFrom(COLLECTION_DEFAULT),
                    arena.allocateFrom(service + ": " + account), arena.allocateFrom(secret), MemorySegment.NULL, error,
                    arena.allocateFrom("service"), arena.allocateFrom(service),
                    arena.allocateFrom("account"), arena.allocateFrom(account), MemorySegment.NULL);
            String message = takeError(error);
            if (message != null || ok == 0) {
                throw failure("write", message);
            }
        } catch (CredentialStoreException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected("write", t);
        }
    }

    @Override
    public boolean delete(String account) {
        Names.checkAccount(account);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(ADDRESS);
            int removed = (int) api.clear.invokeExact(schema(arena), MemorySegment.NULL, error,
                    arena.allocateFrom("service"), arena.allocateFrom(service),
                    arena.allocateFrom("account"), arena.allocateFrom(account), MemorySegment.NULL);
            String message = takeError(error);
            if (message != null) {
                throw failure("delete", message);
            }
            return removed != 0;
        } catch (CredentialStoreException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected("delete", t);
        }
    }

    private MemorySegment lookup(Arena arena, String account, MemorySegment error) throws Throwable {
        return (MemorySegment) api.lookup.invokeExact(schema(arena), MemorySegment.NULL, error,
                arena.allocateFrom("service"), arena.allocateFrom(service),
                arena.allocateFrom("account"), arena.allocateFrom(account), MemorySegment.NULL);
    }

    /** Builds the schema on the native side: its name and two string attributes, everything else zero. */
    private static MemorySegment schema(Arena arena) {
        MemorySegment schema = arena.allocate(SCHEMA); // zero-filled, so the attribute list ends after the second entry
        schema.set(ADDRESS, SCHEMA_NAME_OFFSET, arena.allocateFrom(SCHEMA_NAME));
        schema.set(JAVA_INT, SCHEMA_NAME_OFFSET + ADDRESS.byteSize(), SECRET_SCHEMA_NONE);
        String[] attributes = {"service", "account"};
        for (int i = 0; i < attributes.length; i++) {
            long entry = SCHEMA_ATTRIBUTES_OFFSET + i * ATTRIBUTE_SIZE;
            schema.set(ADDRESS, entry, arena.allocateFrom(attributes[i]));
            schema.set(JAVA_INT, entry + ADDRESS.byteSize(), SECRET_SCHEMA_ATTRIBUTE_STRING);
        }
        return schema;
    }

    /** Returns the message of the GError that {@code slot} points to (and frees it), or null if there was none. */
    private String takeError(MemorySegment slot) throws Throwable {
        MemorySegment error = slot.get(ADDRESS, 0);
        if (error.equals(MemorySegment.NULL)) {
            return null;
        }
        MemorySegment gerror = error.reinterpret(GERROR_SIZE);
        MemorySegment text = gerror.get(ADDRESS, GERROR_MESSAGE);
        String message = text.equals(MemorySegment.NULL) ? "unknown error" : text.reinterpret(Long.MAX_VALUE).getString(0);
        api.errorFree.invokeExact(error);
        return message;
    }

    private static CredentialStoreException failure(String action, String message) {
        return new CredentialStoreException("The Secret Service could not " + action + " the secret"
                + (message == null ? "" : ": " + message));
    }

    private static CredentialStoreException unexpected(String action, Throwable t) {
        return new CredentialStoreException("The Secret Service failed to " + action + " the secret: " + t, t);
    }
}
