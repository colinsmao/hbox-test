package dev.kelianmao.mobwalk.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * HUD and chat messages resolve through the lang file at runtime, so a missing key
 * or a lost {@code %s} shows up as a raw key or a dropped number on screen instead
 * of failing the compile. These asserts stand in for that missing compile check.
 */
final class LangKeysTest {
  private static final String LANG_PATH = "/assets/mobwalk/lang/en_us.json";

  private static JsonObject lang;

  @BeforeAll
  static void loadLang() throws IOException {
    try (InputStream in = LangKeysTest.class.getResourceAsStream(LANG_PATH)) {
      assertNotNull(in, LANG_PATH + " is missing from the client resources");
      lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
        .getAsJsonObject();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "mobwalk.hud.flood_radius",
    "mobwalk.hud.profile",
    "mobwalk.hud.no_profiles_active",
    "mobwalk.command.dump.no_selection",
    "mobwalk.command.dump.counts"
  })
  void messageKeyIsTranslated(String key) {
    assertTrue(lang.has(key), key + " is missing from en_us.json");
    assertFalse(lang.get(key).getAsString().isBlank(), key + " is blank");
  }

  @Test
  void formattedMessagesKeepTheirPlaceholders() {
    assertEquals(1, placeholderCount("mobwalk.hud.flood_radius"));
    assertEquals(1, placeholderCount("mobwalk.hud.profile"));
    assertEquals(5, placeholderCount("mobwalk.command.dump.counts"));
  }

  private static int placeholderCount(String key) {
    return lang.get(key).getAsString().split("%s", -1).length - 1;
  }
}
