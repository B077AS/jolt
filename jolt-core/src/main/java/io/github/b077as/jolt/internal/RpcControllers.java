package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.JoltRpc;
import io.github.b077as.jolt.JoltRpcExceptionHandler;
import io.github.b077as.jolt.RpcCall;
import io.github.b077as.jolt.RpcException;
import io.github.b077as.jolt.RpcExceptionHandler;
import io.github.b077as.jolt.RpcHandler;
import io.github.b077as.jolt.WebWindow;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The reflection behind {@link JoltRpc} and {@link JoltRpcExceptionHandler}: which argument of a method gets what the page sent, the
 * {@link RpcCall} or the window, the checks of a method's shape, and the scan of a controller object for {@code Rpc.register}. The
 * Spring starter uses the same pieces, so both behave alike; it only finds the beans (and sees through proxies) itself.
 */
public final class RpcControllers {

    /** Names under this prefix belong to Jolt (such as {@code jolt.window.show}). */
    public static final String RESERVED_PREFIX = "jolt.";

    /** What a scan found: the handlers by the name the page calls, and the exception handlers by the exception type. */
    public record Scan(Map<String, RpcHandler> methods, Map<Class<? extends Throwable>, RpcExceptionHandler<Throwable>> exceptionHandlers) {
    }

    private RpcControllers() {
    }

    // ---------------------------------------------------------------- a controller object

    /**
     * Reads the {@link JoltRpc} and {@link JoltRpcExceptionHandler} methods of {@code controller} (its class and superclasses).
     *
     * @throws IllegalStateException if there are none, or a method has the wrong shape, a reserved or repeated name
     */
    public static Scan scan(Object controller) {
        Class<?> type = controller.getClass();
        JoltRpc onClass = null;
        for (Class<?> c = type; c != null && onClass == null; c = c.getSuperclass()) {
            onClass = c.getAnnotation(JoltRpc.class);
        }
        String prefix = onClass == null ? "" : onClass.value().strip();
        Set<String> signatures = new HashSet<>();
        Map<String, Method> byName = new LinkedHashMap<>();
        Map<Class<? extends Throwable>, Method> byType = new LinkedHashMap<>();
        Map<String, RpcHandler> methods = new LinkedHashMap<>();
        Map<Class<? extends Throwable>, RpcExceptionHandler<Throwable>> exceptionHandlers = new LinkedHashMap<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                JoltRpc rpc = method.getAnnotation(JoltRpc.class);
                JoltRpcExceptionHandler handler = method.getAnnotation(JoltRpcExceptionHandler.class);
                if (method.isBridge() || method.isSynthetic() || (rpc == null && handler == null)) {
                    continue;
                }
                if (!signatures.add(method.getName() + Arrays.toString(method.getParameterTypes()))) {
                    continue; // overridden by a subclass that has it too
                }
                String where = describe(method);
                if (rpc != null) {
                    String own = rpc.value().strip();
                    String name = prefix.isEmpty() ? nameOf(own, method) : prefix + "." + nameOf(own, method);
                    checkMethod(name, method, where);
                    Method earlier = byName.putIfAbsent(name, method);
                    if (earlier != null) {
                        throw new IllegalStateException("Two @JoltRpc methods are called '" + name + "': " + describe(earlier) + " and " + where);
                    }
                    methods.put(name, handler(controller, method, method, where));
                }
                if (handler != null) {
                    @SuppressWarnings("unchecked")
                    Class<? extends Throwable> exception = (Class<? extends Throwable>) exceptionType(method, where);
                    Method earlier = byType.putIfAbsent(exception, method);
                    if (earlier != null) {
                        throw new IllegalStateException("Two @JoltRpcExceptionHandler methods handle " + exception.getName() + ": "
                                + describe(earlier) + " and " + where);
                    }
                    exceptionHandlers.put(exception, exceptionHandler(controller, method, method, where));
                }
            }
        }
        if (methods.isEmpty() && exceptionHandlers.isEmpty()) {
            throw new IllegalStateException(type.getName() + " has no @JoltRpc or @JoltRpcExceptionHandler methods");
        }
        return new Scan(methods, exceptionHandlers);
    }

    // ---------------------------------------------------------------- one method

    /** The name the page calls: {@code own} (the annotation's value), or the method's name when that is empty. */
    public static String nameOf(String own, Method method) {
        return own.isEmpty() ? method.getName() : own;
    }

    /** Checks that a {@link JoltRpc} method may be registered as {@code name}. {@code where} says which method, for the messages. */
    public static void checkMethod(String name, Method method, String where) {
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalStateException("@JoltRpc method " + where + " is static; it must be an instance method");
        }
        if (name.startsWith(RESERVED_PREFIX)) {
            throw new IllegalStateException("@JoltRpc name '" + name + "' on " + where + " starts with '" + RESERVED_PREFIX
                    + "', which is reserved for Jolt");
        }
    }

    /**
     * Builds the handler once: which argument goes where is worked out here, not on every call.
     *
     * @param declared  the annotated method, whose parameters say what the page sends
     * @param invocable the method to call on {@code bean}: {@code declared}, or its counterpart on a proxy
     */
    public static RpcHandler handler(Object bean, Method declared, Method invocable, String where) {
        makeAccessible(invocable, where);
        Parameter[] parameters = declared.getParameters();
        Type paramsType = null;
        int paramsIndex = -1;
        for (int i = 0; i < parameters.length; i++) {
            Class<?> type = parameters[i].getType();
            if (type != RpcCall.class && type != WebWindow.class) {
                if (paramsIndex >= 0) {
                    throw new IllegalStateException("@JoltRpc method " + where
                            + " has more than one parameter for what the page sends; put them in one record");
                }
                paramsIndex = i;
                paramsType = parameters[i].getParameterizedType();
            }
        }
        int index = paramsIndex;
        Type type = paramsType;
        return call -> {
            Object[] arguments = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                Class<?> parameter = parameters[i].getType();
                arguments[i] = i == index ? call.params(type)
                        : parameter == RpcCall.class ? call
                        : call.window();
            }
            try {
                return invocable.invoke(bean, arguments);
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

    // ---------------------------------------------------------------- one exception handler method

    /** The exception a {@link JoltRpcExceptionHandler} method is for: its one {@code Throwable} parameter. Checks the rest of the shape too. */
    public static Class<?> exceptionType(Method method, String where) {
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

    /** The handler for a method that passed {@link #exceptionType}. Arguments as for {@link #handler}. */
    public static RpcExceptionHandler<Throwable> exceptionHandler(Object bean, Method declared, Method invocable, String where) {
        makeAccessible(invocable, where);
        Class<?>[] parameters = declared.getParameterTypes();
        return (call, error) -> {
            Object[] arguments = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                arguments[i] = parameters[i] == RpcCall.class ? call : error;
            }
            try {
                return (RpcException) invocable.invoke(bean, arguments);
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

    // ---------------------------------------------------------------- helpers

    private static void makeAccessible(Method method, String where) {
        if (!method.trySetAccessible()) {
            throw new IllegalStateException(where + " cannot be called by reflection: make the class and the method public, or open its "
                    + "package to jolt-core in module-info.java");
        }
    }

    private static String describe(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }
}
