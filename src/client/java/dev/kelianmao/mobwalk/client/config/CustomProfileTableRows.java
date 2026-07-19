package dev.kelianmao.mobwalk.client.config;

import dev.kelianmao.mobwalk.client.surface.EntityProfile;

import java.util.ArrayList;
import java.util.List;

import fi.dy.masa.malilib.config.options.table.TableRow;
import fi.dy.masa.malilib.config.options.table.type.BooleanEntry;
import fi.dy.masa.malilib.config.options.table.type.DoubleEntry;
import fi.dy.masa.malilib.config.options.table.type.StringEntry;

/**
 * Custom-profiles ConfigTable row builders and ADD seeding (clone clicked row
 * below source).
 */
final class CustomProfileTableRows {
  private CustomProfileTableRows() {}

  static TableRow customRow(EntityProfile p, boolean enabled) {
    return TableRow.of(
      BooleanEntry.of(enabled),
      StringEntry.of(p.name()),
      DoubleEntry.of(p.width()),
      DoubleEntry.of(p.height()),
      DoubleEntry.of(p.reach())
    );
  }

  static TableRow copyCustomRow(TableRow source) {
    boolean enabled = Boolean.TRUE.equals(source.getBoolean(0));
    String name = source.getString(1);
    double width = source.getDouble(2);
    double height = source.getDouble(3);
    double reach = source.getDouble(4);
    return customRow(new EntityProfile(name, width, height, reach), enabled);
  }

  static boolean isKnownCustomRow(TableRow row, List<TableRow> known) {
    for (TableRow prev : known) {
      if (prev == row) {
        return true;
      }
    }
    return false;
  }

  /**
   * Index of the clicked source for a MaLiLib insert-before new row, or
   * {@code -1} when there is no known row below the insert.
   */
  static int clickedSourceIndex(int newIndex, int tableSize, boolean[] knownAt) {
    int src = newIndex + 1;
    if (src < tableSize && knownAt[src]) {
      return src;
    }
    return -1;
  }

  /**
   * Seed newly inserted custom rows. New row at {@code i} with a known prior row
   * at {@code i + 1} → clone that source and swap so the clone is below.
   * Otherwise seed {@code fallback} enabled.
   */
  static void seedNewCustomRows(
    List<TableRow> table, List<TableRow> knownRows, EntityProfile fallback
  ) {
    boolean[] knownAt = new boolean[table.size()];
    for (int i = 0; i < table.size(); i++) {
      knownAt[i] = isKnownCustomRow(table.get(i), knownRows);
    }
    List<Integer> newIndices = new ArrayList<>();
    for (int i = 0; i < table.size(); i++) {
      if (!knownAt[i]) {
        newIndices.add(i);
      }
    }
    for (int n = newIndices.size() - 1; n >= 0; n--) {
      int i = newIndices.get(n);
      int src = clickedSourceIndex(i, table.size(), knownAt);
      if (src >= 0) {
        TableRow clone = copyCustomRow(table.get(src));
        table.set(i, clone);
        TableRow above = table.get(i);
        TableRow source = table.get(src);
        table.set(i, source);
        table.set(src, above);
      } else {
        table.set(i, customRow(fallback, true));
      }
    }
  }
}
