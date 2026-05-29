package com.example.overlay.client;

import java.util.ArrayList;
import java.util.List;

import com.example.overlay.client.widgets.HelloOverlay;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class OverlayManager {
	private static final List<Overlay> OVERLAYS = new ArrayList<>();

	private OverlayManager() {
	}

	public static void bootstrap() {
		register(new HelloOverlay());
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
