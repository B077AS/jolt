package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.AppResources;
import io.github.b077as.jolt.AppResponse;
import io.github.b077as.jolt.RequestHandler;
import io.github.b077as.jolt.ResourceOptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetServerMultipartTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final AppResources NO_FILES = path -> null;
    private static final String TYPE = "multipart/form-data; boundary=" + MultipartParserTest.B;

    /** Describes what the handler received, so a test can see exactly what got through. */
    private static final RequestHandler DESCRIBE = request -> {
        if (!request.path().equals("/upload")) {
            return null;
        }
        String summary = request.parts().stream()
                .map(part -> part.name() + (part.isFile() ? "(" + part.filename() + "," + part.size() + ")" : "=" + part.text()))
                .collect(Collectors.joining(";"));
        return AppResponse.text(summary + "|" + request.header("x-jolt-token"));
    };

    private static HttpResponse<String> upload(AssetServer server, byte[] body, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.origin() + "/upload"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).header("Content-Type", TYPE);
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static MultipartParserTest.Body form() {
        return new MultipartParserTest.Body().field("name", "Ann").file("doc", "../d.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aMultipartFormWithTheSecretFieldReachesTheHandlerWithoutIt() throws Exception {
        try (AssetServer server = new AssetServer(NO_FILES, DESCRIBE, 0)) {
            HttpResponse<String> ok = upload(server, form().field("_jolt_token", server.token()).done());
            assertEquals(200, ok.statusCode());
            assertEquals("name=Ann;doc(d.txt,5)|null", ok.body());
        }
    }

    @Test
    void theSecretFieldMayComeFirstOrInTheMiddle() throws Exception {
        try (AssetServer server = new AssetServer(NO_FILES, DESCRIBE, 0)) {
            byte[] first = new MultipartParserTest.Body().field("_jolt_token", server.token()).field("name", "Ann").done();
            assertEquals("name=Ann|null", upload(server, first).body());
            byte[] middle = new MultipartParserTest.Body().field("a", "1").field("_jolt_token", server.token()).field("b", "2").done();
            assertEquals("a=1;b=2|null", upload(server, middle).body());
        }
    }

    @Test
    void theHeaderAloneIsEnoughForMultipartAndAFieldIsStillStripped() throws Exception {
        try (AssetServer server = new AssetServer(NO_FILES, DESCRIBE, 0)) {
            assertEquals("name=Ann;doc(d.txt,5)|null", upload(server, form().done(), "X-Jolt-Token", server.token()).body());
            assertEquals("name=Ann;doc(d.txt,5)|null",
                    upload(server, form().field("_jolt_token", "ignored").done(), "X-Jolt-Token", server.token()).body());
        }
    }

    @Test
    void multipartWithoutOrWithAWrongSecretIsRefused() throws Exception {
        try (AssetServer server = new AssetServer(NO_FILES, DESCRIBE, 0)) {
            assertEquals(403, upload(server, form().done()).statusCode());
            assertEquals(403, upload(server, form().field("_jolt_token", "wrong").done()).statusCode());
            assertEquals(403, upload(server, form().field("_jolt_token", "").done()).statusCode());
            assertEquals(403, upload(server, form().done(), "X-Jolt-Token", "wrong").statusCode());
            // a file called _jolt_token is not the field
            byte[] file = new MultipartParserTest.Body().file("_jolt_token", "x.txt", "text/plain", server.token().getBytes(StandardCharsets.UTF_8)).done();
            assertEquals(403, upload(server, file).statusCode());
            // the secret as the content of an unrelated field does not count
            assertEquals(403, upload(server, new MultipartParserTest.Body().field("other", server.token()).done()).statusCode());
            // a malformed body without a header is refused, not answered with a parse error
            assertEquals(403, upload(server, "junk".getBytes(StandardCharsets.UTF_8)).statusCode());
        }
    }

    @Test
    void crossSiteMultipartIsRefusedEvenWithTheSecret() throws Exception {
        try (AssetServer server = new AssetServer(NO_FILES, DESCRIBE, 0)) {
            byte[] body = form().field("_jolt_token", server.token()).done();
            assertEquals(403, upload(server, body, "Origin", "https://evil.example").statusCode());
            assertEquals(403, upload(server, body, "Sec-Fetch-Site", "cross-site").statusCode());
            assertEquals(200, upload(server, body, "Origin", server.origin()).statusCode());
        }
    }

    @Test
    void aMalformedBodyWithTheHeaderReachesTheHandlerWhichSeesTheError() throws Exception {
        RequestHandler strict = request -> AppResponse.text(String.valueOf(assertThrows(IllegalArgumentException.class, request::parts).getMessage()));
        try (AssetServer server = new AssetServer(NO_FILES, strict, 0)) {
            HttpResponse<String> response = upload(server, "junk".getBytes(StandardCharsets.UTF_8), "X-Jolt-Token", server.token());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().startsWith("malformed multipart body"), response.body());
        }
    }

    @Test
    void theRequestSizeLimitIsConfigurable() throws Exception {
        byte[] big = new MultipartParserTest.Body().file("f", "big.bin", "application/octet-stream", new byte[3000]).done();
        try (AssetServer small = new AssetServer(NO_FILES, DESCRIBE, ResourceOptions.defaults().withMaxRequestBytes(1000), 0)) {
            assertEquals(413, upload(small, big, "X-Jolt-Token", small.token()).statusCode());
            assertEquals(200, upload(small, new MultipartParserTest.Body().field("a", "1").done(), "X-Jolt-Token", small.token()).statusCode());
        }
        try (AssetServer roomy = new AssetServer(NO_FILES, DESCRIBE, ResourceOptions.defaults().withMaxRequestBytes(100_000), 0)) {
            assertEquals("f(big.bin,3000)|null", upload(roomy, big, "X-Jolt-Token", roomy.token()).body());
        }
        try (AssetServer defaults = new AssetServer(NO_FILES, DESCRIBE, 0)) {
            byte[] over = new MultipartParserTest.Body().file("f", "big.bin", "application/octet-stream", new byte[ResourceOptions.DEFAULT_MAX_REQUEST_BYTES]).done();
            assertEquals(413, upload(defaults, over, "X-Jolt-Token", defaults.token()).statusCode());
        }
    }

    @Test
    void aMuchTooLargeBodyStillGetsACleanFourThirteenNotADroppedConnection() throws Exception {
        try (AssetServer server = new AssetServer(NO_FILES, DESCRIBE, ResourceOptions.defaults().withMaxRequestBytes(1000), 0)) {
            byte[] huge = new MultipartParserTest.Body().file("f", "huge.bin", "application/octet-stream", new byte[20 * 1024 * 1024]).done();
            assertEquals(413, upload(server, huge, "X-Jolt-Token", server.token()).statusCode());
            // and the server is fine afterwards
            assertEquals(200, upload(server, new MultipartParserTest.Body().field("a", "1").done(), "X-Jolt-Token", server.token()).statusCode());
        }
    }

    @Test
    void theInitScriptAddsTheFieldToMultipartFormsToo() throws Exception {
        try (AssetServer server = new AssetServer(NO_FILES, DESCRIBE, 0)) {
            assertTrue(server.initScript().contains("'multipart/form-data'"));
        }
    }

    @Test
    void resourceOptionsValidateTheirLimit() {
        assertEquals(ResourceOptions.DEFAULT_MAX_REQUEST_BYTES, ResourceOptions.defaults().maxRequestBytes());
        assertEquals(0, new ResourceOptions(0).maxRequestBytes());
        assertThrows(IllegalArgumentException.class, () -> new ResourceOptions(-1));
        assertThrows(IllegalArgumentException.class, () -> new ResourceOptions(ResourceOptions.LIMIT_MAX_REQUEST_BYTES + 1));
        assertEquals(5, ResourceOptions.defaults().withMaxRequestBytes(5).maxRequestBytes());
    }
}
