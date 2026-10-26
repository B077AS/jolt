package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.AppOptions;
import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.spi.Platform;

import java.util.Locale;

/**
 * Linux backend: GTK 3 window hosting WebKitGTK 4.1, on X11 and Wayland (chosen by GDK at runtime).
 * Registered via {@code ServiceLoader}.
 */
public final class LinuxPlatform implements Platform {

    static final String INSTALL_HINT =
            "install libgtk-3-0 and libwebkit2gtk-4.1-0 (Debian/Ubuntu), webkit2gtk4.1 (Fedora) or webkit2gtk-4.1 (Arch)";

    private volatile String reason = "not checked";

    @Override
    public String name() {
        return "linux-webkitgtk";
    }

    @Override
    public boolean isSupported() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            reason = "not running on Linux";
            return false;
        }
        try {
            Gtk.load();
            return true;
        } catch (Throwable t) {
            reason = "GTK 3 / WebKitGTK 4.1 could not be loaded (" + t.getMessage() + "); " + INSTALL_HINT;
            return false;
        }
    }

    @Override
    public String unsupportedReason() {
        return reason;
    }

    @Override
    public Application createApplication(AppOptions options) {
        return new LinuxApplication(options);
    }
}
