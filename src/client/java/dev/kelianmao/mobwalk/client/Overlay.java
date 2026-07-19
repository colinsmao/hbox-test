package dev.kelianmao.mobwalk.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public interface Overlay {
  String id();

  void render(GuiGraphicsExtractor graphics, DeltaTracker delta);

  default boolean isVisible() {
    return true;
  }
}
