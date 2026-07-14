package dev.kelianmao.mobwalk.client;

import java.util.ArrayList;
import java.util.List;

import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.util.StringUtils;

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
		// One scrolling list: General LABEL + Generic options, then Debug LABEL +
		// Debug options (MaLiLib ConfigOptionWrapper.Type.LABEL).
		List<ConfigOptionWrapper> list = new ArrayList<>();
		list.add(new ConfigOptionWrapper(StringUtils.translate("mobwalk.config.general")));
		list.addAll(ConfigOptionWrapper.createFor(Configs.Generic.OPTIONS));
		list.add(new ConfigOptionWrapper(StringUtils.translate("mobwalk.config.debug")));
		list.addAll(ConfigOptionWrapper.createFor(Configs.Debug.OPTIONS));
		return list;
	}
}
