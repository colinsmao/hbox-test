package dev.kelianmao.mobwalk.client;

import java.util.Locale;
import java.util.Optional;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;

/**
 * MaLiLib cycle-list entry backed by {@link ProfileRoster} enabled ids (not the
 * closed {@link EntityProfile.Option} enum).
 */
public final class RosterProfileOption implements IConfigOptionListEntry {
  private final String id;

  public RosterProfileOption(String id) {
    this.id = (id == null || id.isBlank()) ? "player" : id;
  }

  public String id() {
    return id;
  }

  public static RosterProfileOption player() {
    return new RosterProfileOption("player");
  }

  @Override
  public String getStringValue() {
    return id;
  }

  @Override
  public String getDisplayName() {
    return Configs.profileDisplayLabel(id);
  }

  @Override
  public IConfigOptionListEntry cycle(boolean forward) {
    Optional<String> next = Configs.roster().cycle(id, forward);
    return next.map(RosterProfileOption::new).orElse(this);
  }

  @Override
  public IConfigOptionListEntry fromString(String value) {
    if (value == null || value.isBlank()) {
      return player();
    }
    String trimmed = value.trim();
    Optional<String> builtin = ProfileRoster.builtinIdForName(trimmed);
    if (builtin.isPresent()) {
      return new RosterProfileOption(builtin.get());
    }
    return new RosterProfileOption(trimmed.toLowerCase(Locale.ROOT));
  }
}
