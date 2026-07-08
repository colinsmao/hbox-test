package dev.kelianmao.mobwalk.client;

import java.util.ArrayList;
import java.util.List;

import dev.kelianmao.mobwalk.client.widgets.RadiusIndicatorOverlay;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class OverlayManager {
	private static final List<Overlay> OVERLAYS = new ArrayList<>();

	// The flood-radius readout (shown on shift+scroll). Owned here so the scroll
	// handler in MobWalkClient can ping it via radiusIndicator().
	private static final RadiusIndicatorOverlay RADIUS_INDICATOR = new RadiusIndicatorOverlay();

	private OverlayManager() {
	}

	public static void bootstrap() {
		register(RADIUS_INDICATOR);
	}

	public static RadiusIndicatorOverlay radiusIndicator() {
		return RADIUS_INDICATOR;
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
