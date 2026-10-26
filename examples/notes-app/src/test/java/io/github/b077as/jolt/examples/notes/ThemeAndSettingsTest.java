package io.github.b077as.jolt.examples.notes;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.b077as.jolt.ColorScheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThemeAndSettingsTest {

    @TempDir
    Path dir;

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void parsesAValidRequest() {
        ThemeRequest r = ThemeRequest.parse(json("{\"preference\":\"system\",\"scheme\":\"dark\",\"background\":\"#0f1117\",\"text\":\"#E7E9EE\"}"));
        assertEquals("system", r.preference());
        assertEquals(ColorScheme.DARK, r.scheme());
        assertEquals(0x0F1117, r.background());
        assertEquals(0xE7E9EE, r.text());
    }

    @Test
    void rejectsEverythingThatIsNotExactlyWhatWeExpect() {
        String ok = "\"preference\":\"light\",\"scheme\":\"light\",\"background\":\"#ffffff\",\"text\":\"#000000\"";
        assertEquals(ColorScheme.LIGHT, ThemeRequest.parse(json("{" + ok + "}")).scheme());

        String[] bad = {
                "{}",
                "{\"preference\":\"purple\",\"scheme\":\"light\",\"background\":\"#ffffff\",\"text\":\"#000000\"}",
                "{\"preference\":\"light\",\"scheme\":\"sepia\",\"background\":\"#ffffff\",\"text\":\"#000000\"}",
                "{\"preference\":\"light\",\"scheme\":\"light\",\"background\":\"ffffff\",\"text\":\"#000000\"}",
                "{\"preference\":\"light\",\"scheme\":\"light\",\"background\":\"#fff\",\"text\":\"#000000\"}",
                "{\"preference\":\"light\",\"scheme\":\"light\",\"background\":\"#ffffff\",\"text\":\"#00000g\"}",
                "{\"preference\":\"light\",\"scheme\":\"light\",\"background\":\"#ffffff\",\"text\":\"red\"}",
                "{\"preference\":\"light\",\"scheme\":\"light\",\"background\":7,\"text\":\"#000000\"}",
                "{\"preference\":\"light\",\"scheme\":\"light\",\"background\":\"#ffffff\\n\",\"text\":\"#000000\"}",
        };
        for (String request : bad) {
            assertThrows(IllegalArgumentException.class, () -> ThemeRequest.parse(json(request)), request);
        }
    }

    @Test
    void settingsDefaultToTheSystemTheme() {
        SettingsStore.Settings s = new SettingsStore(dir.resolve("settings.json")).get();
        assertEquals("system", s.preference());
        assertNull(s.scheme());
    }

    @Test
    void settingsSurviveARestart() {
        Path file = dir.resolve("nested").resolve("settings.json");
        new SettingsStore(file).save(new SettingsStore.Settings("dark", "dark", 0x0F1117, 0xE7E9EE));
        SettingsStore.Settings s = new SettingsStore(file).get();
        assertEquals(new SettingsStore.Settings("dark", "dark", 0x0F1117, 0xE7E9EE), s);
    }

    @Test
    void aDamagedSettingsFileFallsBackToDefaultsInsteadOfStoppingTheApp() throws Exception {
        for (String content : new String[] {"", "not json", "[]", "{\"preference\":\"neon\"}"}) {
            Path file = dir.resolve("broken.json");
            Files.writeString(file, content);
            assertEquals("system", new SettingsStore(file).get().preference(), "content: " + content);
        }
    }
}
