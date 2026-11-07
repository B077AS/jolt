package io.github.b077as.jolt;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Making the operating system open this application for links of a scheme you choose, such as {@code myapp://invite/ABC}, so a
 * link in a browser, a chat or an e-mail starts the application. Get it from {@link Application#urlSchemes()}.
 *
 * <p>Windows: keys under {@code HKCU\Software\Classes\<scheme>}. Linux: a {@code .desktop} file in
 * {@code ~/.local/share/applications} that handles {@code x-scheme-handler/<scheme>}, made the default with {@code xdg-mime}. Both belong
 * to the user and need no administrator rights.
 *
 * <p>The link reaches the application as a command-line argument: the first launch finds it in {@code main}'s arguments, and when a
 * copy is already running, {@link SingleInstance} hands the arguments of the new launch to it. {@link #findUrl} picks the link out.
 * <b>Treat the link as untrusted input</b>: any web page can open one.
 *
 * <pre>{@code
 * app.urlSchemes().register("myapp", "My App", Autostart.currentCommand(List.of()));
 * // ... later, from main(args) or from the SingleInstance callback:
 * UrlSchemes.findUrl(args, "myapp").ifPresent(url -> openInvite(url.getHost(), url.getPath()));
 * }</pre>
 *
 * <p>Register on every start or at install time; the entry holds the program's path, so register again if the program moves. Do not
 * register from a development run (classes folder, IDE): the entry would point at a command that only works there.
 */
public interface UrlSchemes {

    /** Web, mail and file schemes: taking one over would break links for the user's browser or mail program, so they are refused. */
    List<String> RESERVED = List.of("http", "https", "file", "ftp", "mailto", "data", "blob", "about", "javascript");

    /** Whether an entry exists for {@code scheme} (made by this or any copy of the application; the command is not compared). */
    boolean isRegistered(String scheme);

    /**
     * Creates or replaces the entry so that links of {@code scheme} start {@code command} with the link appended as the last argument.
     *
     * @param scheme      lower case letters, digits, {@code +}, {@code -} and {@code .}, starting with a letter, such as {@code "myapp"}
     *                    (without {@code ://}); web, mail and file schemes ({@link #RESERVED}) are refused
     * @param displayName what the system calls the handler, for example in "Open My App?" prompts
     * @param command     the program followed by its arguments, for example {@code List.of("C:\Apps\My\My.exe")}; see
     *                    {@link Autostart#currentCommand} for a program started with {@code java -jar}
     * @throws IllegalArgumentException if the scheme is not valid or is reserved, or the command is empty
     */
    void register(String scheme, String displayName, List<String> command);

    /** Removes the entry; does nothing if there is none. */
    void unregister(String scheme);

    /**
     * The first argument that is a link of {@code scheme} ({@code myapp:...}, case-insensitive), or empty. Pass this launch's arguments,
     * or those a later launch handed to the running copy.
     */
    static Optional<URI> findUrl(List<String> arguments, String scheme) {
        String prefix = scheme.toLowerCase(Locale.ROOT) + ":";
        for (String argument : arguments) {
            if (argument != null && argument.regionMatches(true, 0, prefix, 0, prefix.length())) {
                try {
                    return Optional.of(new URI(argument));
                } catch (java.net.URISyntaxException e) {
                    // not a usable link: keep looking
                }
            }
        }
        return Optional.empty();
    }

    /** Checks a scheme name and returns it. For backends. */
    static String validate(String scheme) {
        if (scheme == null || !scheme.matches("[a-z][a-z0-9+.-]{0,63}")) {
            throw new IllegalArgumentException("not a valid URL scheme (lower case letters, digits, + - . starting with a letter): " + scheme);
        }
        if (RESERVED.contains(scheme)) {
            throw new IllegalArgumentException("the scheme \"" + scheme + "\" belongs to the system or to another program");
        }
        return scheme;
    }
}
