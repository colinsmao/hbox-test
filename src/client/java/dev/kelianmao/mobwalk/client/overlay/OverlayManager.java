package dev.kelianmao.mobwalk.client.overlay;

import java.util.ArrayList;
import java.util.List;


import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class OverlayManager {
  private static final List<Overlay> OVERLAYS = new ArrayList<>();

  // The flood-radius readout (shown on shift+scroll). Owned here so the scroll
  // handler in MobWalkClient can ping it via radiusIndicator().
  private static final RadiusIndicatorOverlay RADIUS_INDICATOR = new RadiusIndicatorOverlay();

  // The flood progress ring. Owned here so the frame driver in
  // CollisionSurfaceOverlay can push each frame's progress at it.
  private static final FloodProgressOverlay FLOOD_PROGRESS = new FloodProgressOverlay();

  private OverlayManager() {
  }

  public static void bootstrap() {
    register(RADIUS_INDICATOR);
    register(FLOOD_PROGRESS);
  }

  public static RadiusIndicatorOverlay radiusIndicator() {
    return RADIUS_INDICATOR;
  }

  public static FloodProgressOverlay floodProgress() {
    return FLOOD_PROGRESS;
  }

  public static void register(Overlay overlay) {
    OVERLAYS.add(overlay);
  }

  public static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
    for (Overlay overlay : OVERLAYS) {
      if (overlay.isVisible()) {
        overlay.render(graphics, delta);
      }
    }
  }
}
