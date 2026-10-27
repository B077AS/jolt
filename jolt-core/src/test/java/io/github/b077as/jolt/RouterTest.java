package io.github.b077as.jolt;

import io.github.b077as.jolt.internal.ResourceService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterTest {

    private static AppRequest request(String method, String path) {
        return new AppRequest(method, path, "", Map.of(), new byte[0]);
    }

    private static AppRequest request(String method, String path, String body) {
        return new AppRequest(method, path, "", Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(AppResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static String call(Router router, String method, String path) throws Exception {
        AppResponse response = router.handle(request(method, path));
        return response == null ? null : text(response);
    }

    // ---- matching ----

    @Test
    void literalRoutesMatchTheirMethodAndPath() throws Exception {
        Router router = Router.create()
                .get("/", r -> AppResponse.text("home"))
                .get("/notes", r -> AppResponse.text("list"))
                .post("/notes", r -> AppResponse.text("created:" + r.bodyText()))
                .put("/notes", r -> AppResponse.text("put"))
                .patch("/notes", r -> AppResponse.text("patch"))
                .delete("/notes", r -> AppResponse.text("deleted"));
        assertEquals("home", call(router, "GET", "/"));
        assertEquals("list", call(router, "GET", "/notes"));
        assertEquals("created:x", text(router.handle(request("POST", "/notes", "x"))));
        assertEquals("put", call(router, "PUT", "/notes"));
        assertEquals("patch", call(router, "PATCH", "/notes"));
        assertEquals("deleted", call(router, "DELETE", "/notes"));
    }

    @Test
    void pathParametersAreCapturedAndDecoded() throws Exception {
        Router router = Router.create().get("/users/{user}/notes/{id}", r -> AppResponse.text(r.param("user") + "|" + r.param("id")));
        assertEquals("ann|7", call(router, "GET", "/users/ann/notes/7"));
        assertEquals("Ann Lee|é", call(router, "GET", "/users/Ann Lee/notes/é"));
        assertNull(call(router, "GET", "/users/ann/notes"));
        assertNull(call(router, "GET", "/users/ann/notes/7/extra"));
    }

    @Test
    void aMissingParameterIsNull() throws Exception {
        Router router = Router.create().get("/x/{id}", r -> AppResponse.text(String.valueOf(r.param("other"))));
        assertEquals("null", call(router, "GET", "/x/1"));
    }

    @Test
    void literalsAreCaseSensitive() throws Exception {
        Router router = Router.create().get("/Notes", r -> AppResponse.text("n"));
        assertEquals("n", call(router, "GET", "/Notes"));
        assertNull(call(router, "GET", "/notes"));
    }

    @Test
    void trailingSlashesMakeNoDifference() throws Exception {
        Router router = Router.create().get("/notes/", r -> AppResponse.text("n")).get("/a/{id}", r -> AppResponse.text("a" + r.param("id")));
        assertEquals("n", call(router, "GET", "/notes"));
        assertEquals("n", call(router, "GET", "/notes/"));
        assertEquals("a1", call(router, "GET", "/a/1/"));
    }

    @Test
    void aRestParameterTakesTheRemainingSegments() throws Exception {
        Router router = Router.create().get("/files/{*path}", r -> AppResponse.text("[" + r.param("path") + "]"));
        assertEquals("[a]", call(router, "GET", "/files/a"));
        assertEquals("[a/b/c.txt]", call(router, "GET", "/files/a/b/c.txt"));
        assertEquals("[]", call(router, "GET", "/files"));
        assertEquals("[]", call(router, "GET", "/files/"));
        assertNull(call(router, "GET", "/other/a"));
    }

    @Test
    void theMostSpecificRouteWinsWhateverTheOrder() throws Exception {
        Router router = Router.create()
                .get("/files/{*rest}", r -> AppResponse.text("rest"))
                .get("/files/{name}", r -> AppResponse.text("param"))
                .get("/files/readme", r -> AppResponse.text("literal"));
        assertEquals("literal", call(router, "GET", "/files/readme"));
        assertEquals("param", call(router, "GET", "/files/other"));
        assertEquals("rest", call(router, "GET", "/files/a/b"));

        Router deeper = Router.create()
                .get("/a/{x}/c", r -> AppResponse.text("param-then-literal"))
                .get("/a/b/{y}", r -> AppResponse.text("literal-then-param"));
        // decided from the left: the literal second segment beats the parameter
        assertEquals("literal-then-param", call(deeper, "GET", "/a/b/c"));
    }

    @Test
    void routesOfOtherMethodsAreNotMixedUp() throws Exception {
        Router router = Router.create().get("/x", r -> AppResponse.text("get")).post("/x", r -> AppResponse.text("post"));
        assertEquals("get", call(router, "GET", "/x"));
        assertEquals("post", call(router, "POST", "/x"));
    }

    // ---- fall-through, 405 and HEAD ----

    @Test
    void unmatchedRequestsFallThroughToTheFiles() throws Exception {
        Router router = Router.create().get("/api/ping", r -> AppResponse.text("pong"));
        assertNull(router.handle(request("GET", "/css/app.css")));
        assertNull(router.handle(request("GET", "/")));
        assertNull(router.handle(request("POST", "/css/app.css")));
    }

    @Test
    void aWrongMethodOnAKnownPathGets405WithAllowForChangingMethods() throws Exception {
        Router router = Router.create().get("/notes", r -> AppResponse.text("l")).post("/notes", r -> AppResponse.text("c"));
        AppResponse put = router.handle(request("PUT", "/notes"));
        assertEquals(405, put.status());
        assertEquals("GET, HEAD, POST", put.headers().get("Allow"));
        assertEquals(405, router.handle(request("DELETE", "/notes")).status());
    }

    @Test
    void aGetOnAPathWithOnlyOtherMethodsFallsThroughSoItDoesNotHideAFile() throws Exception {
        Router router = Router.create().post("/save", r -> AppResponse.text("saved"));
        assertNull(router.handle(request("GET", "/save")));
        assertNull(router.handle(request("HEAD", "/save")));
    }

    @Test
    void headUsesTheGetRouteUnlessThereIsAHeadRoute() throws Exception {
        Router router = Router.create().get("/a", r -> AppResponse.text("get:" + r.method()));
        assertEquals("get:HEAD", call(router, "HEAD", "/a"));
        Router explicit = Router.create().get("/a", r -> AppResponse.text("get")).route("HEAD", "/a", r -> AppResponse.text("head"));
        assertEquals("head", call(explicit, "HEAD", "/a"));
        assertEquals("get", call(explicit, "GET", "/a"));
    }

    // ---- groups ----

    @Test
    void groupsPrefixTheirRoutesAndMayHaveParameters() throws Exception {
        Router router = Router.create()
                .group("/api", api -> api
                        .get("/ping", r -> AppResponse.text("pong"))
                        .get("/", r -> AppResponse.text("root")))
                .group("/orgs/{org}", org -> org.get("/members/{id}", r -> AppResponse.text(r.param("org") + ":" + r.param("id"))));
        assertEquals("pong", call(router, "GET", "/api/ping"));
        assertEquals("root", call(router, "GET", "/api"));
        assertEquals("acme:5", call(router, "GET", "/orgs/acme/members/5"));
        assertNull(call(router, "GET", "/ping"));
    }

    @Test
    void groupsNest() throws Exception {
        Router router = Router.create().group("/a", a -> a.group("/b", b -> b.get("/c", r -> AppResponse.text("abc"))));
        assertEquals("abc", call(router, "GET", "/a/b/c"));
    }

    @Test
    void aGroupFilterAppliesOnlyToItsOwnRoutes() throws Exception {
        List<String> seen = new ArrayList<>();
        Router router = Router.create()
                .get("/public", r -> AppResponse.text("public"))
                .group("/admin", admin -> admin
                        .get("/users", r -> AppResponse.text("users"))
                        .before(r -> {
                            seen.add(r.path());
                            return r.header("x-admin") == null ? AppResponse.status(403) : null;
                        }));
        assertEquals("public", call(router, "GET", "/public"));
        assertTrue(seen.isEmpty());
        assertEquals(403, router.handle(request("GET", "/admin/users")).status());
        AppRequest admin = new AppRequest("GET", "/admin/users", "", Map.of("x-admin", "1"), new byte[0]);
        assertEquals("users", text(router.handle(admin)));
        assertEquals(List.of("/admin/users", "/admin/users"), seen);
    }

    @Test
    void nestedGroupFiltersRunOuterFirst() throws Exception {
        List<String> order = new ArrayList<>();
        Router router = Router.create().group("/a", a -> a
                .before(r -> {
                    order.add("outer");
                    return null;
                })
                .group("/b", b -> b
                        .before(r -> {
                            order.add("inner");
                            return null;
                        })
                        .get("/c", r -> {
                            order.add("route");
                            return AppResponse.text("ok");
                        })));
        assertEquals("ok", call(router, "GET", "/a/b/c"));
        assertEquals(List.of("outer", "inner", "route"), order);
    }

    // ---- filters ----

    @Test
    void globalFiltersRunInOrderBeforeTheRouteAndMayStopTheRequest() throws Exception {
        List<String> order = new ArrayList<>();
        Router router = Router.create()
                .before(r -> {
                    order.add("first");
                    return null;
                })
                .before(r -> {
                    order.add("second");
                    return r.path().equals("/blocked") ? AppResponse.redirect("/login") : null;
                })
                .get("/blocked", r -> {
                    order.add("route");
                    return AppResponse.text("never");
                })
                .get("/open", r -> {
                    order.add("route");
                    return AppResponse.text("open");
                });
        AppResponse blocked = router.handle(request("GET", "/blocked"));
        assertEquals(303, blocked.status());
        assertEquals(List.of("first", "second"), order);
        order.clear();
        assertEquals("open", call(router, "GET", "/open"));
        assertEquals(List.of("first", "second", "route"), order);
    }

    @Test
    void filtersSeeThePathParametersAndDoNotRunForFilesOr405() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        List<String> ids = new ArrayList<>();
        Router router = Router.create()
                .before(r -> {
                    runs.incrementAndGet();
                    ids.add(r.param("id"));
                    return null;
                })
                .get("/n/{id}", r -> AppResponse.text("n"));
        call(router, "GET", "/n/9");
        assertEquals(List.of("9"), ids);
        assertNull(router.handle(request("GET", "/css/app.css")));
        assertEquals(405, router.handle(request("POST", "/n/9")).status());
        assertEquals(1, runs.get());
    }

    // ---- errors ----

    @Test
    void withoutAnErrorHandlerExceptionsPropagateForJoltToAnswer500() {
        Router router = Router.create().get("/boom", r -> {
            throw new IllegalStateException("boom");
        });
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> router.handle(request("GET", "/boom")));
        assertEquals("boom", thrown.getMessage());
    }

    @Test
    void anErrorHandlerTurnsExceptionsFromRoutesAndFiltersIntoResponses() throws Exception {
        Router router = Router.create()
                .before(r -> {
                    if (r.path().equals("/filter")) {
                        throw new IOException("filter failed");
                    }
                    return null;
                })
                .get("/boom", r -> {
                    throw new IllegalStateException("route failed");
                })
                .get("/filter", r -> AppResponse.text("never"))
                .onError((r, e) -> AppResponse.html("<p>" + e.getMessage() + "</p>").withStatus(500));
        AppResponse route = router.handle(request("GET", "/boom"));
        assertEquals(500, route.status());
        assertEquals("<p>route failed</p>", text(route));
        assertEquals("<p>filter failed</p>", text(router.handle(request("GET", "/filter"))));
    }

    @Test
    void anErrorHandlerThatReturnsNullLeavesTheOriginalException() {
        Router router = Router.create()
                .get("/boom", r -> {
                    throw new IllegalStateException("original");
                })
                .onError((r, e) -> null);
        assertEquals("original", assertThrows(IllegalStateException.class, () -> router.handle(request("GET", "/boom"))).getMessage());
    }

    @Test
    void anErrorHandlerThatThrowsKeepsBothFailures() {
        Router router = Router.create()
                .get("/boom", r -> {
                    throw new IllegalStateException("original");
                })
                .onError((r, e) -> {
                    throw new IOException("handler failed");
                });
        IOException thrown = assertThrows(IOException.class, () -> router.handle(request("GET", "/boom")));
        assertEquals("handler failed", thrown.getMessage());
        assertEquals("original", thrown.getSuppressed()[0].getMessage());
    }

    @Test
    void unmatchedRequestsAreNotErrors() throws Exception {
        Router router = Router.create().onError((r, e) -> AppResponse.text("error"));
        assertNull(router.handle(request("GET", "/anything")));
    }

    // ---- registration rules ----

    @Test
    void badPatternsAreRejectedWhenRegistered() {
        Router router = Router.create();
        Router.Route route = r -> null;
        assertThrows(IllegalArgumentException.class, () -> router.get("notes", route));
        assertThrows(IllegalArgumentException.class, () -> router.get("", route));
        assertThrows(IllegalArgumentException.class, () -> router.get(null, route));
        assertThrows(IllegalArgumentException.class, () -> router.get("/a//b", route));
        assertThrows(IllegalArgumentException.class, () -> router.get("/a/{}", route));
        assertThrows(IllegalArgumentException.class, () -> router.get("/a/{1x}", route));
        assertThrows(IllegalArgumentException.class, () -> router.get("/a/{x-y}", route));
        assertThrows(IllegalArgumentException.class, () -> router.get("/a/pre{id}", route));
        assertThrows(IllegalArgumentException.class, () -> router.get("/a/{id", route));
        assertThrows(IllegalArgumentException.class, () -> router.get("/a/{*rest}/b", route));
        assertThrows(IllegalArgumentException.class, () -> router.get("/a/{id}/b/{id}", route));
        assertThrows(IllegalArgumentException.class, () -> router.get("/a", null));
        assertThrows(IllegalArgumentException.class, () -> router.route("TRACE", "/a", route));
        assertThrows(IllegalArgumentException.class, () -> router.route(null, "/a", route));
        assertThrows(IllegalArgumentException.class, () -> router.before(null));
    }

    @Test
    void duplicateRoutesAreRejectedEvenWithDifferentParameterNames() {
        Router router = Router.create().get("/a/{x}", r -> null).post("/a/{x}", r -> null);
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> router.get("/a/{y}", r -> null));
        assertTrue(thrown.getMessage().contains("duplicate route"), thrown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> router.get("/a/{y}/", r -> null));
        router.get("/a/b", r -> null); // a plain segment is a different shape
        router.route("head", "/a/{x}", r -> null); // method names are case-insensitive, and HEAD is its own route
    }

    @Test
    void groupPrefixesAreValidatedAndCannotCollideWithParameters() {
        Router router = Router.create();
        assertThrows(IllegalArgumentException.class, () -> router.group("/a/{*rest}", g -> g.get("/b", r -> null)));
        assertThrows(IllegalArgumentException.class, () -> router.group("a", g -> g.get("/b", r -> null)));
        assertThrows(IllegalArgumentException.class, () -> router.group("/orgs/{id}", g -> g.get("/{id}", r -> null)));
        Router duplicates = Router.create().get("/api/ping", r -> null);
        assertThrows(IllegalArgumentException.class, () -> duplicates.group("/api", g -> g.get("/ping", r -> null)));
    }

    @Test
    void methodNamesAreCaseInsensitiveWhenRegistering() throws Exception {
        Router router = Router.create().route("post", "/x", r -> AppResponse.text("posted"));
        assertEquals("posted", call(router, "POST", "/x"));
    }

    // ---- other ----

    @Test
    void requestsWithoutRouteParametersHaveNone() throws Exception {
        AppRequest plain = request("GET", "/x");
        assertTrue(plain.pathParams().isEmpty());
        assertNull(plain.param("id"));
        AppRequest routed = plain.withPathParams(Map.of("id", "3"));
        assertEquals("3", routed.param("id"));
        assertTrue(plain.pathParams().isEmpty(), "the original is unchanged");
        assertNotNull(new AppRequest("GET", "/x", "", Map.of(), new byte[0], null).pathParams());
    }

    @Test
    void theRouterWorksAsTheHandlerOfAResourceServiceAndFilesStillAnswer() {
        Router router = Router.create()
                .get("/hello/{name}", r -> AppResponse.html("<b>" + r.param("name") + "</b>"))
                .post("/echo", r -> AppResponse.text(r.formValue("a")));
        ResourceService service = new ResourceService(
                path -> path.equals("index.html") ? "<h1>home</h1>".getBytes(StandardCharsets.UTF_8) : null, router);
        assertEquals("<b>Ann</b>", text(service.serve("GET", "jolt://app/hello/Ann", Map.of(), new byte[0])));
        assertEquals("<h1>home</h1>", text(service.serve("GET", "jolt://app/", Map.of(), new byte[0])));
        assertEquals(404, service.serve("GET", "jolt://app/missing", Map.of(), new byte[0]).status());
        assertEquals("1", text(service.serve("POST", "jolt://app/echo", Map.of(), "a=1".getBytes(StandardCharsets.UTF_8))));
        AppResponse notAllowed = service.serve("DELETE", "jolt://app/echo", Map.of(), new byte[0]);
        assertEquals(405, notAllowed.status());
        assertEquals("POST", notAllowed.headers().get("Allow"));
    }

    @Test
    void aRouterIsSafeToUseFromManyThreadsAtOnce() throws Exception {
        Router router = Router.create()
                .get("/n/{id}", r -> AppResponse.text(r.param("id")))
                .group("/g", g -> g.before(r -> null).get("/x/{id}", r -> AppResponse.text("g" + r.param("id"))));
        int threads = 16;
        int perThread = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            results.add(pool.submit(() -> {
                start.await();
                int wrong = 0;
                for (int i = 0; i < perThread; i++) {
                    String expected = id + "-" + i;
                    AppResponse a = router.handle(request("GET", "/n/" + expected));
                    AppResponse b = router.handle(request("GET", "/g/x/" + expected));
                    if (!expected.equals(text(a)) || !("g" + expected).equals(text(b))) {
                        wrong++;
                    }
                }
                return wrong;
            }));
        }
        start.countDown();
        for (Future<Integer> result : results) {
            assertEquals(0, result.get());
        }
        pool.shutdown();
        assertSame(router, router.get("/late", r -> AppResponse.text("late")));
    }
}
