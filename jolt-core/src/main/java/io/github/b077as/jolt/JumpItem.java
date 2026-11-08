package io.github.b077as.jolt;

import java.util.List;
import java.util.Objects;

/**
 * One entry of the menu that opens when the user right-clicks the application's taskbar or launcher icon, see
 * {@link Application#setJumpList}: a title and the command-line arguments the program is started with when it is chosen
 * ("New incognito window" with {@code --incognito}). Choosing it starts the program again, so receive the arguments in the
 * running copy with {@link SingleInstance}. Immutable.
 *
 * @param title        shown in the menu, at most {@link #MAX_TITLE} characters, no line breaks
 * @param arguments    passed to the program, after its own command; may be empty
 * @param description  Windows: the tooltip of the entry; may be empty
 * @param icon         Windows: PNG or ICO data for the entry, or {@code null} for the program's own icon; Linux ignores it
 */
public record JumpItem(String title, List<String> arguments, String description, byte[] icon) {

    /** The longest title accepted. */
    public static final int MAX_TITLE = 200;

    public JumpItem {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(description, "description");
        if (title.isBlank() || title.length() > MAX_TITLE || title.indexOf('\n') >= 0 || title.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("a title must be 1 to " + MAX_TITLE + " characters on one line");
        }
        arguments = List.copyOf(arguments);
        icon = icon == null ? null : icon.clone();
    }

    /** An entry that starts the program with {@code arguments}. */
    public static JumpItem of(String title, String... arguments) {
        return new JumpItem(title, List.of(arguments), "", null);
    }

    public JumpItem withDescription(String description) {
        return new JumpItem(title, arguments, description, icon);
    }

    public JumpItem withIcon(byte[] icon) {
        return new JumpItem(title, arguments, description, icon);
    }

    @Override
    public byte[] icon() {
        return icon == null ? null : icon.clone();
    }
}
