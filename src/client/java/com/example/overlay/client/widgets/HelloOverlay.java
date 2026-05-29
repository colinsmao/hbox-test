package com.example.overlay.client.widgets;

import com.example.overlay.client.Overlay;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class HelloOverlay implements Overlay {
	private static final int MARGIN = 8;
	private static final int BOX_WIDTH = 120;
	private static final int BOX_HEIGHT = 22;
	private static final int BOX_COLOR = 0x99000000;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final String LABEL = "Graphics Overlay";

	@Override
	public String id() {
		return "hello";
	}

	@Override
	public void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Font font = Minecraft.getInstance().font;
		graphics.fill(MARGIN, MARGIN, MARGIN + BOX_WIDTH, MARGIN + BOX_HEIGHT, BOX_COLOR);
		graphics.text(font, LABEL, MARGIN + 6, MARGIN + 7, TEXT_COLOR, true);
	}
}
