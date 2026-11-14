package io.github.b077as.jolt.spring.internal;

import io.github.b077as.jolt.Rpc;
import io.github.b077as.jolt.RpcCall;
import io.github.b077as.jolt.RpcHandler;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.spring.JoltRpc;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds {@link JoltRpc} methods on beans as they are created and, once every singleton exists, registers them with the
 * application's {@link Rpc}. Registering at the end (not while beans are being created) keeps the {@code Rpc} bean, and the
 * JSON mapper it uses, out of the bean post-processing phase.
 */
public final class RpcMethodRegistrar implements BeanPostProcessor, SmartInitializingSingleton, BeanFactoryAware {

    /** Names under this prefix belong to Jolt (such as {@code jolt.window.show}). */
    public static final String RESERVED_PREFIX = "jolt.";

    private record Found(String name, Object bean, String beanName, Method method) {
    }

    private final List<Found> found = new ArrayList<>();
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
        if (methods.isEmpty()) {
            return bean;
        }
        JoltRpc onClass = AnnotatedElementUtils.findMergedAnnotation(type, JoltRpc.class);
        String prefix = onClass == null ? "" : onClass.value().strip();
        synchronized (found) {
            for (Map.Entry<Method, JoltRpc> entry : methods.entrySet()) {
                Method method = entry.getKey();
                if (Modifier.isStatic(method.getModifiers())) {
                    throw new IllegalStateException("@JoltRpc method " + describe(beanName, method) + " is static; it must be an instance method");
                }
                String own = entry.getValue().value().strip();
                String name = own.isEmpty() ? method.getName() : own;
                found.add(new Found(prefix.isEmpty() ? name : prefix + "." + name, bean, beanName, method));
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
        if (all.isEmpty()) {
            return;
        }
        Map<String, Found> byName = new LinkedHashMap<>();
        for (Found method : all) {
            if (method.name().startsWith(RESERVED_PREFIX)) {
                throw new IllegalStateException("@JoltRpc name '" + method.name() + "' on " + describe(method.beanName(), method.method())
                        + " starts with '" + RESERVED_PREFIX + "', which is reserved for Jolt");
            }
            Found earlier = byName.putIfAbsent(method.name(), method);
            if (earlier != null) {
                throw new IllegalStateException("Two @JoltRpc methods are called '" + method.name() + "': "
                        + describe(earlier.beanName(), earlier.method()) + " and " + describe(method.beanName(), method.method()));
            }
        }
        Rpc rpc = beanFactory.getBean(Rpc.class);
        for (Found method : byName.values()) {
            rpc.on(method.name(), handler(method));
        }
    }

    /** Builds the handler once: which argument goes where is worked out here, not on every call. */
    static RpcHandler handler(Found found) {
        Method target = AopUtils.selectInvocableMethod(found.method(), found.bean().getClass());
        ReflectionUtils.makeAccessible(target);
        Parameter[] parameters = found.method().getParameters();
        Type paramsType = null;
        int paramsIndex = -1;
        for (int i = 0; i < parameters.length; i++) {
            Class<?> type = parameters[i].getType();
            if (type != RpcCall.class && type != WebWindow.class) {
                if (paramsIndex >= 0) {
                    throw new IllegalStateException("@JoltRpc method " + describe(found.beanName(), found.method())
                            + " has more than one parameter for what the page sends; put them in one record");
                }
                paramsIndex = i;
                paramsType = parameters[i].getParameterizedType();
            }
        }
        int index = paramsIndex;
        Type type = paramsType;
        Object bean = found.bean();
        return call -> {
            Object[] arguments = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                Class<?> parameter = parameters[i].getType();
                arguments[i] = i == index ? call.params(type)
                        : parameter == RpcCall.class ? call
                        : call.window();
            }
            try {
                return target.invoke(bean, arguments);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw e;
            }
        };
    }

    private static String describe(String beanName, Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName() + " (bean '" + beanName + "')";
    }
}
