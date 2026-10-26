package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.AppOptions;
import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.spi.Platform;

import java.util.Locale;

/** Windows backend: Win32 windows hosting Microsoft Edge WebView2. Registered via {@code ServiceLoader}. */
public final class WindowsPlatform implements Platform {

    private volatile String reason = "not checked";

    @Override
    public String name() {
        return "windows-webview2";
    }

    @Override
    public boolean isSupported() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            reason = "not running on Windows";
            return false;
        }
        try {
            String version = WebView2Loader.get().installedRuntimeVersion();
            if (version == null) {
                reason = "the Microsoft Edge WebView2 Runtime is not installed "
                        + "(https://developer.microsoft.com/microsoft-edge/webview2/)";
                return false;
            }
            return true;
        } catch (Throwable t) {
            reason = t.toString();
            return false;
        }
    }

    @Override
    public String unsupportedReason() {
        return reason;
    }

    @Override
    public Application createApplication(AppOptions options) {
        return new WindowsApplication(options);
    }
}
