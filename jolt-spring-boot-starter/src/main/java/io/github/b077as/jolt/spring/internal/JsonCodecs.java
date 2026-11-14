package io.github.b077as.jolt.spring.internal;

import io.github.b077as.jolt.JsonCodec;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.util.ClassUtils;

/**
 * Picks the JSON library for RPC from what the application already has: its Jackson 3 {@code ObjectMapper} bean (Spring
 * Boot 4's default), a Jackson 2 one, a {@code Gson} bean, or else Jolt's own {@link JsonCodec#basic()}. Using the
 * application's own mapper means its settings (modules, naming, dates) apply to RPC too.
 *
 * <p>Each library is touched in its own class, loaded only once the library is known to be there.
 */
public final class JsonCodecs {

    private JsonCodecs() {
    }

    public static JsonCodec pick(BeanFactory beans, ClassLoader loader) {
        JsonCodec codec = null;
        if (ClassUtils.isPresent("tools.jackson.databind.ObjectMapper", loader)) {
            codec = Jackson3.from(beans);
        }
        if (codec == null && ClassUtils.isPresent("com.fasterxml.jackson.databind.ObjectMapper", loader)) {
            codec = Jackson2.from(beans);
        }
        if (codec == null && ClassUtils.isPresent("com.google.gson.Gson", loader)) {
            codec = GsonCodec.from(beans);
        }
        return codec != null ? codec : JsonCodec.basic();
    }

    /** Jackson 3 ({@code tools.jackson}). */
    static final class Jackson3 {
        private Jackson3() {
        }

        static JsonCodec from(BeanFactory beans) {
            tools.jackson.databind.ObjectMapper mapper = beans.getBeanProvider(tools.jackson.databind.ObjectMapper.class).getIfUnique();
            return mapper == null ? null : JsonCodec.of(mapper::writeValueAsString, (json, type) -> mapper.readValue(json, mapper.constructType(type)));
        }
    }

    /** Jackson 2 ({@code com.fasterxml.jackson}). */
    static final class Jackson2 {
        private Jackson2() {
        }

        static JsonCodec from(BeanFactory beans) {
            com.fasterxml.jackson.databind.ObjectMapper mapper = beans.getBeanProvider(com.fasterxml.jackson.databind.ObjectMapper.class).getIfUnique();
            return mapper == null ? null : JsonCodec.of(mapper::writeValueAsString, (json, type) -> mapper.readValue(json, mapper.constructType(type)));
        }
    }

    /** Gson. */
    static final class GsonCodec {
        private GsonCodec() {
        }

        static JsonCodec from(BeanFactory beans) {
            com.google.gson.Gson gson = beans.getBeanProvider(com.google.gson.Gson.class).getIfUnique();
            return gson == null ? null : JsonCodec.of(gson::toJson, gson::fromJson);
        }
    }
}
