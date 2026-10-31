package io.github.b077as.jolt.internal;

import java.util.ArrayList;
import java.util.List;

/** Turning a command (program and arguments) into the text an autostart entry holds, and back. */
public final class CommandLines {

    private CommandLines() {}

    /** Splits a command line on spaces; double quotes group words and are removed ({@code a "b c"} gives {@code a}, {@code b c}). */
    public static List<String> split(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (c == ' ' && !quoted) {
                if (any) {
                    parts.add(current.toString());
                    current.setLength(0);
                    any = false;
                }
            } else {
                current.append(c);
                any = true;
            }
        }
        if (any) {
            parts.add(current.toString());
        }
        return parts;
    }

    /**
     * The command as one Windows command line: arguments with a space, a tab or a quote are put in double quotes, with the quotes
     * and the backslashes before them escaped the way {@code CommandLineToArgvW} reads them back.
     */
    public static String windows(List<String> command) {
        StringBuilder line = new StringBuilder();
        for (String argument : command) {
            if (line.length() > 0) {
                line.append(' ');
            }
            if (!argument.isEmpty() && argument.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '"')) {
                line.append(argument);
                continue;
            }
            line.append('"');
            int backslashes = 0;
            for (int i = 0; i < argument.length(); i++) {
                char c = argument.charAt(i);
                if (c == '\\') {
                    backslashes++;
                } else if (c == '"') {
                    line.append("\\".repeat(backslashes * 2 + 1)).append('"');
                    backslashes = 0;
                } else {
                    line.append("\\".repeat(backslashes)).append(c);
                    backslashes = 0;
                }
            }
            line.append("\\".repeat(backslashes * 2)).append('"');
        }
        return line.toString();
    }

    /**
     * The command as the {@code Exec} value of a freedesktop {@code .desktop} file. An argument with a space or a character the
     * format treats specially is put in double quotes, and {@code "}, {@code `}, {@code $} and {@code \} are escaped inside them
     * (and each of those backslashes doubled, because the file format unescapes the whole value once more); {@code %} is doubled so
     * it is not read as a field code.
     */
    public static String desktopExec(List<String> command) {
        StringBuilder line = new StringBuilder();
        for (String argument : command) {
            if (line.length() > 0) {
                line.append(' ');
            }
            String escaped = argument.replace("%", "%%");
            boolean special = escaped.isEmpty() || escaped.chars().anyMatch(c -> " \t\n\"'\\><~|&;$*?#()`".indexOf(c) >= 0);
            if (!special) {
                line.append(escaped);
                continue;
            }
            line.append('"');
            for (int i = 0; i < escaped.length(); i++) {
                char c = escaped.charAt(i);
                if (c == '\\') {
                    line.append("\\\\\\\\"); // one backslash: escaped for Exec, then each of the two doubled for the file value
                } else if (c == '"' || c == '`' || c == '$') {
                    line.append("\\\\").append(c);
                } else {
                    line.append(c);
                }
            }
            line.append('"');
        }
        return line.toString();
    }
}
