package dev.kelianmao.mobwalk.client;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;

import dev.kelianmao.mobwalk.MobWalk;

/** Registers MaLiLib config handler + config-screen factory after MaLiLib boots. */
public final class InitHandler implements IInitializationHandler {
  @Override
  public void registerModHandlers() {
    ConfigManager.getInstance().registerConfigHandler(MobWalk.MOD_ID, new Configs());
    Registry.CONFIG_SCREEN.registerConfigScreenFactory(
      new ModInfo(MobWalk.MOD_ID, "MobWalk", GuiConfigs::new)
    );
    Configs.initCallbacks();
  }
}
