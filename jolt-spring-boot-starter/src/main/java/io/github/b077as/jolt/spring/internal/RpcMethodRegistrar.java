package io.github.b077as.jolt.spring.internal;

import io.github.b077as.jolt.JoltRpc;
import io.github.b077as.jolt.JoltRpcExceptionHandler;
import io.github.b077as.jolt.Rpc;
import io.github.b077as.jolt.RpcExceptionHandler;
import io.github.b077as.jolt.RpcInterceptor;
import io.github.b077as.jolt.spring.JoltProperties;
import io.github.b077as.jolt.internal.RpcControllers;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.ResolvableType;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds {@link JoltRpc} and {@link JoltRpcExceptionHandler} methods on beans as they are created (seeing through proxies and composed
 * annotations, which Spring knows how to do) and, once every singleton exists, registers them with the application's {@link Rpc}. What a
 * method may take, and the checks of its shape, are {@link RpcControllers}', the same as for {@code Rpc.register}. Registering at the end
 * (not while beans are being created) keeps the {@code Rpc} bean, and the JSON mapper it uses, out of the bean post-processing phase.
 */
public final class RpcMethodRegistrar implements BeanPostProcessor, SmartInitializingSingleton, BeanFactoryAware {

    /** Names under this prefix belong to Jolt (such as {@code jolt.window.show}). */
    public static final String RESERVED_PREFIX = RpcControllers.RESERVED_PREFIX;

    private record Found(String name, Object bean, String beanName, Method method) {
    }

    private record FoundHandler(Object bean, String beanName, Method method) {
    }

