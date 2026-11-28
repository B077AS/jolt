package io.github.b077as.jolt;

/**
 * A page wants to download a file. Passed to the handler set with {@link WebWindow#onDownload}.
 *
 * <p>Everything here comes from the page or the server, so treat it as untrusted.
 *
 * @param url        where the file comes from; may be a {@code blob:} or {@code data:} address
 * @param fileName   the suggested name, already reduced to a plain file name that is safe to put in a folder: no path
 *                   separators, control characters, leading dots or names the system reserves. Never empty. It may still be
 *                   misleading (an executable, say), and it may name a file that exists
 * @param mimeType   the declared type, or an empty string
 * @param totalBytes the declared size, or -1 if unknown
 */
public record DownloadRequest(String url, String fileName, String mimeType, long totalBytes) {

    public DownloadRequest {
        url = url == null ? "" : url;
        fileName = io.github.b077as.jolt.internal.FileNames.safe(fileName);
        mimeType = mimeType == null ? "" : mimeType;
        totalBytes = totalBytes < 0 ? -1 : totalBytes;
    }
}
