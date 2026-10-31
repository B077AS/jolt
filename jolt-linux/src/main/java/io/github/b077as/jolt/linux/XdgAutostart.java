package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.Autostart;
import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.internal.CommandLines;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/**
 * Starts the application at sign-in with a freedesktop autostart entry, {@code <config>/autostart/<appId>.desktop}, which GNOME, KDE,
 * XFCE and the other desktops that follow the autostart specification read. Plain file handling, so it needs no display.
 */
final class XdgAutostart implements Autostart {

    private final String appId;
    private final Path file;

    XdgAutostart(String appId, Path autostartDirectory) {
        this.appId = appId;
        this.file = autostartDirectory.resolve(fileName(appId));
    }

    /** {@code $XDG_CONFIG_HOME/autostart}, or {@code ~/.config/autostart}. */
    static Path defaultDirectory() {
        String config = System.getenv("XDG_CONFIG_HOME");
        Path base = config != null && !config.isBlank() && Path.of(config).isAbsolute()
                ? Path.of(config) : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("autostart");
    }

    private static String fileName(String appId) {
        return appId.replaceAll("[^A-Za-z0-9._-]", "_").replaceFirst("^\\.+", "_") + ".desktop"; // never hidden, never a path
    }

    @Override
    public boolean isEnabled() {
        return Files.isRegularFile(file);
    }

    @Override
    public void enable(List<String> command) {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty() || command.get(0).isBlank()) {
            throw new IllegalArgumentException("the command must start with the program to run");
        }
        for (String part : command) {
            if (part.indexOf('\n') >= 0 || part.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("a command argument must not contain a line break");
            }
        }
        String name = appId.replaceAll("[\\r\\n]", " ");
        String text = "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Version=1.0\n"
                + "Name=" + name + "\n"
                + "Exec=" + CommandLines.desktopExec(command) + "\n"
                + "Terminal=false\n"
                + "X-GNOME-Autostart-enabled=true\n";
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), ".jolt-autostart-", ".tmp");
            try {
                Files.writeString(temporary, text, StandardCharsets.UTF_8);
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new JoltException("cannot write the autostart entry " + file, e);
        }
    }

    @Override
    public void disable() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new JoltException("cannot remove the autostart entry " + file, e);
        }
    }
}
