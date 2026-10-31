package io.github.b077as.jolt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A system notification (a toast on Windows, a desktop notification on Linux) for {@link Application#notify}.
 *
 * <p>Every action below runs on the UI thread and only while the application is running; it is not called when the
 * notification is dismissed or expires, or is used after the application has ended.
 *
 * @param title   the first line, shown in bold; must not be blank, at most {@value #MAX_TITLE} characters
 * @param body    the text below it; may be empty, at most {@value #MAX_BODY} characters
 * @param onClick runs when the user clicks the notification itself; {@code null} for none
 * @param buttons up to {@value #MAX_BUTTONS} buttons, in order. Windows and Linux (a desktop may show fewer or none: some only
 *                list them when the notification is expanded)
 * @param reply   a text field with a send button; {@code null} for none. <b>Windows only</b>: the Linux notification
 *                specification has no text input, so there it is left out
 */
public record Notification(String title, String body, Runnable onClick, List<Button> buttons, Reply reply) {

    public static final int MAX_TITLE = 256;
    public static final int MAX_BODY = 1024;
    public static final int MAX_BUTTONS = 5;
    public static final int MAX_LABEL = 64;

    /** A button under the text. */
    public record Button(String label, Runnable action) {
        public Button {
            checkLabel(label);
            Objects.requireNonNull(action, "action");
        }
    }

    /**
     * A text field and the button that sends it.
     *
     * @param label       the button's text, for example {@code "Send"}
     * @param placeholder grey hint text inside the field; may be empty
     * @param onReply     receives what the user typed
     */
    public record Reply(String label, String placeholder, Consumer<String> onReply) {
        public Reply {
            checkLabel(label);
            placeholder = placeholder == null ? "" : placeholder;
            if (placeholder.length() > MAX_LABEL * 4) {
                throw new IllegalArgumentException("placeholder is longer than " + MAX_LABEL * 4 + " characters");
            }
            Objects.requireNonNull(onReply, "onReply");
        }
    }

    public Notification {
        Objects.requireNonNull(title, "title");
        body = body == null ? "" : body;
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (title.length() > MAX_TITLE) {
            throw new IllegalArgumentException("title is longer than " + MAX_TITLE + " characters");
        }
        if (body.length() > MAX_BODY) {
            throw new IllegalArgumentException("body is longer than " + MAX_BODY + " characters");
        }
        if (buttons.size() > MAX_BUTTONS) {
            throw new IllegalArgumentException("more than " + MAX_BUTTONS + " buttons");
        }
    }

    public Notification(String title, String body) {
        this(title, body, null, List.of(), null);
    }

    public Notification(String title, String body, Runnable onClick) {
        this(title, body, onClick, List.of(), null);
    }

    public static Notification of(String title, String body) {
        return new Notification(title, body);
    }

    public static Notification of(String title) {
        return new Notification(title, "");
    }

    /** The same notification, running {@code action} when the user clicks it. */
    public Notification withOnClick(Runnable action) {
        return new Notification(title, body, action, buttons, reply);
    }

    /** The same notification with one more button. */
    public Notification withButton(String label, Runnable action) {
        List<Button> all = new ArrayList<>(buttons);
        all.add(new Button(label, action));
        return new Notification(title, body, onClick, all, reply);
    }

    /** The same notification with a text field; see {@link Reply}. Windows only. */
    public Notification withReply(String label, String placeholder, Consumer<String> onReply) {
        return new Notification(title, body, onClick, buttons, new Reply(label, placeholder, onReply));
    }

    private static void checkLabel(String label) {
        Objects.requireNonNull(label, "label");
        if (label.isBlank() || label.length() > MAX_LABEL) {
            throw new IllegalArgumentException("a button label must be 1 to " + MAX_LABEL + " characters");
        }
    }
}
