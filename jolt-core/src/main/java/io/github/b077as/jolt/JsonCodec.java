package io.github.b077as.jolt;

import java.lang.reflect.Type;
import java.util.Objects;

/**
 * Turns Java values into JSON text and back, for {@link Rpc}. Jolt has no JSON library of its own to impose: {@link #basic()} needs
 * none and handles records, enums, collections and maps, and any library plugs in with {@link #of}:
 *
 * <pre>{@code
 * Gson gson = new Gson();
 * JsonCodec codec = JsonCodec.of(gson::toJson, gson::fromJson);
 *
 * ObjectMapper mapper = new ObjectMapper();
 * JsonCodec codec = JsonCodec.of(mapper::writeValueAsString, (json, type) -> mapper.readValue(json, mapper.constructType(type)));
 * }</pre>
 * A codec is used from many threads at once, so it must be thread-safe (Gson and Jackson's {@code ObjectMapper} are).
 *
 * <p>The JSON it decodes comes from the page: a codec must fail with an exception on bad input and never run code named by it
 * (do not turn on polymorphic typing from type names in the JSON).
 */
public interface JsonCodec {

    /** The JSON text for {@code value} ({@code null} becomes {@code null}). */
    String encode(Object value) throws Exception;

    /** The value of type {@code type} that {@code json} describes. Throws when {@code json} cannot be read as that type. */
    Object decode(String json, Type type) throws Exception;

    /**
     * The built-in codec, with no dependency. It writes {@code null}, strings, numbers, booleans, characters, enums (by name), records
     * (by component), {@link java.util.Map}s (keys as text), {@link Iterable}s, arrays, {@link java.util.Optional} and anything whose
     * {@code toString} is its natural text ({@link java.util.UUID}, {@link java.net.URI}, {@link java.time} values). It reads the same
     * kinds back, including generic records and collections such as {@code List<Item>}; a member missing from the JSON leaves
     * {@code null} (or zero, or {@code false}) and members the type does not have are ignored. For {@link Object} it gives
     * {@code Map}, {@code List}, {@code String}, {@code Boolean}, {@code Long} or {@code Double}, {@code BigDecimal} for other
     * numbers, or {@code null}. Other classes (JavaBeans, classes with private fields) are refused: use records, or plug in a library.
     */
    static JsonCodec basic() {
        return io.github.b077as.jolt.internal.BasicJson.CODEC;
    }

    /** Writes JSON for a value. */
    @FunctionalInterface
    interface Encoder {
        String encode(Object value) throws Exception;
    }

    /** Reads JSON as a value of a type. */
    @FunctionalInterface
    interface Decoder {
        Object decode(String json, Type type) throws Exception;
    }

    /** A codec made of two functions, typically method references to a JSON library (see the class comment). */
    static JsonCodec of(Encoder encoder, Decoder decoder) {
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(decoder, "decoder");
        return new JsonCodec() {
            @Override
            public String encode(Object value) throws Exception {
                return encoder.encode(value);
            }

            @Override
            public Object decode(String json, Type type) throws Exception {
                return decoder.decode(json, type);
            }

            @Override
            public String toString() {
                return "JsonCodec.of(" + encoder + ", " + decoder + ")";
            }
        };
    }
}
