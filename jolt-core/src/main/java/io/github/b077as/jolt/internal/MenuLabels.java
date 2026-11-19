package io.github.b077as.jolt.internal;

/**
 * Converts menu labels, written with {@code &} marking the mnemonic letter and {@code &&} for a real ampersand (see
 * {@link io.github.b077as.jolt.Menu}), into what each toolkit wants.
 */
public final class MenuLabels {

    private MenuLabels() {}

    /** The label as the user reads it, without mnemonic markers: {@code "&Save && close"} is {@code "Save & close"}. */
    public static String plain(String label) {
        StringBuilder text = new StringBuilder(label.length());
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c == '&') {
                if (i + 1 < label.length() && label.charAt(i + 1) == '&') {
                    text.append('&');
                    i++;
                }
                // a single & only marks the next letter
            } else {
                text.append(c);
            }
        }
        return text.toString();
    }

    /**
     * The label for a Win32 menu, which uses the same markers. A lone {@code &} at the very end is dropped (Windows would show it, but it
     * marks nothing), and a tab or line break becomes a space because a tab separates the accelerator text there.
     */
    public static String forWin32(String label) {
        String text = label.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        return endsWithOddAmpersands(text) ? text.substring(0, text.length() - 1) : text;
    }

    private static boolean endsWithOddAmpersands(String text) {
        int n = 0;
        for (int i = text.length() - 1; i >= 0 && text.charAt(i) == '&'; i--) {
            n++;
        }
        return n % 2 == 1;
    }

    /** The label for a GTK menu item with a mnemonic ({@code gtk_menu_item_new_with_mnemonic}): {@code _} marks the letter. */
    public static String forGtk(String label) {
        StringBuilder text = new StringBuilder(label.length() + 2);
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c == '_') {
                text.append("__");
            } else if (c == '&') {
                if (i + 1 < label.length() && label.charAt(i + 1) == '&') {
                    text.append('&');
                    i++;
                } else if (i + 1 < label.length()) {
                    text.append('_');
                }
            } else {
                text.append(c);
            }
        }
        return text.toString();
    }
}
