package io.github.b077as.jolt.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exception handlers by exception type, where the most specific type wins: the one rule behind {@code Rpc.onException} and
 * {@code Router.onException}, kept in one place so the two cannot drift apart.
 *
 * @param <H> what a handler is
 */
public final class TypeRouted<H> {

    private final Map<Class<? extends Throwable>, H> handlers = new ConcurrentHashMap<>();

    /** Registers {@code handler} for {@code type} and its subclasses, replacing the handler for exactly that type. */
    public void put(Class<? extends Throwable> type, H handler) {
        handlers.put(type, handler);
    }

    /** The handler of the most specific superclass of {@code error} (its own class included) that has one, or {@code null}. */
    public H find(Throwable error) {
        if (handlers.isEmpty()) {
            return null;
        }
        for (Class<?> type = error.getClass(); type != null; type = type.getSuperclass()) {
            H handler = handlers.get(type);
            if (handler != null) {
                return handler;
            }
        }
        return null;
    }
}
