package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.internal.ffi.Native;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Microsoft's redistributable {@code WebView2Loader.dll}: finds the installed WebView2 runtime and starts an
 * environment. It is the only native binary Jolt ships. The DLL is embedded in this JAR and extracted to a
 * content-addressed location, so a stale or tampered cached copy is never loaded.
 */
final class WebView2Loader {

    private static final String RESOURCE_DIR = "/io/github/b077as/jolt/win32/native/";

    private static volatile WebView2Loader instance;

    private final MethodHandle createEnvironment;
    private final MethodHandle availableVersion;

    private WebView2Loader(SymbolLookup lib) {
        // HRESULT CreateCoreWebView2EnvironmentWithOptions(PCWSTR browserFolder, PCWSTR userDataFolder,
        //                                                   ICoreWebView2EnvironmentOptions*, completedHandler*)
        this.createEnvironment = Native.downcall(lib, "CreateCoreWebView2EnvironmentWithOptions",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        // HRESULT GetAvailableCoreWebView2BrowserVersionString(PCWSTR browserFolder, LPWSTR* version)
        this.availableVersion = Native.downcall(lib, "GetAvailableCoreWebView2BrowserVersionString",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    }

    static WebView2Loader get() {
        WebView2Loader local = instance;
        if (local == null) {
            synchronized (WebView2Loader.class) {
                local = instance;
                if (local == null) {
                    Path dll = extract();
                    instance = local = new WebView2Loader(SymbolLookup.libraryLookup(dll, Arena.global()));
                }
            }
        }
        return local;
    }

    /** The installed WebView2 runtime version (for example {@code 155.0.4283.62}), or {@code null} if none. */
    String installedRuntimeVersion() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) availableVersion.invokeExact(MemorySegment.NULL, out);
            if (hr < 0) {
                return null;
            }
            MemorySegment text = out.get(ADDRESS, 0);
            if (text.equals(MemorySegment.NULL)) {
                return null;
            }
            String version = Native.readWide(text);
            Win32.coTaskMemFree(text);
            return version;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Starts creating an environment; {@code handler} is invoked on this thread's message loop. */
    int createEnvironment(Path userDataFolder, MemorySegment handler) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) createEnvironment.invokeExact(MemorySegment.NULL, Native.allocWide(arena, userDataFolder.toString()),
                    MemorySegment.NULL, handler);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static Path extract() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String dir = switch (arch) {
            case "amd64", "x86_64" -> "win-x64";
            case "aarch64", "arm64" -> "win-arm64";
            default -> throw new JoltException("Unsupported Windows architecture: " + arch);
        };
        String resource = RESOURCE_DIR + dir + "/WebView2Loader.dll";
        try (InputStream in = WebView2Loader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new JoltException("Bundled resource missing: " + resource);
            }
            byte[] bytes = in.readAllBytes();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
            Path target = Path.of(System.getProperty("java.io.tmpdir"), "jolt-natives", hash, "WebView2Loader.dll");
            if (Files.isRegularFile(target) && java.util.Arrays.equals(Files.readAllBytes(target), bytes)) {
                return target;
            }
            Files.createDirectories(target.getParent());
            Path partial = Files.createTempFile(target.getParent(), "WebView2Loader", ".tmp");
            Files.write(partial, bytes);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new JoltException("Could not extract WebView2Loader.dll", e);
        }
    }
}
