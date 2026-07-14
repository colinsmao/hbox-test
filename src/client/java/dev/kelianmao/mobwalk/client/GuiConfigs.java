package dev.kelianmao.mobwalk.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.IConfigGuiAllTab;
import fi.dy.masa.malilib.util.StringUtils;

import dev.kelianmao.mobwalk.MobWalk;

/** MaLiLib settings screen (ModMenu Configure entry). Filter tabs: All / General / Appearance / Debug. */
public final class GuiConfigs extends GuiConfigsBase implements IConfigGuiAllTab {
	private static ConfigGuiTab tab = ConfigGuiTab.ALL;

	public GuiConfigs() {
		super(10, 50, MobWalk.MOD_ID, null, "mobwalk.gui.title.configs");
	}

	@Override
	public void initGui() {
		Configs.refreshDisplayNames();
		super.initGui();
		this.clearOptions();

		int x = 10;
		int y = 26;
		for (ConfigGuiTab t : ConfigGuiTab.values()) {
			x += this.createButton(x, y, -1, t) + 2;
		}
	}

	private int createButton(int x, int y, int width, ConfigGuiTab tab) {
		ButtonGeneric button = new ButtonGeneric(x, y, width, 20, tab.getDisplayName());
		button.setEnabled(GuiConfigs.tab != tab);
		this.addButton(button, new ButtonListener(tab, this));
		return button.getWidth();
	}

	@Override
	protected int getConfigWidth() {
		return 200;
	}

	@Override
	public boolean useAllTab() {
		return true;
	}

	@Override
	public List<ConfigOptionWrapper> getAllConfigs() {
		List<ConfigOptionWrapper> configs = new ArrayList<>();
		configs.addAll(ConfigOptionWrapper.createFor(Configs.Generic.OPTIONS));
		configs.addAll(ConfigOptionWrapper.createFor(Configs.Appearance.OPTIONS));
		configs.addAll(ConfigOptionWrapper.createFor(Configs.Debug.OPTIONS));
		return configs;
	}

	@Override
	public List<ConfigOptionWrapper> getConfigs() {
		if (tab == ConfigGuiTab.ALL && this.useAllTab()) {
			return this.getAllConfigs();
		}
		if (tab == ConfigGuiTab.GENERAL) {
			return ConfigOptionWrapper.createFor(Configs.Generic.OPTIONS);
		}
		if (tab == ConfigGuiTab.APPEARANCE) {
			return ConfigOptionWrapper.createFor(Configs.Appearance.OPTIONS);
		}
		if (tab == ConfigGuiTab.DEBUG) {
			return ConfigOptionWrapper.createFor(Configs.Debug.OPTIONS);
		}
		return Collections.emptyList();
	}

	private record ButtonListener(ConfigGuiTab tab, GuiConfigs parent) implements IButtonActionListener {
		@Override
		public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
			GuiConfigs.tab = this.tab;
			this.parent.reCreateListWidget();
			if (this.parent.getListWidget() != null) {
				this.parent.getListWidget().resetScrollbarPosition();
			}
			this.parent.initGui();
		}
	}

	public enum ConfigGuiTab {
		ALL(IConfigGuiAllTab.getTranslationKey()),
		GENERAL("mobwalk.gui.button.config_gui.general"),
		APPEARANCE("mobwalk.gui.button.config_gui.appearance"),
		DEBUG("mobwalk.gui.button.config_gui.debug");

		private final String translationKey;

		ConfigGuiTab(String translationKey) {
			this.translationKey = translationKey;
		}

		public String getDisplayName() {
			return StringUtils.translate(this.translationKey);
		}
	}
}
