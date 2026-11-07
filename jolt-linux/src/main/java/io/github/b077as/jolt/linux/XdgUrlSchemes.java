package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.UrlSchemes;
import io.github.b077as.jolt.internal.CommandLines;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Links of a scheme start the application through a freedesktop entry {@code <data>/applications/<appId>-<scheme>.desktop} that
 * declares {@code MimeType=x-scheme-handler/<scheme>} and {@code Exec=... %u}, made the user's default for that type with
 * {@code xdg-mime default}. Without {@code xdg-mime} the entry is still written, but the desktop may not pick it as the default.
 */
final class XdgUrlSchemes implements UrlSchemes {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.linux");

    private final String appId;
    private final Path directory;
    /** Called with (desktop file name, mime type) to make the entry the default handler. */
    private final BiConsumer<String, String> setDefault;

    XdgUrlSchemes(String appId, Path applicationsDirectory, BiConsumer<String, String> setDefault) {
        this.appId = appId;
        this.directory = applicationsDirectory;
        this.setDefault = setDefault;
    }

    /** {@code $XDG_DATA_HOME/applications}, or {@code ~/.local/share/applications}. */
    static Path defaultDirectory() {
        String data = System.getenv("XDG_DATA_HOME");
        Path base = data != null && !data.isBlank() && Path.of(data).isAbsolute()
                ? Path.of(data) : Path.of(System.getProperty("user.home"), ".local", "share");
        return base.resolve("applications");
    }

    /** Runs {@code xdg-mime default <file> <mime>}; a missing or failing tool is logged, not fatal. */
    static void xdgMimeDefault(String desktopFile, String mimeType) {
        try {
            Process process = new ProcessBuilder("xdg-mime", "default", desktopFile, mimeType)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOG.log(System.Logger.Level.WARNING, "xdg-mime did not finish; the URL handler may not be the default");
            } else if (process.exitValue() != 0) {
                LOG.log(System.Logger.Level.WARNING, "xdg-mime default failed with exit code " + process.exitValue());
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "xdg-mime is not available; the URL handler may not be the default: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String fileName(String scheme) {
        return appId.replaceAll("[^A-Za-z0-9._-]", "_").replaceFirst("^\\.+", "_") + "-" + scheme + ".desktop";
    }

    @Override
    public boolean isRegistered(String scheme) {
        UrlSchemes.validate(scheme);
        return Files.isRegularFile(directory.resolve(fileName(scheme)));
    }

    @Override
    public void register(String scheme, String displayName, List<String> command) {
        UrlSchemes.validate(scheme);
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(command, "command");
        if (command.isEmpty() || command.get(0).isBlank()) {
            throw new IllegalArgumentException("the command must start with the program to run");
        }
        for (String part : command) {
            if (part.indexOf('\n') >= 0 || part.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("a command argument must not contain a line break");
            }
        }
        String name = displayName.replaceAll("[\\r\\n]", " ");
        String text = "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Version=1.0\n"
                + "Name=" + name + "\n"
                + "Exec=" + CommandLines.desktopExec(command) + " %u\n"
                + "Terminal=false\n"
                + "NoDisplay=true\n"
                + "MimeType=x-scheme-handler/" + scheme + ";\n";
        Path file = directory.resolve(fileName(scheme));
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, ".jolt-scheme-", ".tmp");
            try {
                Files.writeString(temporary, text, StandardCharsets.UTF_8);
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new JoltException("cannot write the URL handler entry " + file, e);
        }
        setDefault.accept(file.getFileName().toString(), "x-scheme-handler/" + scheme);
    }

    @Override
    public void unregister(String scheme) {
        UrlSchemes.validate(scheme);
        Path file = directory.resolve(fileName(scheme));
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new JoltException("cannot remove the URL handler entry " + file, e);
        }
    }
}
