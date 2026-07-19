package dev.kelianmao.mobwalk.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.screens.Screen;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigResettable;
import fi.dy.masa.malilib.config.IConfigValue;
import fi.dy.masa.malilib.config.gui.ConfigOptionChangeListenerTextField;
import fi.dy.masa.malilib.config.gui.ConfigOptionListenerResetConfig;
import fi.dy.masa.malilib.config.options.table.ConfigTable;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.IConfigGui;
import fi.dy.masa.malilib.gui.interfaces.IConfigGuiAllTab;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;
import fi.dy.masa.malilib.gui.wrappers.TextFieldType;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;

import dev.kelianmao.mobwalk.MobWalk;

/**
 * MaLiLib settings screen (ModMenu Configure entry). Filter tabs: All / General /
 * Appearance / Debug. "Edit Built-in Profiles" / "Edit Custom Profiles" open from
 * General. Those two ConfigTable rows use {@link ConfirmResetConfigOption};
 * {@link Configs.Generic#WAND_ITEM} uses {@link ItemIdConfigOption}; other
 * rows use stock {@link WidgetConfigOption}.
 */
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
  protected WidgetListConfigOptions createListWidget(int listX, int listY) {
    return new WidgetListConfigOptions(
      listX,
      listY,
      this.getBrowserWidth(),
      this.getBrowserHeight(),
      this.getConfigWidth(),
      0.0f,
      this.useKeybindSearch(),
      this
    ) {
      @Override
      protected WidgetConfigOption createListEntryWidget(
        int x, int y, int listIndex, boolean isOdd, ConfigOptionWrapper wrapper
      ) {
        IConfigBase config = wrapper.getConfig();
        if (config == Configs.Profiles.BUILTIN_PROFILES
          || config == Configs.Profiles.CUSTOM_PROFILES) {
          return new ConfirmResetConfigOption(
            x,
            y,
            this.browserEntryWidth,
            this.browserEntryHeight,
            this.maxLabelWidth,
            this.configWidth,
            wrapper,
            listIndex,
            this.parent,
            this
          );
        }
        if (config == Configs.Generic.WAND_ITEM) {
          return new ItemIdConfigOption(
            x,
            y,
            this.browserEntryWidth,
            this.browserEntryHeight,
            this.maxLabelWidth,
            this.configWidth,
            wrapper,
            listIndex,
            this.parent,
            this
          );
        }
        return new WidgetConfigOption(
          x,
          y,
          this.browserEntryWidth,
          this.browserEntryHeight,
          this.maxLabelWidth,
          this.configWidth,
          wrapper,
          listIndex,
          this.parent,
          this
        );
      }
    };
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

  /**
   * Item-id string row: live item-registry invalid tooltip on the text field
   * (MaLiLib has no ITEM_ID {@link TextFieldType}). Factory selects this for
   * {@link Configs.Generic#WAND_ITEM}.
   */
  private static final class ItemIdConfigOption extends WidgetConfigOption {
    ItemIdConfigOption(
      int x,
      int y,
      int width,
      int height,
      int labelWidth,
      int configWidth,
      ConfigOptionWrapper wrapper,
      int listIndex,
      IKeybindConfigGui host,
      WidgetListConfigOptionsBase<?, ?> parent
    ) {
      super(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent);
    }

    @Override
    protected void addConfigTextFieldEntry(
      int x,
      int y,
      int resetX,
      int configWidth,
      int configHeight,
      IConfigValue config,
      TextFieldType type
    ) {
      GuiTextFieldGeneric field = this.createTextField(
        x, y + 1, configWidth - 4, configHeight - 3
      );
      int maxLength = type.getMaxLength() > 0 ? type.getMaxLength() : this.maxTextfieldTextLength;
      field.setMaxLength(maxLength);
      field.setValue(config.getStringValue());

      ButtonGeneric resetButton = this.createResetButton(resetX, y, config);
      ConfigOptionChangeListenerTextField listener = new ConfigOptionChangeListenerTextField(
        config, field, resetButton
      ) {
        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField) {
          boolean result = super.onTextChange(textField);
          WandItem.applyInvalidTooltip(textField);
          return result;
        }
      };
      ConfigOptionListenerResetConfig resetListener = new ConfigOptionListenerResetConfig(
        config,
        new ConfigOptionListenerResetConfig.ConfigResetterTextField(config, field),
        resetButton,
        null
      );
      this.addTextField(field, listener, type);
      this.addButton(resetButton, resetListener);
      WandItem.applyInvalidTooltip(field);
    }

    @Override
    public void applyNewValueToConfig() {
      super.applyNewValueToConfig();
      if (this.textField != null) {
        WandItem.applyInvalidTooltip(this.textField.textField());
      }
    }
  }

  /**
   * Profile-table rows only ({@link Configs.Profiles#BUILTIN_PROFILES} /
   * {@link Configs.Profiles#CUSTOM_PROFILES}): RESET arms into Cancel (same rect) +
   * Confirm to the right so a double-click lands on Cancel. Selected by
   * {@link #createListWidget}; other rows use stock {@link WidgetConfigOption}.
   */
  private static final class ConfirmResetConfigOption extends WidgetConfigOption {
    private static final int GAP = 2;

    private ButtonGeneric resetButton;
    private ButtonBase configButton;
    private ConfigTable table;
    private int idleEntryWidth;
    private int resetIdleX;
    private int resetIdleWidth;
    private boolean armed;
    private ButtonGeneric cancelButton;
    private ButtonGeneric confirmButton;

    ConfirmResetConfigOption(
      int x,
      int y,
      int width,
      int height,
      int labelWidth,
      int configWidth,
      ConfigOptionWrapper wrapper,
      int listIndex,
      IKeybindConfigGui host,
      WidgetListConfigOptionsBase<?, ?> parent
    ) {
      super(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent);
    }

    @Override
    protected void addConfigButtonEntry(
      int x, int y, IConfigResettable config, ButtonBase button
    ) {
      ConfigTable table = (ConfigTable) config;
      this.table = table;
      this.idleEntryWidth = this.getWidth();
      this.resetButton = this.createResetButton(x, y, config);
      this.resetIdleX = this.resetButton.getX();
      this.resetIdleWidth = this.resetButton.getWidth();
      // MaLiLib ConfigTable.isModified() is stale after popup edits; use live compare.
      this.resetButton.setEnabled(Configs.configTableIsModified(this.table));

      // Both profile tables use ProfilesTableEdit (always-disabled per-row RESET).
      String label = table.getDisplayString() != null
        ? table.getDisplayString()
        : table.getName();
      ButtonGeneric open = new ButtonGeneric(
        button.getX(), button.getY(), button.getWidth(), 20, label
      );
      this.configButton = open;
      this.addButton(open, (btn, mouseButton) -> {
        if (this.host instanceof IConfigGui configGui) {
          Screen parent = GuiUtils.getCurrentScreen();
          if (config == Configs.Profiles.CUSTOM_PROFILES) {
            GuiBase.openGui(new CustomProfilesTableEdit(configGui, null, parent));
          } else {
            GuiBase.openGui(new BuiltinProfilesTableEdit(configGui, null, parent));
          }
        }
        this.resetButton.setEnabled(Configs.configTableIsModified(this.table));
      });
      this.addButton(this.resetButton, (btn, mouseButton) -> this.armReset());
    }

    private void armReset() {
      if (this.armed || this.resetButton == null || this.table == null) {
        return;
      }
      this.armed = true;

      // Park the idle RESET off-screen so Cancel receives the double-click.
      this.resetButton.setX(-10000);
      this.resetButton.setWidth(0);
      this.resetButton.setEnabled(false);

      String cancelLabel = StringUtils.translate("mobwalk.gui.button.reset_cancel");
      String confirmLabel = StringUtils.translate("mobwalk.gui.button.reset_confirm");

      this.cancelButton = new ButtonGeneric(
        this.resetIdleX,
        this.resetButton.getY(),
        this.resetIdleWidth,
        20,
        cancelLabel
      );
      this.confirmButton = new ButtonGeneric(
        this.cancelButton.getX() + this.cancelButton.getWidth() + GAP,
        this.resetButton.getY(),
        -1,
        20,
        confirmLabel
      );

      int rowRight = this.getX() + this.idleEntryWidth;
      int confirmRight = this.confirmButton.getX() + this.confirmButton.getWidth();
      if (confirmRight > rowRight) {
        // Not enough room on the right — still keep Cancel under the cursor.
        int leftX = this.cancelButton.getX() - GAP - this.confirmButton.getWidth();
        this.confirmButton.setX(Math.max(this.getX(), leftX));
      }

      this.addButton(this.cancelButton, (btn, mouseButton) -> this.disarmReset(false));
      this.addButton(this.confirmButton, (btn, mouseButton) -> this.disarmReset(true));

      int right = Math.max(
        this.cancelButton.getX() + this.cancelButton.getWidth(),
        this.confirmButton.getX() + this.confirmButton.getWidth()
      );
      this.setWidth(Math.max(this.idleEntryWidth, right - this.getX()));
    }

    private void disarmReset(boolean confirm) {
      if (!this.armed || this.table == null || this.resetButton == null) {
        return;
      }

      if (confirm) {
        this.table.resetToDefault();
        // ConfigTable.resetToDefault skips the value-change callback.
        Configs.syncAfterProfilesTableReset();
        if (this.host instanceof GuiConfigs gui) {
          // Rebuild rows so dependent options (e.g. mobProfile cycle) re-read state.
          gui.reloadConfigList();
          return;
        }
        this.configButton.updateDisplayString();
      }

      if (this.cancelButton != null) {
        this.subWidgets.remove(this.cancelButton);
        this.cancelButton = null;
      }
      if (this.confirmButton != null) {
        this.subWidgets.remove(this.confirmButton);
        this.confirmButton = null;
      }

      this.resetButton.setX(this.resetIdleX);
      this.resetButton.setWidth(this.resetIdleWidth);
      this.resetButton.setDisplayString(
        StringUtils.translate("malilib.gui.button.reset.caps")
      );
      this.resetButton.setEnabled(Configs.configTableIsModified(this.table));
      this.setWidth(this.idleEntryWidth);
      this.armed = false;
    }
  }

  /** Rebuild the options list after a confirmed ConfigTable RESET. */
  void reloadConfigList() {
    this.reCreateListWidget();
    if (this.getListWidget() != null) {
      this.getListWidget().resetScrollbarPosition();
    }
    this.initGui();
  }
}