    private final List<Found> found = new ArrayList<>();
    private final List<FoundHandler> foundHandlers = new ArrayList<>();
    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> type = AopUtils.getTargetClass(bean);
        if (type.getName().startsWith("java.") || type.getName().startsWith("org.springframework.")) {
            return bean;
        }
        Map<Method, JoltRpc> methods = MethodIntrospector.selectMethods(type,
                (MethodIntrospector.MetadataLookup<JoltRpc>) method -> AnnotatedElementUtils.findMergedAnnotation(method, JoltRpc.class));
        Map<Method, JoltRpcExceptionHandler> handlers = MethodIntrospector.selectMethods(type,
                (MethodIntrospector.MetadataLookup<JoltRpcExceptionHandler>) method ->
                        AnnotatedElementUtils.findMergedAnnotation(method, JoltRpcExceptionHandler.class));
        if (methods.isEmpty() && handlers.isEmpty()) {
            return bean;
        }
        synchronized (foundHandlers) {
            for (Method method : handlers.keySet()) {
                foundHandlers.add(new FoundHandler(bean, beanName, method));
            }
        }
        JoltRpc onClass = AnnotatedElementUtils.findMergedAnnotation(type, JoltRpc.class);
        String prefix = onClass == null ? "" : onClass.value().strip();
        synchronized (found) {
            for (Map.Entry<Method, JoltRpc> entry : methods.entrySet()) {
                Method method = entry.getKey();
                String name = RpcControllers.nameOf(entry.getValue().value().strip(), method);
                String full = prefix.isEmpty() ? name : prefix + "." + name;
                RpcControllers.checkMethod(full, method, describe(beanName, method));
                found.add(new Found(full, bean, beanName, method));
            }
        }
        return bean;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<Found> all;
        synchronized (found) {
            all = List.copyOf(found);
            found.clear();
        }
        List<FoundHandler> exceptionHandlers;
        synchronized (foundHandlers) {
            exceptionHandlers = List.copyOf(foundHandlers);
            foundHandlers.clear();
        }
        Map<String, Found> byName = new LinkedHashMap<>();
        for (Found method : all) {
            Found earlier = byName.putIfAbsent(method.name(), method);
            if (earlier != null) {
                throw new IllegalStateException("Two @JoltRpc methods are called '" + method.name() + "': "
                        + describe(earlier.beanName(), earlier.method()) + " and " + describe(method.beanName(), method.method()));
            }
        }
        Map<Class<?>, FoundHandler> byType = new LinkedHashMap<>();
        for (FoundHandler handler : exceptionHandlers) {
            Class<?> type = RpcControllers.exceptionType(handler.method(), describe(handler.beanName(), handler.method()));
            FoundHandler earlier = byType.putIfAbsent(type, handler);
            if (earlier != null) {
                throw new IllegalStateException("Two @JoltRpcExceptionHandler methods handle " + type.getName() + ": "
                        + describe(earlier.beanName(), earlier.method()) + " and " + describe(handler.beanName(), handler.method()));
            }
        }
        Rpc rpc = beanFactory.getBean(Rpc.class);
        beanFactory.getBeanProvider(RpcInterceptor.class).orderedStream().forEach(rpc::around);
        for (Found method : byName.values()) {
            String where = describe(method.beanName(), method.method());
            rpc.on(method.name(), RpcControllers.paramsType(method.method()), RpcControllers.resultType(method.method()),
                    RpcControllers.handler(method.bean(), method.method(), invocable(method.method(), method.bean()), where));
        }
        for (Map.Entry<Class<?>, FoundHandler> entry : byType.entrySet()) {
            FoundHandler handler = entry.getValue();
            String where = describe(handler.beanName(), handler.method());
            @SuppressWarnings("unchecked")
            Class<Throwable> type = (Class<Throwable>) entry.getKey();
            rpc.onException(type, RpcControllers.exceptionHandler(handler.bean(), handler.method(), invocable(handler.method(), handler.bean()), where));
        }
        registerExceptionHandlerBeans(rpc, byType.keySet());
        String typeScript = beanFactory.getBean(JoltProperties.class).getRpc().getTypescript();
        if (typeScript != null && !typeScript.isBlank()) {
            rpc.writeTypeScript(Path.of(typeScript.strip()));
        }
    }

    /**
     * Beans that are an {@link RpcExceptionHandler} (a {@code @Bean} method returning {@code RpcExceptionHandler<SQLException>}, or a
     * class implementing it) handle the exception type they declare, as {@code @JoltRpcExceptionHandler} methods do.
     */
    @SuppressWarnings("unchecked")
    private void registerExceptionHandlerBeans(Rpc rpc, java.util.Set<Class<?>> annotated) {
        if (!(beanFactory instanceof ConfigurableListableBeanFactory factory)) {
            return;
        }
        java.util.Set<Class<?>> seen = new java.util.HashSet<>(annotated);
        for (String name : factory.getBeanNamesForType(RpcExceptionHandler.class)) {
            Class<?> type = ResolvableType.forType(factory.getMergedBeanDefinition(name).getResolvableType().getType())
                    .as(RpcExceptionHandler.class).resolveGeneric(0);
            if (type == null) {
                type = GenericTypeResolver.resolveTypeArgument(factory.getType(name), RpcExceptionHandler.class);
            }
            if (type == null || !Throwable.class.isAssignableFrom(type)) {
                throw new IllegalStateException("The RpcExceptionHandler bean '" + name + "' does not say which exception it handles: declare it as "
                        + "RpcExceptionHandler<SomeException> (the return type of the @Bean method, or the class's own type argument)");
            }
            if (!seen.add(type)) {
                throw new IllegalStateException("The exception " + type.getName() + " is handled twice (the RpcExceptionHandler bean '" + name
                        + "' and another handler)");
            }
            rpc.onException((Class<Throwable>) type, (RpcExceptionHandler<Throwable>) factory.getBean(name));
        }
    }

    /** The method to call on the bean: on a proxy, the one the proxy's class has (an interface method for a JDK proxy). */
    private static Method invocable(Method method, Object bean) {
        return AopUtils.selectInvocableMethod(method, bean.getClass());
    }

    private static String describe(String beanName, Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName() + " (bean '" + beanName + "')";
    }
}
