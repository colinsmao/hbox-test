package dev.kelianmao.mobwalk.client.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;

/**
 * Resolves the wand-item config string against {@link BuiltInRegistries#ITEM}.
 * Malformed or unknown ids fall back to {@link Items#STICK} for the wand; the
 * typed string stays in the config field.
 */
public final class WandItem {
  public static final String DEFAULT_ID = "minecraft:stick";

  private static final String INVALID_TOOLTIP_KEY = "mobwalk.gui.text_field.invalid_item_id";

  private WandItem() {}

  /** True when {@code text} parses to a registered item id. */
  public static boolean isValid(String text) {
    if (text == null) {
      return false;
    }
    Identifier id = Identifier.tryParse(text.trim());
    return id != null && BuiltInRegistries.ITEM.containsKey(id);
  }

  /**
   * Item named by {@code text}, or {@link Items#STICK} when the string is
   * malformed or not a registered item.
   */
  public static Item resolve(String text) {
    if (text == null) {
      return Items.STICK;
    }
    Identifier id = Identifier.tryParse(text.trim());
    if (id == null) {
      return Items.STICK;
    }
    return BuiltInRegistries.ITEM.getOptional(id).orElse(Items.STICK);
  }

  /**
   * MaLiLib-style live invalid cue: hover tooltip when the field text is not a
   * registered item id; clear when valid.
   */
  public static void applyInvalidTooltip(GuiTextFieldGeneric field) {
    if (isValid(field.getValue())) {
      field.clearHoverTooltip();
    } else {
      field.setHoverTooltip(INVALID_TOOLTIP_KEY);
    }
  }
}
