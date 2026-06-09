package com.example.overlay.client;

import com.example.overlay.OverlayMod;
import com.example.overlay.client.widgets.CollisionSurfaceOverlay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents;

import net.minecraft.resources.Identifier;

public final class OverlayClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		OverlayManager.bootstrap();
		WorldOverlayManager.bootstrap();

		// Shift+scroll while holding the stick adjusts the flood radius (and shows
		// the indicator) instead of switching the hotbar slot; returning false
		// cancels the vanilla slot change. Plain scroll is left untouched.
		ClientHotbarScrollEvents.ALLOW.register((inventory, currentSlot, newSlot, xOffset, yOffset) -> {
			CollisionSurfaceOverlay collision = WorldOverlayManager.collisionSurface();
			if (collision == null || !collision.wantsRadiusScroll()) {
				return true;
			}
			int radius = collision.adjustRadius(yOffset > 0 ? 1 : -1);
			OverlayManager.radiusIndicator().show(radius);
			return false;
		});

		HudElementRegistry.attachElementBefore(
			VanillaHudElements.CHAT,
			Identifier.fromNamespaceAndPath(OverlayMod.MOD_ID, "overlay_root"),
			OverlayManager::render
		);
	}
}
