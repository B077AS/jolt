package io.github.b077as.jolt;

/**
 * Decides what the right-click menu of a window shows. Install it with {@link WebWindow#setContextMenu}.
 *
 * <pre>{@code
 * window.setContextMenu(info -> {
 *     if (info.isLink()) {
 *         return Menu.of(Menu.action("Open link", () -> app.openExternal(URI.create(info.linkUrl()))),
 *                        Menu.action("Copy link", () -> app.clipboard().writeText(info.linkUrl())));
 *     }
 *     if (info.editable()) {
 *         return Menu.of(Menu.action("Cut", () -> window.edit(EditCommand.CUT)),
 *                        Menu.action("Copy", () -> window.edit(EditCommand.COPY)),
 *                        Menu.action("Paste", () -> window.edit(EditCommand.PASTE)));
 *     }
 *     return null; // no menu here
 * });
 * }</pre>
 */
@FunctionalInterface
public interface ContextMenuProvider {

    /**
     * The menu for this click, or {@code null} (or an empty menu) for none. Called on the UI thread, once per right click; keep it quick.
     * Whatever it returns is shown instead of the web view's own menu.
     */
    Menu menuFor(ContextMenuInfo info);
}
