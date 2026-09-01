package dev.kelianmao.mobwalk.client.overlay;

import dev.kelianmao.mobwalk.client.config.Configs;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A ring around the crosshair while a flood computes, so a large selection reads
 * as work in progress rather than a stalled wand. The driver pushes
 * {@link #update} every frame a flood is armed and {@link #hide}s otherwise, both
 * from {@code CollisionSurfaceOverlay.extract} — the render thread, the same one
 * {@link #render} runs on, so this needs no cross-thread handoff (unlike
 * {@link RadiusIndicatorOverlay}, whose {@code show} arrives from the client
 * thread).
 *
 * <p>The ring is SEGMENTS exclusive {@code fill}s stepped around a circle,
 * clockwise from 12 o'clock. Centre is {@code (guiWidth-1)/2 + 0.5}, the same
 * integer-odd-box origin as {@code Hud.extractCrosshair}. No texture, no matrix.
 */
public final class FloodProgressOverlay implements Overlay {
  private static final int RADIUS = 8;
  private static final int THICKNESS = 1;
  private static final int SEGMENTS = 64;
  private static final int ARC_ARGB = 0xE0D6D6D6;
  // Expansion owns most of the ring; the finalize passes hold the rest so the
  // sweep keeps moving after the disk is full.
  private static final float EXPANSION_SHARE = 0.75f;

  private boolean armed;
  private float progress;

  /**
   * Show the ring for a flood that has reached {@code expansion} of its disk
   * with {@code passes} of its finalize passes run, both {@code 0f..1f}.
   */
  public void update(float expansion, float passes) {
    float swept = EXPANSION_SHARE * Math.clamp(expansion, 0f, 1f)
      + (1f - EXPANSION_SHARE) * Math.clamp(passes, 0f, 1f);
    progress = Math.clamp(swept, 0f, 1f);
    armed = progress < 1f;
  }

  /** Drop the ring. */
  public void hide() {
    armed = false;
  }

  @Override
  public String id() {
    return "flood_progress";
  }

  @Override
  public boolean isVisible() {
    return armed && Configs.showFloodProgress();
  }

  @Override
  public void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
    // Same integer-odd-box centre as Hud.extractCrosshair: (w-1)/2 + 0.5.
    double cx = (graphics.guiWidth() - 1) / 2 + 0.5;
    double cy = (graphics.guiHeight() - 1) / 2 + 0.5;
    int swept = Math.round(progress * SEGMENTS);
    for (int i = 0; i < swept; i++) {
      // Clockwise from 12 o'clock, and y grows downward, so the sweep starts at
      // -y and turns toward +x.
      double angle = 2 * Math.PI * i / SEGMENTS;
      int px = (int) Math.round(cx + Math.sin(angle) * RADIUS);
      int py = (int) Math.round(cy - Math.cos(angle) * RADIUS);
      graphics.fill(px - THICKNESS, py - THICKNESS, px + THICKNESS, py + THICKNESS, ARC_ARGB);
    }
  }
}
