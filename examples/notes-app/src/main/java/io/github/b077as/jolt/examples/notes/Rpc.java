package io.github.b077as.jolt.examples.notes;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.b077as.jolt.WebWindow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Request/response on top of Jolt's plain string messages.
 *
 * <p>The page sends {@code {"id": 1, "method": "notes.add", "params": {...}}}; this answers with
 * {@code {"id": 1, "result": ...}} or {@code {"id": 1, "error": "..."}}. Handlers run on virtual threads, never on the UI
 * thread, so slow work such as file or network access cannot freeze the window. That is safe because
 * {@link WebWindow#postMessage} may be called from any thread.
 */
final class Rpc {

    @FunctionalInterface
    interface Handler {
        /** @return the value to send back to the page (any Gson-serializable object, or {@code null}) */
        Object handle(JsonObject params) throws Exception;
    }

    private static final Gson GSON = new Gson();

    private final WebWindow window;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    Rpc(WebWindow window) {
        this.window = window;
        window.onMessage(this::onMessage);
        window.onClosed(workers::shutdown);
    }

    Rpc on(String method, Handler handler) {
        handlers.put(method, handler);
        return this;
    }

    /** Runs on the UI thread: parse quickly, then hand the real work to a worker. */
    private void onMessage(String text) {
        JsonObject request;
        try {
            request = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException notJson) {
            return; // some other kind of message; not ours
        }
        if (!request.has("id") || !request.has("method")) {
            return;
        }
        JsonElement id = request.get("id");
        String method = request.get("method").getAsString();
        JsonObject params = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params") : new JsonObject();
        workers.execute(() -> window.postMessage(respond(id, method, params)));
    }

    private String respond(JsonElement id, String method, JsonObject params) {
        JsonObject response = new JsonObject();
        response.add("id", id);
        Handler handler = handlers.get(method);
        if (handler == null) {
            response.addProperty("error", "Unknown method: " + method);
            return GSON.toJson(response);
        }
        try {
            response.add("result", GSON.toJsonTree(handler.handle(params)));
        } catch (Exception e) {
            response.addProperty("error", e.getMessage() != null ? e.getMessage() : e.toString());
        }
        return GSON.toJson(response);
    }
}
