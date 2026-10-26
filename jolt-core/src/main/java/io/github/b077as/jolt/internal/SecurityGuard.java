package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.BlockedEvent;
import io.github.b077as.jolt.SecurityPolicy;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A window's current {@link SecurityPolicy} and the decisions taken from it. Shared by the backends so both apply the
 * same rules; the backends only gather the URLs and enforce the answers.
 */
public final class SecurityGuard {

    private static final System.Logger LOG = System.getLogger("io.github.b077as.jolt.security");

    private volatile SecurityPolicy policy;
    private final Consumer<Runnable> poster;
    private final CopyOnWriteArrayList<Consumer<BlockedEvent>> listeners = new CopyOnWriteArrayList<>();

    public SecurityGuard(SecurityPolicy policy) {
        this(policy, Runnable::run);
    }

    /**
     * @param poster runs the listener notifications. Backends pass the UI thread queue, so a listener never runs inside the
     *               native event handler that detected the problem; calling back into the web view from there (to navigate,
     *               say) is not safe.
     */
    public SecurityGuard(SecurityPolicy policy, Consumer<Runnable> poster) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    public SecurityPolicy policy() {
        return policy;
    }

    public void set(SecurityPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public void onBlocked(Consumer<BlockedEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Whether the window may navigate to {@code url}. {@code about:} pages ({@code about:blank}, {@code about:srcdoc}) always may. */
    public boolean allowNavigation(String url) {
        SecurityPolicy p = policy;
        if (!p.lockNavigation() || isAbout(url) || p.trusts(url)) {
            return true;
        }
        blocked(BlockedEvent.Kind.NAVIGATION, url, "Navigation to " + url + " blocked: its origin is not trusted. Add it with "
                + "SecurityPolicy.withTrustedOrigins(...) or turn off lockNavigation.");
        return false;
    }

    /** Whether a page may open a new window to {@code url}. */
    public boolean allowNewWindow(String url) {
        if (!policy.blockNewWindows()) {
            return true;
        }
        blocked(BlockedEvent.Kind.NEW_WINDOW, url, "New window to " + url + " blocked by the SecurityPolicy");
        return false;
    }

    /** Whether the page at {@code url} may be granted a permission it asked for. */
    public boolean allowPermission(String url) {
        if (!policy.denyPermissions()) {
            return true;
        }
        blocked(BlockedEvent.Kind.PERMISSION, url, "Permission request from " + url + " denied by the SecurityPolicy");
        return false;
    }

    /** Whether a message sent from the page at {@code url} may reach the application's listeners. */
    public boolean allowMessageFrom(String url) {
        SecurityPolicy p = policy;
        if (!p.restrictMessages() || p.trusts(url)) {
            return true;
        }
        blocked(BlockedEvent.Kind.MESSAGE, url, "Message from " + url + " ignored: its origin is not trusted. Add it with "
                + "SecurityPolicy.withTrustedOrigins(...) or turn off restrictMessages.");
        return false;
    }

    private static boolean isAbout(String url) {
        return url != null && url.regionMatches(true, 0, "about:", 0, 6);
    }

    private void blocked(BlockedEvent.Kind kind, String url, String description) {
        LOG.log(System.Logger.Level.WARNING, description);
        BlockedEvent event = new BlockedEvent(kind, url);
        poster.accept(() -> {
            for (Consumer<BlockedEvent> listener : listeners) {
                try {
                    listener.accept(event);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "onBlocked listener failed", e);
                }
            }
        });
    }
}
