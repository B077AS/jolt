package io.github.b077as.jolt;

/**
 * What the user right-clicked, for a {@link ContextMenuProvider}. The web view works this out itself from the click, so a page cannot
 * lie about it. Text and addresses are what the page contains: treat them as untrusted input.
 *
 * @param x             where the click was, in logical (DPI-independent) pixels from the left edge of the page area
 * @param y             where the click was, in logical pixels from the top of the page area
 * @param pageUrl       the address of the document, or {@code ""}
 * @param linkUrl       the address of the link under the pointer, or {@code ""} if it is not on a link
 * @param sourceUrl     the address of the image, audio or video under the pointer, or {@code ""}
 * @param editable      whether the pointer is on a text field or other editable area
 * @param hasSelection  whether some text is selected
 * @param selectedText  the selected text, shortened to {@value #MAX_SELECTED_TEXT} characters; {@code ""} where the platform does not
 *                      tell (Linux: use {@link #hasSelection})
 */
public record ContextMenuInfo(int x, int y, String pageUrl, String linkUrl, String sourceUrl, boolean editable, boolean hasSelection,
                              String selectedText) {

    /** Longest {@link #selectedText} given. */
    public static final int MAX_SELECTED_TEXT = 4096;

    public ContextMenuInfo {
        pageUrl = pageUrl == null ? "" : pageUrl;
        linkUrl = linkUrl == null ? "" : linkUrl;
        sourceUrl = sourceUrl == null ? "" : sourceUrl;
        selectedText = selectedText == null ? "" : selectedText.length() > MAX_SELECTED_TEXT ? selectedText.substring(0, MAX_SELECTED_TEXT) : selectedText;
    }

    /** Whether the pointer is on a link. */
    public boolean isLink() {
        return !linkUrl.isEmpty();
    }

    /** Whether the pointer is on an image, audio or video. */
    public boolean isMedia() {
        return !sourceUrl.isEmpty();
    }
}
