package io.github.b077as.jolt.spring;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.b077as.jolt.JsonCodec;
import io.github.b077as.jolt.Rpc;
import io.github.b077as.jolt.RpcCall;
import io.github.b077as.jolt.RpcException;
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
        runner.withPropertyValues("jolt.rpc.expose-error-messages=true", "jolt.rpc.max-concurrent-calls=3").run(context -> {
            Rpc rpc = context.getBean(Rpc.class);
            assertTrue(rpc.options().exposeErrorMessages());
            assertEquals(3, rpc.options().maxConcurrentCalls());
        });
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
