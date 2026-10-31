package io.github.b077as.jolt;

/**
 * An icon in the system tray (Windows notification area, Linux status notifier). Create it with
 * {@link Application#createTray}. All methods are safe from any thread; callbacks run on the UI thread.
 */
public interface Tray extends AutoCloseable {

    /** Replaces the icon: PNG data, or the bytes of a Windows {@code .ico} (several sizes look best). Bad data is logged and ignored. */
    void setIcon(byte[] icon);

    /** Sets the text shown when the pointer rests on the icon ({@code null}: none). Use it for state such as "3 unread". */
    void setTooltip(String tooltip);

    /**
     * Sets the menu shown when the user right-clicks the icon (on Linux, the one the desktop shows for it). It replaces the
     * previous menu; to change a checkbox or enable an item, build the menu again and call this again. {@code null} removes it.
     */
    void setMenu(TrayMenu menu);

    /** Called when the user clicks the icon with the primary button (a single click on Windows, "activate" on Linux). */
    void onClick(Runnable listener);

    /** Removes the icon. */
    @Override
    void close();
}
