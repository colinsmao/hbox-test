package com.example.overlay.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public interface Overlay {
	String id();

	void render(GuiGraphicsExtractor graphics, DeltaTracker delta);

	default boolean isVisible() {
		return true;
	}
}
