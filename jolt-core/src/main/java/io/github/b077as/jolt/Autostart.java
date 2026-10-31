package io.github.b077as.jolt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Starting the application when the user signs in. Windows: a value under {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run}.
 * Linux: a {@code ~/.config/autostart/<appId>.desktop} file. Both belong to the user and need no administrator rights. Get it from
 * {@link Application#autostart()}.
 *
 * <p>To start <em>minimized</em>, add an argument to the command, such as {@code --minimized}, and have the application read it
 * at launch and start hidden (with a {@link Tray} to bring it back). To change that setting later, call {@link #enable} again.
 */
public interface Autostart {

    /** Whether an entry exists for this application. */
    boolean isEnabled();

    /**
     * Creates or replaces the entry. {@code command} is the program followed by its arguments, for example
     * {@code List.of("C:\\Apps\\Parsel\\Parsel.exe", "--minimized")}. For an installed or packaged application give the path of its
     * launcher; {@link #currentCommand} describes the running process, which suits an application started with {@code java -jar}.
     */
    void enable(List<String> command);

    /** Removes the entry; does nothing if there is none. */
    void disable();

    /**
     * The command line of the running process followed by {@code extraArguments}, best effort: the operating system has to report it,
     * which it does for {@code java -jar app.jar} and for a packaged launcher.
     *
     * @throws IllegalStateException if the operating system does not say how this process was started
     */
    static List<String> currentCommand(List<String> extraArguments) {
        ProcessHandle.Info info = ProcessHandle.current().info();
        List<String> command = new ArrayList<>();
        Optional<String[]> arguments = info.arguments();
        if (info.command().isPresent() && arguments.isPresent() && arguments.get().length > 0) {
            command.add(info.command().get());
            command.addAll(Arrays.asList(arguments.get()));
        } else if (info.commandLine().isPresent()) {
            command.addAll(io.github.b077as.jolt.internal.CommandLines.split(info.commandLine().get()));
        } else if (info.command().isPresent()) {
            command.add(info.command().get());
        } else {
            throw new IllegalStateException("the operating system does not report how this process was started");
        }
        command.addAll(extraArguments);
        return command;
    }
}
