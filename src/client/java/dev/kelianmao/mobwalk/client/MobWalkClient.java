package dev.kelianmao.mobwalk.client;

import dev.kelianmao.mobwalk.MobWalk;
import dev.kelianmao.mobwalk.client.widgets.CollisionSurfaceOverlay;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class MobWalkClient implements ClientModInitializer {
	// Debug-only marker style names (matched to CollisionSurfaceOverlay's style
	// indices), shown on the HUD when the occluder-style key cycles.
	private static final String[] OCCLUDER_STYLE_NAMES = {"tiny", "half", "full", "bold"};

	@Override
	public void onInitializeClient() {
		OverlayManager.bootstrap();
		WorldOverlayManager.bootstrap();

		// Standalone debug key (default K): increments the occluder-marker style
		// (tiny / half-block / full / bold-line, wrapping) so the final look can be
		// A/B'd in-game. Not tied to the scroll/use handlers; a pure render-thread
		// choice, so it does not re-flood.
		KeyMapping occluderStyleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.mobwalk.occluder_style",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_K,
			KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			int style = -1;
			while (occluderStyleKey.consumeClick()) {
				CollisionSurfaceOverlay collision = WorldOverlayManager.collisionSurface();
				if (collision != null) {
					style = collision.cycleOccluderStyle();
				}
			}
			if (style >= 0 && style < OCCLUDER_STYLE_NAMES.length) {
				OverlayManager.radiusIndicator().showProfile("occluder " + OCCLUDER_STYLE_NAMES[style]);
			}
		});

		// Standalone key (default V): toggles whether standable tops are drawn on the
		// block's visible face (soul sand, mud, ...) or at their true collision height.
		// Unlike the occluder-style key this re-floods (the visible top is gathered
		// compute-side and gated on the flag), so it goes through the overlay's
		// toggleVisualTop(); toggling is rare, so the recompute is a non-issue.
		KeyMapping surfaceHeightKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.mobwalk.surface_height",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_V,
			KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean toggled = false;
			boolean visible = false;
			while (surfaceHeightKey.consumeClick()) {
				CollisionSurfaceOverlay collision = WorldOverlayManager.collisionSurface();
				if (collision != null) {
					visible = collision.toggleVisualTop();
					toggled = true;
				}
			}
			if (toggled) {
				OverlayManager.radiusIndicator().showProfile("surface: " + (visible ? "visible" : "collision"));
			}
		});

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
			Identifier.fromNamespaceAndPath(MobWalk.MOD_ID, "overlay_root"),
			OverlayManager::render
		);
	}
}
