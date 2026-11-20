package io.github.b077as.jolt.spring.internal;

import io.github.b077as.jolt.Rpc;
import io.github.b077as.jolt.RpcCall;
import io.github.b077as.jolt.RpcException;
import io.github.b077as.jolt.RpcExceptionHandler;
import io.github.b077as.jolt.RpcHandler;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.spring.JoltRpc;
import io.github.b077as.jolt.spring.JoltRpcExceptionHandler;
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
 * Finds {@link JoltRpc} and {@link JoltRpcExceptionHandler} methods on beans as they are created and, once every singleton exists,
 * registers them with the application's {@link Rpc}. Registering at the end (not while beans are being created) keeps the {@code Rpc} bean, and the
 * JSON mapper it uses, out of the bean post-processing phase.
 */
public final class RpcMethodRegistrar implements BeanPostProcessor, SmartInitializingSingleton, BeanFactoryAware {

    /** Names under this prefix belong to Jolt (such as {@code jolt.window.show}). */
    public static final String RESERVED_PREFIX = "jolt.";

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
        List<FoundHandler> exceptionHandlers;
        synchronized (foundHandlers) {
            exceptionHandlers = List.copyOf(foundHandlers);
            foundHandlers.clear();
        }
        if (all.isEmpty() && exceptionHandlers.isEmpty()) {
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
        Map<Class<?>, FoundHandler> byType = new LinkedHashMap<>();
        for (FoundHandler handler : exceptionHandlers) {
            Class<?> type = exceptionType(handler);
            FoundHandler earlier = byType.putIfAbsent(type, handler);
            if (earlier != null) {
                throw new IllegalStateException("Two @JoltRpcExceptionHandler methods handle " + type.getName() + ": "
                        + describe(earlier.beanName(), earlier.method()) + " and " + describe(handler.beanName(), handler.method()));
            }
        }
        Rpc rpc = beanFactory.getBean(Rpc.class);
        for (Found method : byName.values()) {
            rpc.on(method.name(), handler(method));
        }
        for (Map.Entry<Class<?>, FoundHandler> entry : byType.entrySet()) {
            @SuppressWarnings("unchecked")
            Class<Throwable> type = (Class<Throwable>) entry.getKey();
            rpc.onException(type, exceptionHandler(entry.getValue()));
        }
    }

    /** The exception a handler method is for: its one {@code Throwable} parameter. Checks the rest of the signature too. */
    static Class<?> exceptionType(FoundHandler found) {
        Method method = found.method();
        String where = describe(found.beanName(), method);
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalStateException("@JoltRpcExceptionHandler method " + where + " is static; it must be an instance method");
        }
        if (!RpcException.class.isAssignableFrom(method.getReturnType())) {
            throw new IllegalStateException("@JoltRpcExceptionHandler method " + where + " must return an RpcException, not "
                    + method.getReturnType().getTypeName());
        }
        Class<?> exception = null;
        for (Class<?> parameter : method.getParameterTypes()) {
            if (parameter == RpcCall.class) {
                continue;
            }
            if (!Throwable.class.isAssignableFrom(parameter) || exception != null) {
                throw new IllegalStateException("@JoltRpcExceptionHandler method " + where
                        + " takes one exception and optionally the RpcCall, nothing else");
            }
            exception = parameter;
        }
        if (exception == null) {
            throw new IllegalStateException("@JoltRpcExceptionHandler method " + where + " takes no exception to handle");
        }
        if (RpcException.class.isAssignableFrom(exception)) {
            throw new IllegalStateException("@JoltRpcExceptionHandler method " + where
                    + " handles an RpcException, which already says what the page is told");
        }
        return exception;
    }

    private static RpcExceptionHandler<Throwable> exceptionHandler(FoundHandler found) {
        Method target = AopUtils.selectInvocableMethod(found.method(), found.bean().getClass());
        ReflectionUtils.makeAccessible(target);
        Class<?>[] parameters = found.method().getParameterTypes();
        Object bean = found.bean();
        return (call, error) -> {
            Object[] arguments = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                arguments[i] = parameters[i] == RpcCall.class ? call : error;
            }
            try {
                return (RpcException) target.invoke(bean, arguments);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException exception) {
                    throw exception;
                }
                if (cause instanceof Error failure) {
                    throw failure;
                }
                throw new IllegalStateException(e);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        };
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
