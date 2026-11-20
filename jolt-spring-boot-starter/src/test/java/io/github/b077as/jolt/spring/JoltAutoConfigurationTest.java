package io.github.b077as.jolt.spring;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.b077as.jolt.JoltRpc;
import io.github.b077as.jolt.JoltRpcExceptionHandler;
import io.github.b077as.jolt.JsonCodec;
import io.github.b077as.jolt.Rpc;
import io.github.b077as.jolt.RpcCall;
import io.github.b077as.jolt.RpcException;
import io.github.b077as.jolt.RpcExceptionHandler;
import io.github.b077as.jolt.RpcHandler;
import io.github.b077as.jolt.RpcInterceptor;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.spring.internal.LoopbackGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the starter puts into a context, and @JoltRpc methods answering calls from a (fake) page. */
class JoltAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JoltAutoConfiguration.class));

    record Note(String text, List<String> tags) {
    }

    record Renamed(@JsonProperty("full_name") String name) {
    }

    @JoltRpc("notes")
    static class NotesService {
        @JoltRpc
        String hello() {
            return "hello";
        }

        @JoltRpc("add")
        Note add(Note note) {
            return new Note(note.text().toUpperCase(), note.tags());
        }

        @JoltRpc
        int count(List<Note> notes) {
            return notes.size();
        }

        @JoltRpc
        String withCall(RpcCall call, Map<String, Object> params, WebWindow window) {
            return call.method() + " " + params.get("x") + " " + (window == call.window());
        }

        @JoltRpc
        CompletionStage<String> later() {
            return CompletableFuture.supplyAsync(() -> "later");
        }

        @JoltRpc
        void refuse() {
            throw new RpcException("not_allowed", "No.");
        }

        @JoltRpc
        void broken() {
            throw new IllegalStateException("secret detail");
        }

        @JoltRpc
        Renamed renamed() {
            return new Renamed("Ada");
        }

        String notExposed() {
            return "no";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Notes {
        @Bean
        NotesService notesService() {
            return new NotesService();
        }
    }

    @Test
    void joltRpcMethodsAnswerThePageWithParamsCallWindowFuturesAndErrors() {
        runner.withUserConfiguration(Notes.class).run(context -> {
            Rpc rpc = context.getBean(Rpc.class);
            FakePage page = new FakePage();
            rpc.attach(page.window);

            assertEquals("hello", page.result("notes.hello", "{}"));
            assertEquals(Map.of("text", "HI", "tags", List.of("a", "b")), page.result("notes.add", "{\"text\":\"hi\",\"tags\":[\"a\",\"b\"]}"));
            assertEquals(2L, ((Number) page.result("notes.count", "[{\"text\":\"a\"},{\"text\":\"b\"}]")).longValue(), "a generic list parameter");
            assertEquals("notes.withCall 1 true", page.result("notes.withCall", "{\"x\":1}"));
            assertEquals("later", page.result("notes.later", "{}"));

            Map<?, ?> refused = page.error("notes.refuse", "{}");
            assertEquals("not_allowed", refused.get("code"));
            assertEquals("No.", refused.get("message"));

            Map<?, ?> broken = page.error("notes.broken", "{}");
            assertEquals(RpcException.INTERNAL, broken.get("code"));
            assertTrue(!String.valueOf(broken.get("message")).contains("secret"), "the exception's message stays in Java: " + broken);

            assertEquals(RpcException.UNKNOWN_METHOD, page.error("notes.notExposed", "{}").get("code"));
            assertEquals(RpcException.BAD_PARAMS, page.error("notes.add", "{\"text\":[1]}").get("code"));
        });
    }

    @Test
    void withoutAJsonLibraryBeanJoltsOwnCodecIsUsed() {
        runner.run(context -> assertSame(JsonCodec.basic(), context.getBean(JsonCodec.class)));
    }

    @Test
    void theApplicationsJacksonMapperIsUsedSoItsAnnotationsApply() {
        runner.withUserConfiguration(Notes.class)
                .withBean(tools.jackson.databind.json.JsonMapper.class, () -> tools.jackson.databind.json.JsonMapper.builder().build())
                .run(context -> {
                    FakePage page = new FakePage();
                    context.getBean(Rpc.class).attach(page.window);
                    assertEquals(Map.of("full_name", "Ada"), page.result("notes.renamed", "{}"));
                });
    }

    @Test
    void aJsonCodecBeanOfTheApplicationWins() {
        JsonCodec own = JsonCodec.of(value -> "\"own\"", (json, type) -> null);
        runner.withBean(JsonCodec.class, () -> own).run(context -> assertSame(own, context.getBean(Rpc.class).options().codec()));
    }

    @Test
    void propertiesReachTheRpcOptions() {
        runner.withPropertyValues("jolt.rpc.max-concurrent-calls=3", "jolt.rpc.max-message-chars=1000").run(context -> {
            Rpc rpc = context.getBean(Rpc.class);
            assertEquals(3, rpc.options().maxConcurrentCalls());
            assertEquals(1000, rpc.options().maxMessageChars());
        });
    }

    @Test
    void exposeErrorMessagesShowsThePageEveryExceptionWhileDeveloping() {
        runner.withUserConfiguration(Notes.class).withPropertyValues("jolt.rpc.expose-error-messages=true").run(context -> {
            FakePage page = new FakePage();
            context.getBean(Rpc.class).attach(page.window);
            Map<?, ?> broken = page.error("notes.broken", "{}");
            assertEquals(RpcException.INTERNAL, broken.get("code"));
            assertEquals("IllegalStateException: secret detail", broken.get("message"));
        });
    }

    static class RpcErrors {
        @JoltRpcExceptionHandler
        RpcException illegalArgument(IllegalArgumentException e) {
            return new RpcException("invalid", e.getMessage());
        }

        @JoltRpcExceptionHandler
        RpcException state(IllegalStateException e, RpcCall call) {
            return new RpcException("state", call.method() + ": " + e.getMessage());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NotesWithErrors {
        @Bean
        NotesService notesService() {
            return new NotesService();
        }

        @Bean
        RpcErrors rpcErrors() {
            return new RpcErrors();
        }
    }

    @Test
    void joltRpcExceptionHandlersChooseWhatThePageIsTold() {
        runner.withUserConfiguration(NotesWithErrors.class).run(context -> {
            FakePage page = new FakePage();
            context.getBean(Rpc.class).attach(page.window);
            Map<?, ?> broken = page.error("notes.broken", "{}");
            assertEquals("state", broken.get("code"));
            assertEquals("notes.broken: secret detail", broken.get("message"), "the handler got the exception and the call");
        });
    }

    @Test
    void anApplicationHandlerForThrowableReplacesTheExposeErrorMessagesOne() {
        class All {
            @JoltRpcExceptionHandler
            RpcException all(Throwable e) {
                return new RpcException("mine", "x");
            }
        }
        runner.withUserConfiguration(Notes.class).withBean(All.class, All::new).withPropertyValues("jolt.rpc.expose-error-messages=true").run(context -> {
            FakePage page = new FakePage();
            context.getBean(Rpc.class).attach(page.window);
            assertEquals("mine", page.error("notes.broken", "{}").get("code"));
        });
    }

    static class TwoHandlers {
        @JoltRpcExceptionHandler
        RpcException one(IllegalStateException e) {
            return new RpcException("a", "a");
        }

        @JoltRpcExceptionHandler
        RpcException two(IllegalStateException e) {
            return new RpcException("b", "b");
        }
    }

    static class NoException {
        @JoltRpcExceptionHandler
        RpcException none(RpcCall call) {
            return null;
        }
    }

    static class WrongReturn {
        @JoltRpcExceptionHandler
        String wrong(IllegalStateException e) {
            return "";
        }
    }

    static class ForRpcException {
        @JoltRpcExceptionHandler
        RpcException rpc(RpcException e) {
            return e;
        }
    }

    @Test
    void badExceptionHandlerMethodsStopTheApplication() {
        for (Class<?> bad : List.of(TwoHandlers.class, NoException.class, WrongReturn.class, ForRpcException.class)) {
            runner.withBean(bad).run(context -> {
                assertNotNull(context.getStartupFailure(), bad.getSimpleName());
                assertTrue(context.getStartupFailure().getMessage().contains("@JoltRpcExceptionHandler"), context.getStartupFailure().getMessage());
            });
        }
    }

    static class Audit implements RpcInterceptor {
        final List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        final String name;

        Audit(String name) {
            this.name = name;
        }

        @Override
        public Object intercept(RpcCall call, RpcHandler next) throws Exception {
            seen.add(name + " " + call.method());
            return next.handle(call);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Interceptors {
        @Bean
        @org.springframework.core.annotation.Order(2)
        Audit second() {
            return new Audit("second");
        }

        @Bean
        @org.springframework.core.annotation.Order(1)
        Audit first() {
            return new Audit("first");
        }

        @Bean
        RpcInterceptor refuseBroken() {
            return (call, next) -> {
                if (call.method().equals("notes.refuse")) {
                    throw new RpcException("blocked", "by an interceptor bean");
                }
                return next.handle(call);
            };
        }
    }

    @Test
    void rpcInterceptorBeansWrapEveryCallInTheirOrder() {
        runner.withUserConfiguration(Notes.class, Interceptors.class).run(context -> {
            FakePage page = new FakePage();
            context.getBean(Rpc.class).attach(page.window);
            assertEquals("hello", page.result("notes.hello", "{}"));
            Audit first = context.getBean("first", Audit.class);
            Audit second = context.getBean("second", Audit.class);
            assertEquals(List.of("first notes.hello"), first.seen);
            assertEquals(List.of("second notes.hello"), second.seen);
            assertEquals("blocked", page.error("notes.refuse", "{}").get("code"));
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ExceptionHandlerBeans {
        @Bean
        RpcExceptionHandler<IllegalStateException> state() {
            return (call, e) -> new RpcException("state", "handled by a bean: " + e.getMessage());
        }
    }

    static class IllegalArgumentHandler implements RpcExceptionHandler<IllegalArgumentException> {
        @Override
        public RpcException handle(RpcCall call, IllegalArgumentException error) {
            return new RpcException("argument", error.getMessage());
        }
    }

    @Test
    void rpcExceptionHandlerBeansHandleTheTypeTheyDeclare() {
        runner.withUserConfiguration(Notes.class, ExceptionHandlerBeans.class).withBean(IllegalArgumentHandler.class).run(context -> {
            FakePage page = new FakePage();
            context.getBean(Rpc.class).attach(page.window);
            Map<?, ?> broken = page.error("notes.broken", "{}");
            assertEquals("state", broken.get("code"));
            assertEquals("handled by a bean: secret detail", broken.get("message"));
            assertEquals(2, context.getBeansOfType(RpcExceptionHandler.class).size());
        });
    }

    @Test
    void anExceptionHandledByABeanAndByAnAnnotatedMethodStopsTheApplication() {
        runner.withUserConfiguration(NotesWithErrors.class, ExceptionHandlerBeans.class).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(context.getStartupFailure().getMessage().contains("handled twice"), context.getStartupFailure().getMessage());
        });
    }

    @Test
    void theTypeScriptPropertyWritesTheDeclarationsOfTheMethodsAtStartup(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path file = dir.resolve("jolt-api.d.ts");
        runner.withUserConfiguration(Notes.class).withPropertyValues("jolt.rpc.typescript=" + file.toString().replace('\\', '/')).run(context -> {
            assertNotNull(context.getBean(Rpc.class));
            String ts = java.nio.file.Files.readString(file);
            assertTrue(ts.contains("\"notes.add\": { params: Note; result: Note };"), ts);
            assertTrue(ts.contains("\"notes.later\": { params: void; result: string };"), ts);
            assertTrue(ts.contains("export interface Note {"), ts);
            assertTrue(!ts.contains("jolt.window"), ts);
        });
    }

    @Test
    void theStreamWindowPropertyReachesTheOptions() {
        runner.withPropertyValues("jolt.rpc.stream-window=7").run(context -> assertEquals(7, context.getBean(Rpc.class).options().streamWindow()));
    }

    @JoltRpc
    static class Twice {
        @JoltRpc("same")
        String one() {
            return "1";
        }

        @JoltRpc("same")
        String two() {
            return "2";
        }
    }

    @Test
    void twoMethodsWithOneNameStopTheApplication() {
        runner.withBean(Twice.class).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(context.getStartupFailure().getMessage().contains("Two @JoltRpc methods are called 'same'"), context.getStartupFailure().getMessage());
        });
    }

    static class Reserved {
        @JoltRpc("jolt.window.show")
        void show() {
        }
    }

    @Test
    void namesUnderJoltAreReserved() {
        runner.withBean(Reserved.class).run(context -> assertTrue(context.getStartupFailure().getMessage().contains("reserved"),
                context.getStartupFailure().getMessage()));
    }

    static class TwoParams {
        @JoltRpc
        void both(Note one, Note two) {
        }
    }

    @Test
    void aMethodWithTwoParameterObjectsIsRefused() {
        runner.withBean(TwoParams.class).run(context -> assertTrue(context.getStartupFailure().getMessage().contains("more than one parameter"),
                context.getStartupFailure().getMessage()));
    }

    @Test
    void aServletApplicationWithoutTheJoltRuntimeGetsNoGuard() {
        // A plain run of the same application (a test, a browser) must not find its server locked.
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(JoltAutoConfiguration.class))
                .run(context -> assertTrue(context.getBeansOfType(LoopbackGuard.class).isEmpty()));
    }

    @Test
    void aServletApplicationStartedByJoltGetsTheGuard() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(JoltAutoConfiguration.class))
                .withBean(JoltRuntime.BEAN_NAME, JoltRuntime.class, JoltRuntime::new)
                .run(context -> assertEquals(1, context.getBeansOfType(LoopbackGuard.class).size()));
    }
}
