package io.github.b077as.jolt.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The command that starts this program again, for jump list entries. A native launcher (jpackage, an installed program) is just its
 * own path. For {@code java}/{@code javaw} Windows does not tell a process its arguments, so the command is rebuilt from the
 * class path and the main class the JVM reports: {@code -jar app.jar} or {@code -cp <path> Main}. JVM options are not carried over
 * (a jar's manifest can hold {@code Enable-Native-Access}).
 */
public final class RunningCommand {

    private RunningCommand() {}

    /** The command of this process, or an empty list if it cannot be worked out. */
    public static List<String> ofThisProcess() {
        String exe = ProcessHandle.current().info().command().orElse(null);
        return rebuild(exe, System.getProperty("sun.java.command"), System.getProperty("java.class.path"));
    }

    /** Pure part of {@link #ofThisProcess}, for tests. {@code javaCommand} is the {@code sun.java.command} property. */
    public static List<String> rebuild(String exe, String javaCommand, String classPath) {
        if (exe == null || exe.isBlank()) {
            return List.of();
        }
        String name = exe.substring(Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
        boolean isJava = name.equals("java") || name.equals("javaw") || name.equals("java.exe") || name.equals("javaw.exe");
        List<String> command = new ArrayList<>();
        command.add(exe);
        if (!isJava) {
            return command; // a native launcher starts itself
        }
        if (javaCommand == null || javaCommand.isBlank() || classPath == null || classPath.isBlank()) {
            return List.of();
        }
        String first = javaCommand.strip().split(" ", 2)[0];
        boolean jar = first.toLowerCase(Locale.ROOT).endsWith(".jar") && !classPath.contains(File.pathSeparator);
        if (jar) {
            command.add("-jar");
            command.add(classPath); // in -jar mode the class path is the jar itself
        } else {
            command.add("-cp");
            command.add(classPath);
            command.add(first);
        }
        return command;
    }
}
