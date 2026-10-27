package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.AppResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetServerTest {

    private static final Map<String, String> FILES = Map.of(
            "index.html", "<h1>home</h1>",
            "css/site.css", "body{}",
            "js/app.mjs", "export default 1",
            "sub/index.html", "<h1>sub</h1>",
            "my file.txt", "spaced");

    private static AppResources files() {
        return path -> FILES.containsKey(path) ? FILES.get(path).getBytes(StandardCharsets.UTF_8) : null;
    }

    private static HttpResponse<String> send(String method, AssetServer server, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.origin() + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesFilesWithTheirContentTypes() throws Exception {
        try (AssetServer server = new AssetServer(files(), null, 0)) {
            HttpResponse<String> root = send("GET", server, "/");
            assertEquals(200, root.statusCode());
            assertEquals("<h1>home</h1>", root.body());
            assertEquals("text/html; charset=utf-8", root.headers().firstValue("Content-Type").orElse(null));
            assertEquals("text/css; charset=utf-8", send("GET", server, "/css/site.css").headers().firstValue("Content-Type").orElse(null));
            assertEquals("text/javascript; charset=utf-8", send("GET", server, "/js/app.mjs").headers().firstValue("Content-Type").orElse(null));
            assertEquals("<h1>sub</h1>", send("GET", server, "/sub/").body());
            assertEquals("spaced", send("GET", server, "/my%20file.txt").body());
            assertEquals("nosniff", root.headers().firstValue("X-Content-Type-Options").orElse(null));
        }
    }

    @Test
    void missingFilesAreNotFoundAndOtherMethodsAreRefused() throws Exception {
        try (AssetServer server = new AssetServer(files(), null, 0)) {
            assertEquals(404, send("GET", server, "/nope.js").statusCode());
            assertEquals(405, send("POST", server, "/").statusCode());
            HttpResponse<String> head = send("HEAD", server, "/");
            assertEquals(200, head.statusCode());
            assertEquals("", head.body());
        }
    }

    @Test
    void aResourceThatThrowsIsAnErrorNotACrash() throws Exception {
        try (AssetServer server = new AssetServer(path -> {
            throw new IOException("disk gone");
        }, null, 0)) {
            assertEquals(500, send("GET", server, "/").statusCode());
        }
    }

    @Test
    void requestsNamingAnotherHostAreRefused() throws Exception {
        try (AssetServer server = new AssetServer(files(), null, 0)) {
            int port = Integer.parseInt(server.origin().substring(server.origin().lastIndexOf(':') + 1));
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                String reply = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertTrue(reply.startsWith("HTTP/1.1 403"), reply);
            }
        }
    }

    @Test
    void normalizeMapsUrlsToResourcePathsAndRejectsEscapes() {
        assertEquals("index.html", AssetServer.normalize(URI.create("/")));
        assertEquals("a/b.js", AssetServer.normalize(URI.create("/a/b.js")));
        assertEquals("a/b.js", AssetServer.normalize(URI.create("/a//./b.js")));
        assertEquals("a/index.html", AssetServer.normalize(URI.create("/a/")));
        assertEquals("a b.txt", AssetServer.normalize(URI.create("/a%20b.txt")));
        assertNull(AssetServer.normalize(URI.create("/../secret")));
        assertNull(AssetServer.normalize(URI.create("/a/%2e%2e/secret")));
        assertNull(AssetServer.normalize(URI.create("/a%5c..%5csecret")));
        assertNull(AssetServer.normalize(URI.create("/a%00.js")));
    }

    @Test
    void directoryResourcesStayInsideTheirFolder(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("ui"));
        Files.writeString(dir.resolve("ui/index.html"), "ok");
        Files.writeString(dir.resolve("secret.txt"), "no");
        AppResources resources = AppResources.directory(dir.resolve("ui"));
        assertEquals("ok", new String(resources.read("index.html"), StandardCharsets.UTF_8));
        assertNull(resources.read("../secret.txt"));
        assertNull(resources.read("missing.html"));
        assertNull(resources.read("."));
    }

    @Test
    void classpathResourcesAreFoundUnderTheirBase() throws Exception {
        AppResources resources = AppResources.classpath("io/github/b077as/jolt/internal");
        assertTrue(resources.read("AssetServer.class") != null || resources.read("AppDirs.class") != null);
        assertNull(resources.read("nope.class"));
    }
}
