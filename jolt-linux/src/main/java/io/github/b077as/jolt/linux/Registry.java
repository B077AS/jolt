package io.github.b077as.jolt.linux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Maps the integer ids handed to native code as {@code user_data} back to Java objects.
 *
 * <p>Native callbacks are process-wide stubs, but the objects they act on come and go. Looking targets up by id
 * means a callback that arrives late, for example one GLib still had queued when its application was closed, finds
 * nothing and is ignored instead of touching freed state.
 */
final class Registry {

    private static final AtomicLong IDS = new AtomicLong(1);
    private static final Map<Long, Object> OBJECTS = new ConcurrentHashMap<>();

    private Registry() {}

    static long add(Object object) {
        long id = IDS.getAndIncrement();
        OBJECTS.put(id, object);
        return id;
    }

    /** The registered object, or {@code null} if it is gone or of another type. */
    static <T> T get(long id, Class<T> type) {
        Object object = OBJECTS.get(id);
        return type.isInstance(object) ? type.cast(object) : null;
    }

    /** Like {@link #get} but also unregisters the id. */
    static <T> T take(long id, Class<T> type) {
        Object object = OBJECTS.remove(id);
        return type.isInstance(object) ? type.cast(object) : null;
    }

    static void remove(long id) {
        OBJECTS.remove(id);
    }
}
