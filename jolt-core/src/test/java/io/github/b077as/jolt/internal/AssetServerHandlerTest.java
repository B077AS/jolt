package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.AppRequest;
import io.github.b077as.jolt.AppResources;
import io.github.b077as.jolt.AppResponse;
import io.github.b077as.jolt.RequestHandler;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetServerHandlerTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final AppResources FILES = path -> switch (path) {
        case "index.html" -> "<h1>home</h1>".getBytes(StandardCharsets.UTF_8);
        case "fonts/icons.woff2" -> new byte[] {1, 2, 3};
        case "fonts/icons.eot" -> new byte[] {4};
        default -> null;
    };

    private static final RequestHandler HANDLER = request -> switch (request.path()) {
        case "/fragment" -> AppResponse.html("<b>" + request.method() + ":" + request.query() + "</b>");
        case "/echo" -> AppResponse.html(request.formValue("name") + "|" + request.isHtmx() + "|" + request.header("x-jolt-token"));
        case "/boom" -> throw new IllegalStateException("secret internals");
        case "/go" -> AppResponse.redirect("/fragment");
        case "/trigger" -> AppResponse.status(204).withHeader("HX-Trigger", "saved");
        default -> null;
    };

    private static HttpResponse<String> call(AssetServer server, String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.origin() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void getRequestsReachTheHandlerAndFallBackToFiles() throws Exception {
        try (AssetServer server = new AssetServer(FILES, HANDLER, 0)) {
            HttpResponse<String> fragment = call(server, "GET", "/fragment?a=1", null);
            assertEquals("<b>GET:a=1</b>", fragment.body());
            assertEquals("text/html; charset=utf-8", fragment.headers().firstValue("Content-Type").orElse(null));
            assertEquals("<h1>home</h1>", call(server, "GET", "/", null).body());
            assertEquals(404, call(server, "GET", "/missing", null).statusCode());
        }
    }

    @Test
    void postNeedsTheSecret() throws Exception {
        try (AssetServer server = new AssetServer(FILES, HANDLER, 0)) {
            String form = "name=Ann+Lee";
            assertEquals(403, call(server, "POST", "/echo", form).statusCode());
            assertEquals(403, call(server, "POST", "/echo", form, "X-Jolt-Token", "wrong").statusCode());
            assertEquals(403, call(server, "POST", "/echo", form, "X-Jolt-Token", server.token().substring(1)).statusCode());
            HttpResponse<String> ok = call(server, "POST", "/echo", form, "X-Jolt-Token", server.token(), "HX-Request", "true",
                    "Content-Type", "application/x-www-form-urlencoded");
            assertEquals(200, ok.statusCode());
            // the handler sees the form and htmx marker, but never the secret
            assertEquals("Ann Lee|true|null", ok.body());
        }
    }

    @Test
    void everyChangingMethodNeedsTheSecret() throws Exception {
        try (AssetServer server = new AssetServer(FILES, HANDLER, 0)) {
            for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
                assertEquals(403, call(server, method, "/echo", "name=x").statusCode(), method);
                assertEquals(200, call(server, method, "/echo", "name=x", "X-Jolt-Token", server.token()).statusCode(), method);
            }
        }
    }

    @Test
    void crossSiteAndForeignOriginRequestsAreRefusedEvenWithTheSecret() throws Exception {
        try (AssetServer server = new AssetServer(FILES, HANDLER, 0)) {
            String token = server.token();
            assertEquals(403, call(server, "POST", "/echo", "name=x", "X-Jolt-Token", token, "Origin", "https://evil.example").statusCode());
            assertEquals(403, call(server, "POST", "/echo", "name=x", "X-Jolt-Token", token, "Origin", "null").statusCode());
            assertEquals(200, call(server, "POST", "/echo", "name=x", "X-Jolt-Token", token, "Origin", server.origin()).statusCode());
            for (String site : new String[] {"cross-site", "same-site"}) {
                assertEquals(403, call(server, "GET", "/fragment", null, "Sec-Fetch-Site", site).statusCode(), site);
                assertEquals(403, call(server, "POST", "/echo", "name=x", "X-Jolt-Token", token, "Sec-Fetch-Site", site).statusCode(), site);
            }
            assertEquals(200, call(server, "GET", "/fragment", null, "Sec-Fetch-Site", "same-origin").statusCode());
            assertEquals(200, call(server, "GET", "/", null, "Sec-Fetch-Site", "none").statusCode());
        }
    }

    @Test
    void noCorsAndNoPreflight() throws Exception {
        try (AssetServer server = new AssetServer(FILES, HANDLER, 0)) {
            HttpResponse<String> preflight = call(server, "OPTIONS", "/echo", null, "Origin", "https://evil.example",
                    "Access-Control-Request-Method", "POST");
            assertEquals(405, preflight.statusCode());
            assertTrue(preflight.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            HttpResponse<String> get = call(server, "GET", "/fragment", null);
            assertTrue(get.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            assertEquals("same-origin", get.headers().firstValue("Cross-Origin-Resource-Policy").orElse(null));
            // not no-referrer: browsers then send "Origin: null" on same-origin form posts, which the server (rightly) refuses
            assertEquals("same-origin", get.headers().firstValue("Referrer-Policy").orElse(null));
        }
    }

    @Test
    void withoutAHandlerChangingMethodsAreNotAllowedAtAll() throws Exception {
        try (AssetServer server = new AssetServer(FILES, null, 0)) {
            assertEquals(405, call(server, "POST", "/", "x", "X-Jolt-Token", server.token()).statusCode());
        }
    }

    @Test
    void handlerFailuresDoNotLeakDetailsAndRedirectsAndHeadersWork() throws Exception {
        try (AssetServer server = new AssetServer(FILES, HANDLER, 0)) {
            HttpResponse<String> boom = call(server, "GET", "/boom", null);
            assertEquals(500, boom.statusCode());
            assertFalse(boom.body().contains("secret internals"));
            HttpResponse<String> go = call(server, "GET", "/go", null);
            assertEquals(303, go.statusCode());
            assertEquals("/fragment", go.headers().firstValue("Location").orElse(null));
            HttpResponse<String> trigger = call(server, "POST", "/trigger", "", "X-Jolt-Token", server.token());
            assertEquals(204, trigger.statusCode());
            assertEquals("saved", trigger.headers().firstValue("HX-Trigger").orElse(null));
        }
    }

    @Test
    void oversizedBodiesAreRejected() throws Exception {
        try (AssetServer server = new AssetServer(FILES, HANDLER, 0)) {
            String big = "a".repeat(AssetServer.MAX_BODY + 1);
            assertEquals(413, call(server, "POST", "/echo", big, "X-Jolt-Token", server.token()).statusCode());
        }
    }

    @Test
    void tokensDifferPerServerAndAreNotGuessable() throws Exception {
        try (AssetServer a = new AssetServer(FILES, HANDLER, 0); AssetServer b = new AssetServer(FILES, HANDLER, 0)) {
            assertNotEquals(a.token(), b.token());
            assertTrue(a.token().length() >= 43);
            assertEquals(403, call(a, "POST", "/echo", "name=x", "X-Jolt-Token", b.token()).statusCode());
        }
    }

    @Test
    void fontsAreServedWithFontTypesAndIgnoreVersionQueries() throws Exception {
        try (AssetServer server = new AssetServer(FILES, HANDLER, 0)) {
            HttpResponse<String> woff2 = call(server, "GET", "/fonts/icons.woff2?v=7.4.47", null);
            assertEquals(200, woff2.statusCode());
            assertEquals("font/woff2", woff2.headers().firstValue("Content-Type").orElse(null));
            assertEquals("application/vnd.ms-fontobject",
                    call(server, "GET", "/fonts/icons.eot?v=7.4.47", null).headers().firstValue("Content-Type").orElse(null));
        }
    }

    @Test
    void theInitScriptOnlyActsOnTheServerOriginAndOnlySendsTheSecretThere() throws Exception {
        try (AssetServer server = new AssetServer(FILES, HANDLER, 0)) {
            String script = server.initScript();
            assertTrue(script.contains(server.token()));
            assertTrue(script.contains("location.origin !== \"" + server.origin() + "\""), script);
            assertTrue(script.contains("sameServer(e.detail.path)"));
            assertTrue(script.contains("sameServer(request.url)"));
        }
    }

    @Test
    void plainFormPostsCarryTheSecretAsAFieldAndHandlersNeverSeeIt() throws Exception {
        try (AssetServer server = new AssetServer(FILES, HANDLER, 0)) {
            String type = "application/x-www-form-urlencoded";
            HttpResponse<String> ok = call(server, "POST", "/echo", "name=Ann&_jolt_token=" + server.token() + "&x=1", "Content-Type", type);
            assertEquals(200, ok.statusCode());
            assertEquals("Ann|false|null", ok.body());
            assertEquals(403, call(server, "POST", "/echo", "name=Ann", "Content-Type", type).statusCode());
            assertEquals(403, call(server, "POST", "/echo", "name=Ann&_jolt_token=nope", "Content-Type", type).statusCode());
            assertEquals(403, call(server, "POST", "/echo", "name=Ann&_jolt_token=", "Content-Type", type).statusCode());
            assertEquals(403, call(server, "POST", "/echo", "name=Ann&other=_jolt_token%3D" + server.token(), "Content-Type", type).statusCode());
            // other origins and other sites are still refused even with the right field
            assertEquals(403, call(server, "POST", "/echo", "_jolt_token=" + server.token(), "Content-Type", type, "Origin", "https://evil.example").statusCode());
            assertEquals(403, call(server, "POST", "/echo", "_jolt_token=" + server.token(), "Content-Type", type, "Sec-Fetch-Site", "cross-site").statusCode());
            // the field is only for urlencoded forms: a multipart or text/plain body needs the header
            assertEquals(403, call(server, "POST", "/echo", "_jolt_token=" + server.token(), "Content-Type", "text/plain").statusCode());
            String multipart = "--b\r\nContent-Disposition: form-data; name=\"_jolt_token\"\r\n\r\n" + server.token() + "\r\n--b--";
            assertEquals(403, call(server, "POST", "/echo", multipart,
                    "Content-Type", "multipart/form-data; boundary=b").statusCode());
        }
    }

    @Test
    void theInitScriptAddsTheFieldOnlyToFormsPostingToTheServer() throws Exception {
        try (AssetServer server = new AssetServer(FILES, HANDLER, 0)) {
            String script = server.initScript();
            assertTrue(script.contains("addEventListener('submit'"));
            assertTrue(script.contains("sameServer((by && by.formAction) || form.action)"));
            assertTrue(script.contains("\"_jolt_token\""));
        }
    }

    @Test
    void appResponseRejectsHeaderInjection() {
        assertThrows(IllegalArgumentException.class, () -> AppResponse.status(200).withHeader("X-A", "ok\r\nSet-Cookie: x=1"));
        assertThrows(IllegalArgumentException.class, () -> AppResponse.status(200).withHeader("X\nA", "ok"));
        assertThrows(IllegalArgumentException.class, () -> AppResponse.status(99));
    }

    @Test
    void appRequestParsesFormsAndQueries() {
        AppRequest request = new AppRequest("POST", "/x", "a=1&a=2&b=%C3%A9", Map.of("hx-request", "true"),
                "name=Ann+Lee&note=a%26b&empty=".getBytes(StandardCharsets.UTF_8));
        assertEquals("Ann Lee", request.formValue("name"));
        assertEquals("a&b", request.formValue("note"));
        assertEquals("", request.formValue("empty"));
        assertEquals(List.of("1", "2"), request.queryParams().get("a"));
        assertEquals("é", request.queryParams().get("b").get(0));
        assertTrue(request.isHtmx());
        assertEquals("true", request.header("HX-Request"));
    }
}
