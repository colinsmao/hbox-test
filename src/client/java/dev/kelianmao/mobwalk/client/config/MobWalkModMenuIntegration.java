package dev.kelianmao.mobwalk.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** ModMenu Configure → MaLiLib {@link GuiConfigs}. */
public final class MobWalkModMenuIntegration implements ModMenuApi {
  @Override
  public ConfigScreenFactory<?> getModConfigScreenFactory() {
    return parent -> {
      GuiConfigs gui = new GuiConfigs();
      gui.setParent(parent);
      return gui;
    };
  }
}
