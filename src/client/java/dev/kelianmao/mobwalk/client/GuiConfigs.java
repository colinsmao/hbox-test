package dev.kelianmao.mobwalk.client;

import java.util.List;

import fi.dy.masa.malilib.gui.GuiConfigsBase;

import dev.kelianmao.mobwalk.MobWalk;

/** MaLiLib settings screen (ModMenu Configure entry). */
public final class GuiConfigs extends GuiConfigsBase {
	public GuiConfigs() {
		super(10, 50, MobWalk.MOD_ID, null, "mobwalk.gui.title.configs");
	}

	@Override
	protected int getConfigWidth() {
		return 200;
	}

	@Override
	public List<ConfigOptionWrapper> getConfigs() {
		return ConfigOptionWrapper.createFor(Configs.Generic.OPTIONS);
	}
}
