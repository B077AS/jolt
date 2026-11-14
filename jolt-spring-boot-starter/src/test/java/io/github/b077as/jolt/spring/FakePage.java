package io.github.b077as.jolt.spring;

import io.github.b077as.jolt.SecurityPolicy;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.internal.BasicJson;
import io.github.b077as.jolt.internal.MiniJson;
import io.github.b077as.jolt.internal.PageRpc;
import io.github.b077as.jolt.internal.RpcProtocol;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** A WebWindow without a window: calls go in as the page would send them, answers are read back from the scripts Java runs. */
final class FakePage {

    private static final String SOURCE = SecurityPolicy.APP_ORIGIN + "/index.html";
    private static final String DELIVERY = "window.__joltRpc&&window.__joltRpc.receive(";

    private final BlockingQueue<String> scripts = new LinkedBlockingQueue<>();
    private final AtomicLong ids = new AtomicLong();
    final WebWindow window = (WebWindow) Proxy.newProxyInstance(WebWindow.class.getClassLoader(), new Class<?>[] {WebWindow.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "eval" -> {
                    scripts.add((String) args[0]);
                    yield CompletableFuture.completedFuture("null");
                }
                case "security" -> SecurityPolicy.standard();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "FakePage";
                default -> null;
            });

    FakePage() {
        PageRpc.deliver(window, RpcProtocol.PREFIX + "{\"t\":\"hello\",\"s\":\"s\"}", SOURCE);
    }

    /** Calls {@code method} and returns the answer: {@code {t:"result", r:...}} or {@code {t:"error", e:{code,message}}}. */
    Map<?, ?> call(String method, String paramsJson) throws InterruptedException {
        long id = ids.incrementAndGet();
        PageRpc.deliver(window, RpcProtocol.PREFIX + "{\"t\":\"call\",\"s\":\"s\",\"id\":" + id + ",\"m\":" + BasicJson.quote(method)
                + ",\"p\":" + paramsJson + "}", SOURCE);
        while (true) {
            String script = scripts.poll(10, TimeUnit.SECONDS);
            assertNotNull(script, "no answer to " + method);
            if (script.startsWith(DELIVERY)) {
                Map<?, ?> message = (Map<?, ?>) BasicJson.parse(MiniJson.decodeString(script.substring(DELIVERY.length(), script.length() - 2)));
                if (message.get("id") instanceof Number n && n.longValue() == id && !"progress".equals(message.get("t"))) {
                    return message;
                }
            }
        }
    }

    /** The result of a call that must succeed. */
    Object result(String method, String paramsJson) throws InterruptedException {
        Map<?, ?> answer = call(method, paramsJson);
        if (!"result".equals(answer.get("t"))) {
            throw new AssertionError(method + " failed: " + answer);
        }
        return answer.get("r");
    }

    /** The error of a call that must fail. */
    Map<?, ?> error(String method, String paramsJson) throws InterruptedException {
        Map<?, ?> answer = call(method, paramsJson);
        if (!"error".equals(answer.get("t"))) {
            throw new AssertionError(method + " did not fail: " + answer);
        }
        return (Map<?, ?>) answer.get("e");
    }
}
