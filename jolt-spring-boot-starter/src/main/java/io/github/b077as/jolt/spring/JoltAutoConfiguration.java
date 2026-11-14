package io.github.b077as.jolt.spring;

import io.github.b077as.jolt.JsonCodec;
import io.github.b077as.jolt.Rpc;
import io.github.b077as.jolt.RpcOptions;
import io.github.b077as.jolt.spring.internal.JsonCodecs;
import io.github.b077as.jolt.spring.internal.LoopbackGuard;
import io.github.b077as.jolt.spring.internal.RpcMethodRegistrar;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * What the starter adds to every application that has it on the class path: the {@link Rpc} that answers {@code jolt.call},
 * with the application's own JSON library, and the registration of {@link JoltRpc} methods. In web mode (a servlet application
 * started with {@link JoltSpringApplication}) also the {@link LoopbackGuard} in front of every request.
 *
 * <p>Each bean backs off when the application defines its own.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
        "org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration",
        "org.springframework.boot.gson.autoconfigure.GsonAutoConfiguration"})
@EnableConfigurationProperties(JoltProperties.class)
public class JoltAutoConfiguration {

    /** The JSON library for RPC: the application's Jackson 3, Jackson 2 or Gson bean, else {@link JsonCodec#basic()}. */
    @Bean
    @ConditionalOnMissingBean
    public JsonCodec joltJsonCodec(BeanFactory beans) {
        return JsonCodecs.pick(beans, getClass().getClassLoader());
    }

    @Bean
    @ConditionalOnMissingBean
    public RpcOptions joltRpcOptions(JsonCodec codec, JoltProperties properties) {
        JoltProperties.Rpc rpc = properties.getRpc();
        return RpcOptions.defaults()
                .withCodec(codec)
                .withExposeErrorMessages(rpc.isExposeErrorMessages())
                .withMaxConcurrentCalls(rpc.getMaxConcurrentCalls())
                .withMaxMessageChars(rpc.getMaxMessageChars());
    }

    @Bean
    @ConditionalOnMissingBean
    public Rpc joltRpc(RpcOptions options) {
        return Rpc.create(options);
    }

    /** Static, as a bean post-processor must be, so that it does not pull this configuration in early. */
    @Bean
    public static RpcMethodRegistrar joltRpcMethodRegistrar() {
        return new RpcMethodRegistrar();
    }

    /** Web mode: only the window may use the server. Present only when the application was started by {@link JoltSpringApplication}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    @ConditionalOnBean(JoltRuntime.class)
    static class WebGuard {

        @Bean
        LoopbackGuard joltLoopbackGuard(JoltProperties properties) {
            return new LoopbackGuard(properties.getWeb().getStartPath());
        }

        @Bean
        FilterRegistrationBean<LoopbackGuard> joltLoopbackGuardRegistration(LoopbackGuard guard) {
            FilterRegistrationBean<LoopbackGuard> registration = new FilterRegistrationBean<>(guard);
            registration.setName("joltLoopbackGuard");
            registration.addUrlPatterns("/*");
            registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
