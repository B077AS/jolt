package io.github.b077as.jolt.examples.notes;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.b077as.jolt.ColorScheme;

import java.util.regex.Pattern;

/**
 * What the page sends to restyle the native window: {@code {preference, scheme, background, text}}.
 *
 * <p>The page is untrusted input, so every field is checked; a bad value fails the request instead of reaching the
 * operating system.
 */
record ThemeRequest(String preference, ColorScheme scheme, int background, int text) {

    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    static ThemeRequest parse(JsonObject params) {
        String preference = string(params, "preference");
        if (!preference.equals("system") && !preference.equals("light") && !preference.equals("dark")) {
            throw new IllegalArgumentException("preference must be system, light or dark: " + preference);
        }
        ColorScheme scheme = switch (string(params, "scheme")) {
            case "light" -> ColorScheme.LIGHT;
            case "dark" -> ColorScheme.DARK;
            default -> throw new IllegalArgumentException("scheme must be light or dark");
        };
        return new ThemeRequest(preference, scheme, color(params, "background"), color(params, "text"));
    }

    private static String string(JsonObject params, String key) {
        JsonElement value = params.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " is required and must be text");
        }
        return value.getAsString();
    }

    private static int color(JsonObject params, String key) {
        String hex = string(params, key);
        if (!HEX_COLOR.matcher(hex).matches()) {
            throw new IllegalArgumentException(key + " must look like #1a2b3c: " + hex);
        }
        return Integer.parseInt(hex.substring(1), 16);
    }
}
