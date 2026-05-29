package com.example.overlay.client;

import com.example.overlay.OverlayMod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import net.minecraft.resources.Identifier;

public final class OverlayClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		OverlayManager.bootstrap();
		HudElementRegistry.attachElementBefore(
			VanillaHudElements.CHAT,
			Identifier.fromNamespaceAndPath(OverlayMod.MOD_ID, "overlay_root"),
			OverlayManager::render
		);
	}
}
