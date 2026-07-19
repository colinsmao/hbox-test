package dev.kelianmao.mobwalk.client.widgets;

import dev.kelianmao.mobwalk.client.Overlay;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A transient HUD readout shown near the crosshair for a short time after a stick
 * action: the flood selection radius ({@link #show}, shift+scroll) or the active
 * entity profile ({@link #showProfile}, sneak+right-click at nothing). Text only,
 * no background; it fades out and then hides itself. Replaces the original demo
 * HUD box from Milestone 1.
 *
 * <p>{@link #show}/{@link #showProfile} are called on the client thread; the
 * fields they write are read on the render thread in {@link #render}/
 * {@link #isVisible}, so they are {@code volatile}.
 */
public final class RadiusIndicatorOverlay implements Overlay {
  private static final long VISIBLE_MS = 1500L;
  private static final long FADE_MS = 500L;
  private static final int CROSSHAIR_Y_OFFSET = 12;
  private static final int RGB = 0xFFFFFF;

  private volatile String text = "";
  private volatile long expiresAt;

  /** Show the indicator with the given radius, resetting its fade timer. */
  public void show(int radius) {
    showMessage("Flood radius: " + radius);
  }

  /** Show the indicator with the active profile name, resetting its fade timer. */
  public void showProfile(String name) {
    showMessage("Profile: " + name);
  }

  private void showMessage(String message) {
    this.text = message;
    this.expiresAt = System.currentTimeMillis() + VISIBLE_MS;
  }

  @Override
  public String id() {
    return "radius_indicator";
  }

  @Override
  public boolean isVisible() {
    return System.currentTimeMillis() < expiresAt;
  }

  @Override
  public void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
    long remaining = expiresAt - System.currentTimeMillis();
    if (remaining <= 0) {
      return;
    }

    int alpha = remaining >= FADE_MS ? 255 : (int) (255L * remaining / FADE_MS);
    if (alpha <= 0) {
      return;
    }

    Minecraft client = Minecraft.getInstance();
    Font font = client.font;
    String text = this.text;
    int x = (client.getWindow().getGuiScaledWidth() - font.width(text)) / 2;
    int y = client.getWindow().getGuiScaledHeight() / 2 + CROSSHAIR_Y_OFFSET;
    graphics.text(font, text, x, y, (alpha << 24) | RGB, true);
  }
}
