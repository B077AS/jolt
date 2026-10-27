package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.AppResponse;
import io.github.b077as.jolt.RequestHandler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceServiceTest {

    private static final String ORIGIN = "jolt://app";

    private static ResourceService service(RequestHandler handler) {
        return new ResourceService(path -> path.equals("index.html") ? "<h1>home</h1>".getBytes(StandardCharsets.UTF_8) : null, handler);
    }

    private static String text(AppResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static String header(AppResponse response, String name) {
        return response.headers().entrySet().stream().filter(h -> h.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    @Test
    void servesFilesAndAddsSecurityHeaders() {
        AppResponse root = service(null).serve("GET", ORIGIN + "/", Map.of(), new byte[0]);
        assertEquals(200, root.status());
        assertEquals("<h1>home</h1>", text(root));
        assertEquals("text/html; charset=utf-8", header(root, "Content-Type"));
        assertEquals("nosniff", header(root, "X-Content-Type-Options"));
        assertEquals("same-origin", header(root, "Referrer-Policy"));
        assertEquals("no-cache", header(root, "Cache-Control"));
        assertEquals(404, service(null).serve("GET", ORIGIN + "/nope", Map.of(), new byte[0]).status());
        assertEquals(404, service(null).serve("POST", ORIGIN + "/index.html", Map.of(), new byte[0]).status());
    }

    @Test
    void badOrEscapingUrlsGet400() {
        assertEquals(400, service(null).serve("GET", ORIGIN + "/../secret", Map.of(), new byte[0]).status());
        assertEquals(400, service(null).serve("GET", "not a url", Map.of(), new byte[0]).status());
    }

    @Test
    void handlerComesFirstAndNullFallsThroughToFiles() {
        ResourceService service = service(request -> request.path().equals("/api") ? AppResponse.text("api:" + request.query()) : null);
        assertEquals("api:a=1", text(service.serve("GET", ORIGIN + "/api?a=1", Map.of(), new byte[0])));
        assertEquals("<h1>home</h1>", text(service.serve("GET", ORIGIN + "/", Map.of(), new byte[0])));
    }

    @Test
    void aFailingHandlerGives500WithoutDetails() {
        ResourceService service = service(request -> {
            throw new IllegalStateException("private details");
        });
        AppResponse response = service.serve("GET", ORIGIN + "/", Map.of(), new byte[0]);
        assertEquals(500, response.status());
        assertFalse(text(response).contains("private"));
    }

    @Test
    void handlerHeadersWinExceptTheSecurityOnes() {
        ResourceService service = service(request -> AppResponse.text("x").withHeader("Cache-Control", "max-age=60")
                .withHeader("Referrer-Policy", "unsafe-url").withHeader("HX-Trigger", "saved"));
        AppResponse response = service.serve("GET", ORIGIN + "/", Map.of(), new byte[0]);
        assertEquals("max-age=60", header(response, "Cache-Control"));
        assertEquals("saved", header(response, "HX-Trigger"));
        assertEquals("same-origin", header(response, "Referrer-Policy"));
    }

    @Test
    void admitRefusesWhatInProcessTransportsMustNot() {
        ResourceService files = service(null);
        ResourceService app = service(request -> null);
        assertEquals(0, files.admit("GET", Map.of(), ORIGIN));
        assertEquals(0, files.admit("HEAD", Map.of(), ORIGIN));
        assertEquals(405, files.admit("POST", Map.of(), ORIGIN));
        assertEquals(405, app.admit("OPTIONS", Map.of(), ORIGIN));
        assertEquals(405, app.admit("TRACE", Map.of(), ORIGIN));
        assertEquals(0, app.admit("POST", Map.of(), ORIGIN));
        assertEquals(0, app.admit("DELETE", Map.of("origin", ORIGIN), ORIGIN));
        assertEquals(403, app.admit("POST", Map.of("origin", "https://evil.example"), ORIGIN));
        assertEquals(403, app.admit("PUT", Map.of("origin", "null"), ORIGIN));
    }

    private static ResourceService redirecting() {
        return service(request -> switch (request.path()) {
            case "/go" -> AppResponse.redirect("/after");
            case "/go307" -> AppResponse.redirect("/echo").withStatus(307);
            case "/loop" -> AppResponse.redirect("/loop");
            case "/away" -> AppResponse.redirect("https://example.com/x");
            case "/after" -> AppResponse.text("after:" + request.method() + ":" + request.body().length);
            case "/echo" -> AppResponse.text("echo:" + request.method() + ":" + request.bodyText());
            default -> null;
        });
    }

    @Test
    void redirectsAreFollowedForFetchesWithTheRightMethod() {
        ResourceService service = redirecting();
        Map<String, String> fetch = Map.of("accept", "*/*");
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
        assertEquals("after:GET:0", text(service.serveFollowingRedirects("POST", ORIGIN + "/go", fetch, body)));
        assertEquals("echo:POST:abc", text(service.serveFollowingRedirects("POST", ORIGIN + "/go307", fetch, body)));
    }

    @Test
    void navigationsGetAPageThatMovesThere() {
        AppResponse response = redirecting().serveFollowingRedirects("POST", ORIGIN + "/go",
                Map.of("accept", "text/html,application/xhtml+xml"), new byte[0]);
        assertEquals(200, response.status());
        assertTrue(text(response).contains("location.replace(\"jolt://app/after\")"), text(response));
        assertTrue(text(response).contains("url=jolt://app/after"));
    }

    @Test
    void foreignOriginAndLoopingRedirectsAreReturnedAsTheyAre() {
        ResourceService service = redirecting();
        Map<String, String> fetch = Map.of("accept", "*/*");
        assertEquals(303, service.serveFollowingRedirects("GET", ORIGIN + "/away", fetch, new byte[0]).status());
        assertEquals(303, service.serveFollowingRedirects("GET", ORIGIN + "/loop", fetch, new byte[0]).status());
    }

    @Test
    void reasonPhrasesCoverTheUsualStatuses() {
        assertEquals("Not Found", ResourceService.reasonPhrase(404));
        assertEquals("No Content", ResourceService.reasonPhrase(204));
        assertEquals("See Other", ResourceService.reasonPhrase(303));
    }
}
