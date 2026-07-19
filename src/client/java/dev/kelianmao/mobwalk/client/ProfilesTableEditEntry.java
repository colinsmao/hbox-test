package dev.kelianmao.mobwalk.client;

import java.util.List;

import fi.dy.masa.malilib.config.options.table.TableRow;
import fi.dy.masa.malilib.config.options.table.type.EntryTypes;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetListTableEdit;
import fi.dy.masa.malilib.gui.widgets.WidgetTableEditEntry;
import fi.dy.masa.malilib.util.StringUtils;

/**
 * Profile table row: per-row RESET stays visible but always disabled.
 * MaLiLib's {@code checkResetButtonState} re-enables when the row looks modified;
 * {@link #setEnabled} on this button ignores that and stays off.
 */
final class ProfilesTableEditEntry extends WidgetTableEditEntry {
  ProfilesTableEditEntry(
    int x,
    int y,
    int width,
    int height,
    int listIndex,
    boolean isOdd,
    TableRow entry,
    TableRow defaultValue,
    WidgetListTableEdit parent,
    List<EntryTypes> types
  ) {
    super(x, y, width, height, listIndex, isOdd, entry, defaultValue, parent, types);
  }

  @Override
  protected ButtonGeneric createResetButton(int x, int y) {
    String label = StringUtils.translate("malilib.gui.button.reset.caps");
    ButtonGeneric button = new ButtonGeneric(x, y, -1, 20, label) {
      @Override
      public void setEnabled(boolean enabled) {
        super.setEnabled(false);
      }
    };
    button.setX(x - button.getWidth());
    button.setEnabled(false);
    return button;
  }
}
