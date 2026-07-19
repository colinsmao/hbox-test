package dev.kelianmao.mobwalk.client;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.screens.Screen;

import fi.dy.masa.malilib.config.IConfigTable;
import fi.dy.masa.malilib.config.options.table.ConfigTable;
import fi.dy.masa.malilib.config.options.table.TableRow;
import fi.dy.masa.malilib.gui.GuiTableEdit;
import fi.dy.masa.malilib.gui.interfaces.IConfigGui;
import fi.dy.masa.malilib.gui.interfaces.IDialogHandler;
import fi.dy.masa.malilib.gui.widgets.WidgetListTableEdit;
import fi.dy.masa.malilib.gui.widgets.WidgetTableEditEntry;

/** Builtin / custom profile table editors that use {@link ProfilesTableEditEntry}. */
abstract class ProfilesTableEdit extends GuiTableEdit {
  ProfilesTableEdit(
    IConfigTable config,
    IConfigGui configGui,
    @Nullable IDialogHandler dialogHandler,
    Screen parent
  ) {
    super(config, configGui, dialogHandler, parent);
  }

  @Override
  protected WidgetListTableEdit createListWidget(int listX, int listY) {
    return new WidgetListTableEdit(
      this.dialogLeft + 10,
      this.dialogTop + 30,
      this.getBrowserWidth(),
      this.getBrowserHeight(),
      this.dialogWidth - 100,
      this
    ) {
      @Override
      protected WidgetTableEditEntry createListEntryWidget(
        int x, int y, int listIndex, boolean isOdd, TableRow entry
      ) {
        IConfigTable config = this.getConfig();
        TableRow dummy = ConfigTable.getDummy(config.getTypes());
        if (listIndex >= 0 && listIndex < config.getTable().size()) {
          return new ProfilesTableEditEntry(
            x,
            y,
            this.browserEntryWidth,
            this.browserEntryHeight,
            listIndex,
            isOdd,
            config.getTable().get(listIndex),
            dummy,
            this,
            config.getTypes()
          );
        }
        return new ProfilesTableEditEntry(
          x,
          y,
          this.browserEntryWidth,
          this.browserEntryHeight,
          listIndex,
          isOdd,
          dummy,
          dummy,
          this,
          config.getTypes()
        );
      }
    };
  }
}
