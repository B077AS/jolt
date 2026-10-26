package io.github.b077as.jolt.spi;

import io.github.b077as.jolt.JoltException;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/** Finds the platform backend to use. */
public final class Platforms {

    private Platforms() {}

    /** Returns the first backend on the class path that reports {@link Platform#isSupported()}. */
    public static Platform select() {
        List<String> notes = new ArrayList<>();
        for (Platform platform : ServiceLoader.load(Platform.class)) {
            if (platform.isSupported()) {
                return platform;
            }
            notes.add(platform.name() + ": " + platform.unsupportedReason());
        }
        String detail = notes.isEmpty()
                ? "no backend on the class path (add jolt-win32 or jolt-linux)"
                : String.join("; ", notes);
        throw new JoltException("No usable Jolt platform backend: " + detail);
    }
}
