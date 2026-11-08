package io.github.b077as.jolt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The extra entries of the menu that opens when the user right-clicks the application's taskbar or launcher icon (the Windows
 * <em>jump list</em>; the freedesktop <em>desktop actions</em> on Linux), see {@link Application#setJumpList}.
 *
 * <ul>
 *   <li><b>tasks</b>: fixed actions ("New window", "New incognito window"). Windows and Linux;</li>
 *   <li><b>categories</b>: your own named groups ("Pinned chats", "Recent projects") whose entries you can change at any time.
 *       Windows only; Linux has no such thing and leaves them out.</li>
 * </ul>
 *
 * Every entry starts the program with its arguments; see {@link JumpItem}. Immutable; build one with {@link #builder()}.
 *
 * @param tasks       the tasks, in order
 * @param categories  the categories, in order
 * @param command     the command that starts the program (program first, then its own arguments such as {@code -jar app.jar}),
 *                    or empty to use the command this process was started with
 */
public record JumpList(List<JumpItem> tasks, List<Category> categories, List<String> command) {

    /** Most tasks a list may hold, and most entries over all categories (Windows shows fewer when space is short). */
    public static final int MAX_ITEMS = 60;

    /** A named group of entries. */
    public record Category(String name, List<JumpItem> items) {
        public Category {
            Objects.requireNonNull(name, "name");
            if (name.isBlank() || name.length() > JumpItem.MAX_TITLE) {
                throw new IllegalArgumentException("a category name must be 1 to " + JumpItem.MAX_TITLE + " characters");
            }
            items = List.copyOf(items);
        }
    }

    public JumpList {
        tasks = List.copyOf(tasks);
        categories = List.copyOf(categories);
        command = List.copyOf(command);
        if (tasks.size() > MAX_ITEMS || categories.stream().mapToInt(c -> c.items().size()).sum() > MAX_ITEMS) {
            throw new IllegalArgumentException("too many jump list entries (at most " + MAX_ITEMS + " tasks and " + MAX_ITEMS
                    + " category entries)");
        }
        if (!command.isEmpty() && command.get(0).isBlank()) {
            throw new IllegalArgumentException("the command must start with the program to run");
        }
    }

    /** A list with nothing in it: clears the extra entries. */
    public static JumpList empty() {
        return new JumpList(List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return tasks.isEmpty() && categories.stream().allMatch(c -> c.items().isEmpty());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<JumpItem> tasks = new ArrayList<>();
        private final List<Category> categories = new ArrayList<>();
        private List<String> command = List.of();

        private Builder() {}

        public Builder task(JumpItem item) {
            tasks.add(Objects.requireNonNull(item, "item"));
            return this;
        }

        /** A task that starts the program with {@code arguments}. */
        public Builder task(String title, String... arguments) {
            return task(JumpItem.of(title, arguments));
        }

        /** A category (Windows only). */
        public Builder category(String name, List<JumpItem> items) {
            categories.add(new Category(name, items));
            return this;
        }

        /** Overrides the command that starts the program, for example when it is started by a launcher script. */
        public Builder command(List<String> command) {
            this.command = List.copyOf(command);
            return this;
        }

        public JumpList build() {
            return new JumpList(tasks, categories, command);
        }
    }
}
