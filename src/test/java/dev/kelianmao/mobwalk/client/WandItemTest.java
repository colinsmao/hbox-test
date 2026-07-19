package dev.kelianmao.mobwalk.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;

/**
 * Pins {@link WandItem} parse / registry lookup / stick fallback.
 * Needs the game classpath (fabric-loader-junit) for {@code BuiltInRegistries.ITEM}.
 */
final class WandItemTest {
  @BeforeAll
  static void bootstrapRegistries() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  @Test
  void validVanillaId() {
    assertTrue(WandItem.isValid("minecraft:stick"));
    assertEquals(Items.STICK, WandItem.resolve("minecraft:stick"));
  }

  @Test
  void barePathDefaultsToMinecraftNamespace() {
    assertTrue(WandItem.isValid("stick"));
    assertEquals(Items.STICK, WandItem.resolve("stick"));
  }

  @Test
  void blazeRodResolves() {
    assertTrue(WandItem.isValid("minecraft:blaze_rod"));
    assertEquals(Items.BLAZE_ROD, WandItem.resolve("minecraft:blaze_rod"));
  }

  @Test
  void badSyntaxFallsBackToStick() {
    assertFalse(WandItem.isValid("minecraft::stick"));
    assertEquals(Items.STICK, WandItem.resolve("minecraft::stick"));
  }

  @Test
  void unknownIdFallsBackToStick() {
    assertFalse(WandItem.isValid("not_an_item"));
    assertEquals(Items.STICK, WandItem.resolve("not_an_item"));
  }

  @Test
  void nullAndBlankFallBackToStick() {
    assertFalse(WandItem.isValid(null));
    assertFalse(WandItem.isValid(""));
    assertEquals(Items.STICK, WandItem.resolve(null));
    assertEquals(Items.STICK, WandItem.resolve(""));
  }
}
