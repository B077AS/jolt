package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.internal.MiniJson;

import java.util.Map;

/**
 * Interprets the answer to the DevTools Protocol call {@code Runtime.evaluate}.
 *
 * <p>Scripts run through this instead of {@code ICoreWebView2::ExecuteScript} because ExecuteScript reports a
 * script that throws, or fails to compile, as a successful {@code null}. The protocol reports it in
 * {@code exceptionDetails}, so the caller can fail the {@code eval} the way the other backends do.
 */
final class DevToolsResult {

    private DevToolsResult() {}

    /** The {@code Runtime.evaluate} parameters for {@code script}, evaluated in the page and returned by value. */
    static String evaluateParams(String script) {
        return "{\"expression\":" + io.github.b077as.jolt.internal.JsLiteral.quote(script) + ",\"returnByValue\":true}";
    }

    /**
     * @return the JSON text of the script's completion value, {@code "null"} when it has none (undefined,
     *         functions, symbols and the like, which {@code JSON.stringify} also cannot represent)
     * @throws JoltException with the script's error message when it threw or did not compile
     */
    static String valueOrThrow(String responseJson) {
        Map<String, String> top;
        try {
            top = MiniJson.members(responseJson);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new JoltException("Unreadable DevTools response: " + abbreviate(responseJson), e);
        }
        String exceptionDetails = top.get("exceptionDetails");
        if (exceptionDetails != null) {
            throw new JoltException("Script error: " + describe(exceptionDetails));
        }
        String result = top.get("result");
        if (result == null) {
            throw new JoltException("Unexpected DevTools response: " + abbreviate(responseJson));
        }
        try {
            String value = MiniJson.members(result).get("value");
            return value == null ? "null" : value;
        } catch (IllegalArgumentException e) {
            throw new JoltException("Unreadable DevTools result: " + abbreviate(result), e);
        }
    }

    /** The thrown error's description (message and stack), falling back to the protocol's short text. */
    private static String describe(String exceptionDetails) {
        try {
            Map<String, String> details = MiniJson.members(exceptionDetails);
            String exception = details.get("exception");
            if (exception != null) {
                String description = MiniJson.members(exception).get("description");
                if (description != null) {
                    return MiniJson.decodeString(description);
                }
                String value = MiniJson.members(exception).get("value");
                if (value != null) {
                    return value; // a thrown primitive, for example: throw "oops"
                }
            }
            String text = details.get("text");
            return text != null ? MiniJson.decodeString(text) : abbreviate(exceptionDetails);
        } catch (IllegalArgumentException e) {
            return abbreviate(exceptionDetails);
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "null" : s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
