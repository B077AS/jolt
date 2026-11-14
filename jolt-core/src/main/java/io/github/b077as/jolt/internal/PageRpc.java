package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.RpcException;
import io.github.b077as.jolt.WebWindow;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Routes a window's RPC messages (see {@link RpcProtocol}) to the {@code Rpc} attached to it. The backends call
 * {@link #deliver} for every message that {@link RpcProtocol#isRpcMessage starts with the prefix}, after the security policy's message
 * check and instead of the application's own listeners.
 */
public final class PageRpc {

    private PageRpc() {}

    /** The Java half for one window. Called on the UI thread; must return quickly. */
    public interface Receiver {
        void receive(String message, String sourceUrl);
    }

    private static final Map<WebWindow, Receiver> RECEIVERS = new IdentityHashMap<>();

    /** @throws IllegalStateException if another receiver is registered for {@code window} */
    public static void register(WebWindow window, Receiver receiver) {
        synchronized (RECEIVERS) {
            Receiver existing = RECEIVERS.putIfAbsent(window, receiver);
            if (existing != null && existing != receiver) {
                throw new IllegalStateException("this window is already attached to another Rpc; detach it first");
            }
        }
    }

    public static void unregister(WebWindow window, Receiver receiver) {
        synchronized (RECEIVERS) {
            RECEIVERS.remove(window, receiver);
        }
    }

    public static boolean isRpcMessage(String message) {
        return RpcProtocol.isRpcMessage(message);
    }

    /**
     * Hands {@code message} from the page at {@code sourceUrl} to the window's {@code Rpc}. With none attached, a call is answered with
     * {@link RpcException#UNAVAILABLE} so the page does not wait forever.
     */
    public static void deliver(WebWindow window, String message, String sourceUrl) {
        Receiver receiver;
        synchronized (RECEIVERS) {
            receiver = RECEIVERS.get(window);
        }
        if (receiver != null) {
            receiver.receive(message, sourceUrl);
            return;
        }
        if (message.length() > 1024 * 1024 || !window.security().trusts(sourceUrl)) {
            return;
        }
        RpcProtocol.Request request = RpcProtocol.parse(message);
        if (request != null && request.type().equals("call")) {
            String answer = RpcProtocol.error(request.session(), request.id(), RpcException.UNAVAILABLE,
                    "No Rpc is attached to this window", null);
            window.eval(RpcProtocol.deliverScript(answer)).exceptionally(t -> null);
        }
    }
}
