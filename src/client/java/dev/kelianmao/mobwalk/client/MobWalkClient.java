package dev.kelianmao.mobwalk.client;

import dev.kelianmao.mobwalk.client.config.Configs;
import dev.kelianmao.mobwalk.client.overlay.OverlayManager;
import dev.kelianmao.mobwalk.client.overlay.WorldOverlayManager;
import dev.kelianmao.mobwalk.client.surface.CollisionSurfaceOverlay.FloodDebugCounts;
import dev.kelianmao.mobwalk.client.surface.CollisionSurfaceOverlay;

import dev.kelianmao.mobwalk.MobWalk;

import fi.dy.masa.malilib.event.InitializationHandler;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class MobWalkClient implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    OverlayManager.bootstrap();
    WorldOverlayManager.bootstrap();
    InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());

    // Flush live options (scroll radius, profile cycle, …) on leave-world /
    // Save and Quit to Title. Config-screen close still saves via MaLiLib.
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Configs.saveToDisk());

    // /mobwalk dump — one-shot flood geometry dump to latest.log + short chat line.
    ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
      dispatcher.register(ClientCommands.literal("mobwalk")
        .then(ClientCommands.literal("dump").executes(ctx -> {
          Minecraft client = Minecraft.getInstance();
          if (client.player == null) {
            return 0;
          }
          CollisionSurfaceOverlay collision = WorldOverlayManager.collisionSurface();
          FloodDebugCounts counts = collision.dumpFloodDebug();
          if (counts == null) {
            client.player.sendSystemMessage(
              Component.literal("flood-debug: no selection"));
          } else {
            client.player.sendSystemMessage(Component.literal(
              "flood-debug: merged=" + counts.merged()
                + " occluders=" + counts.occluders()
                + " skirts=" + counts.skirts()
                + " holes=" + counts.holes()
                + " (see latest.log)"));
          }
          return 1;
        }))));

    // Shift+scroll while holding the stick adjusts the flood radius (and shows
    // the indicator) instead of switching the hotbar slot; returning false
    // cancels the vanilla slot change. Plain scroll is left untouched.
    ClientHotbarScrollEvents.ALLOW.register((inventory, currentSlot, newSlot, xOffset, yOffset) -> {
      CollisionSurfaceOverlay collision = WorldOverlayManager.collisionSurface();
      if (!collision.wantsRadiusScroll()) {
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
