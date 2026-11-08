package io.github.b077as.jolt.linux;

import io.github.b077as.jolt.internal.CommandLines;
import io.github.b077as.jolt.internal.Icons;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Gives a Wayland window its icon. A Wayland application cannot set its own window icon: the compositor looks up
 * {@code <app_id>.desktop} (the app id is the application id) and draws that file's {@code Icon=}, for the title bar, the
 * task bar and the alt-tab list, and shows a generic placeholder (KDE: the Wayland "W") when no such file exists. So
 * {@code setIcon} has to reach the compositor through the user's own launcher data:
 *
 * <ul>
 *   <li>the icon goes into the user's icon theme, {@code $XDG_DATA_HOME/icons/hicolor/<size>/apps/<appId>-<hash>.png}, one file
 *       per size the icon holds (a Windows {@code .ico} holds several). The file name carries a hash of the picture because
 *       desktops cache icons by name, so a new picture needs a new name to show up in a window that is already open;</li>
 *   <li>{@code $XDG_DATA_HOME/applications/<appId>.desktop} points at it. If the system has an entry with this id (a package
 *       installed one) it is copied with only the icon replaced, so the application's menu entry stays as it was; if there
 *       is none (an AppImage, an archive, a development run) a hidden entry ({@code NoDisplay=true}) is made that starts the
 *       running program. A user entry that this class did not write is never touched.</li>
 * </ul>
 *
 * Nothing is done for the icon outside Wayland, where the window icon property works. The same file also carries the launcher
 * menu: {@link #setActions} writes the application's jump list tasks as {@code [Desktop Action ...]} groups, on any desktop. Every
 * failure is logged and ignored: a missing icon or menu must not stop the application.
 */
final class DesktopEntry {

    private static final System.Logger LOG = System.getLogger(DesktopEntry.class.getName());
    private static final String MARKER = "X-Jolt-Generated=true";

    private final String appId;
    private final String name;
    private final Path dataHome;
    private final List<Path> systemDirectories;
    private List<String> command;
    private String installedHash = "";
    private String iconName; // the icon installed so far, or null
    private List<Action> actions = List.of();
    private boolean icons = true; // false off Wayland: the window icon property works there

    /** One entry of the launcher menu: a title and the arguments the program is started with. */
    record Action(String name, List<String> arguments) {}

    DesktopEntry(String appId, String name, Path dataHome, List<Path> systemDirectories, List<String> command) {
        this.appId = appId;
        this.name = name;
        this.dataHome = dataHome;
        this.systemDirectories = systemDirectories;
        this.command = command;
    }

    /** The entry for the running program with the usual locations. Off Wayland it does not install icons, only actions. */
    static DesktopEntry forThisProcess(String appId, String name) {
        String home = System.getenv("XDG_DATA_HOME");
        Path dataHome = home != null && !home.isBlank() && Path.of(home).isAbsolute()
                ? Path.of(home) : Path.of(System.getProperty("user.home"), ".local", "share");
        String dirs = System.getenv("XDG_DATA_DIRS");
        List<Path> system = new ArrayList<>();
        for (String dir : (dirs == null || dirs.isBlank() ? "/usr/local/share:/usr/share" : dirs).split(":")) {
            if (!dir.isBlank()) {
                system.add(Path.of(dir));
            }
        }
        DesktopEntry entry = new DesktopEntry(appId, name, dataHome, system, runningCommand());
        entry.icons = onWayland();
        return entry;
    }

    static boolean onWayland() {
        String backend = System.getenv("GDK_BACKEND");
        if (backend != null && backend.startsWith("x11")) {
            return false;
        }
        String session = System.getenv("XDG_SESSION_TYPE");
        String display = System.getenv("WAYLAND_DISPLAY");
        return "wayland".equalsIgnoreCase(session) || (display != null && !display.isBlank());
    }

    /** How this program was started: the AppImage file if there is one, else the process's own command line. */
    private static List<String> runningCommand() {
        String appImage = System.getenv("APPIMAGE");
        if (appImage != null && !appImage.isBlank()) {
            return List.of(appImage);
        }
        ProcessHandle.Info info = ProcessHandle.current().info();
        List<String> command = new ArrayList<>();
        info.command().ifPresent(command::add);
        if (!command.isEmpty()) {
            info.arguments().ifPresent(a -> command.addAll(List.of(a)));
        }
        return command;
    }

    /** Installs {@code iconData} (PNG, or an icon file holding PNG images) as the icon of this application. */
    synchronized void install(byte[] iconData) {
        if (!icons) {
            return;
        }
        try {
            TreeMap<Integer, byte[]> images = images(iconData);
            if (images.isEmpty()) {
                return;
            }
            String hash = hash(iconData);
            if (hash.equals(installedHash)) {
                return;
            }
            String iconName = safeId() + "-" + hash;
            if (foreignEntry()) {
                installedHash = hash; // somebody else's entry: theirs decides
                return;
            }
            for (var image : images.entrySet()) {
                Path icon = dataHome.resolve("icons/hicolor").resolve(image.getKey() + "x" + image.getKey())
                        .resolve("apps").resolve(iconName + ".png");
                write(icon, image.getValue());
            }
            this.iconName = iconName;
            writeEntry();
            removeOldIcons(iconName);
            Files.setLastModifiedTime(dataHome.resolve("icons/hicolor"), FileTime.from(Instant.now()));
            installedHash = hash;
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "The desktop entry with the window icon could not be written: " + e);
        }
    }

    /**
     * Sets the launcher menu entries (the tasks of a jump list) as desktop actions, keeping the icon installed so far. An empty list
     * takes them away again. A user entry that this class did not write is never touched.
     */
    synchronized void setActions(List<Action> list, List<String> commandOverride) {
        this.actions = List.copyOf(list);
        if (!commandOverride.isEmpty()) {
            this.command = List.copyOf(commandOverride);
        }
        try {
            if (foreignEntry()) {
                LOG.log(System.Logger.Level.INFO, "The desktop entry " + safeId() + ".desktop was not made by Jolt, so its menu is left as it is");
                return;
            }
            if (actions.isEmpty() && !Files.exists(userEntryPath())) {
                return; // nothing to take away
            }
            writeEntry();
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "The desktop entry with the launcher menu could not be written: " + e);
        }
    }

    private Path userEntryPath() {
        return dataHome.resolve("applications").resolve(safeId() + ".desktop");
    }

    private boolean foreignEntry() throws IOException {
        Path userEntry = userEntryPath();
        return Files.exists(userEntry) && !Files.readString(userEntry, StandardCharsets.UTF_8).contains(MARKER);
    }

    private void writeEntry() throws IOException {
        write(userEntryPath(), entryText(iconName).getBytes(StandardCharsets.UTF_8));
    }

    /** The text of the entry; {@code iconName} may be null (no icon installed yet). */
    private String entryText(String iconName) throws IOException {
        String base = null;
        for (Path dir : systemDirectories) {
            Path file = dir.resolve("applications").resolve(safeId() + ".desktop");
            if (Files.isRegularFile(file)) {
                base = Files.readString(file, StandardCharsets.UTF_8);
                break;
            }
        }
        if (base == null) {
            if (command.isEmpty()) {
                throw new IOException("the program's command line is unknown");
            }
            base = "[Desktop Entry]\nType=Application\nName=" + name.replaceAll("[\\r\\n]", " ") + "\n"
                    + "Exec=" + CommandLines.desktopExec(command) + "\nTerminal=false\nNoDisplay=true\n"
                    + "StartupWMClass=" + appId.replaceAll("[\\r\\n]", " ") + "\n";
        }
        // Replace the icon of the main group only; keep every other line (and every "Desktop Action" group) as it is.
        StringBuilder out = new StringBuilder();
        boolean inMain = false;
        boolean replaced = iconName == null; // with no icon of ours, the entry keeps the one it has
        boolean skipping = false; // inside an action group that our own actions replace
        for (String line : base.split("\n", -1)) {
            if (line.startsWith("[")) {
                if (inMain && !replaced) {
                    out.append("Icon=").append(iconName).append('\n');
                    replaced = true;
                }
                inMain = line.trim().equals("[Desktop Entry]");
                skipping = !actions.isEmpty() && line.startsWith("[Desktop Action ");
            } else if (skipping) {
                continue;
            } else if (inMain && !actions.isEmpty() && line.startsWith("Actions=")) {
                continue;
            } else if (inMain && iconName != null && line.startsWith("Icon=")) {
                if (!replaced) {
                    out.append("Icon=").append(iconName).append('\n');
                    replaced = true;
                }
                continue;
            } else if (line.equals(MARKER)) {
                continue;
            }
            if (!skipping) {
                out.append(line).append('\n');
            }
        }
        String text = out.toString().stripTrailing() + "\n";
        if (!replaced) {
            text = text.replaceFirst("(\\[Desktop Entry]\\n)", "$1Icon=" + iconName + "\n");
        }
        if (!actions.isEmpty()) {
            StringBuilder ids = new StringBuilder();
            StringBuilder groups = new StringBuilder();
            for (int i = 0; i < actions.size(); i++) {
                Action action = actions.get(i);
                List<String> exec = new ArrayList<>(command);
                exec.addAll(action.arguments());
                ids.append("jolt-action-").append(i).append(';');
                groups.append("\n[Desktop Action jolt-action-").append(i).append("]\nName=")
                        .append(action.name().replaceAll("[\\r\\n]", " ")).append("\nExec=")
                        .append(CommandLines.desktopExec(exec)).append('\n');
            }
            text = text.replaceFirst("(\\[Desktop Entry]\\n)",
                    java.util.regex.Matcher.quoteReplacement("[Desktop Entry]\nActions=" + ids + "\n")) + groups;
        }
        return text.replaceFirst("(\\[Desktop Entry]\\n)", "$1" + MARKER + "\n");
    }

    /** Old pictures of this application, so a changing accent colour does not fill the theme. */
    private void removeOldIcons(String keep) {
        Pattern old = Pattern.compile(Pattern.quote(safeId()) + "-[0-9a-f]{12}\\.png");
        Path theme = dataHome.resolve("icons/hicolor");
        try (Stream<Path> sizes = Files.list(theme)) {
            for (Path size : (Iterable<Path>) sizes::iterator) {
                Path apps = size.resolve("apps");
                if (!Files.isDirectory(apps)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(apps)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        String fileName = file.getFileName().toString();
                        if (old.matcher(fileName).matches() && !fileName.equals(keep + ".png")) {
                            Files.deleteIfExists(file);
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Old window icons were not removed: " + e);
        }
    }

    private String safeId() {
        return appId.replaceAll("[^A-Za-z0-9._-]", "_").replaceFirst("^\\.+", "_");
    }

    /** PNG pictures by size. An icon file gives one per image in it; a PNG gives its own size. */
    static TreeMap<Integer, byte[]> images(byte[] data) {
        TreeMap<Integer, byte[]> result = new TreeMap<>();
        if (data == null) {
            return result;
        }
        if (Icons.isIco(data)) {
            for (Icons.Entry e : Icons.entries(data)) {
                byte[] png = Icons.asPng(data, e);
                if (png != null) {
                    result.put(e.width(), png);
                }
            }
        } else if (Icons.isPng(data, 0) && data.length >= 24) {
            int width = ByteBuffer.wrap(data, 16, 4).getInt();
            if (width > 0 && width <= 1024) {
                result.put(width, data);
            }
        }
        return result;
    }

    private static String hash(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".jolt-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-r--r--"));
            } catch (UnsupportedOperationException notPosix) {
                // a file system without POSIX permissions (the tests run on Windows too): the default is fine
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
