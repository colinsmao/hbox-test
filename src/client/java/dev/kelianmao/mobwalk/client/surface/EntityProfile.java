package dev.kelianmao.mobwalk.client.surface;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;

/**
 * The size/reach of the entity the standable-surface flood is computed for. A
 * Minecraft hitbox is an axis-aligned {@code width x width} square footprint, so
 * {@code width} fully describes the horizontal extent; {@code reach} is the
 * walkable height threshold (see {@code docs/geometry.md} "Reachability model").
 *
 * <p>Builtin seeds (roster order) live here as constants; enable flags and the
 * live cycle are owned by {@link dev.kelianmao.mobwalk.client.config.ProfileRoster}. {@link #next()} /
 * {@link Option} still cycle the original three (Point / Player / Ravager) until
 * settings wires the roster.
 *
 * <p>{@code width 0} makes config-space dilation a no-op, so {@link #POINT}
 * reproduces the pre-profile point-particle behavior — the oracle baseline.
 * {@code width} drives dilation; {@code height} drives headroom.
 *
 * <p>{@code reach} is {@code max(jump, step)}: two surfaces connect when
 * {@code |dTopY| <= reach}. Living-entity builtins use
 * {@link #DEFAULT_JUMP_REACH}; {@link #POINT} keeps {@code 1.0} as the geometric
 * oracle. Reach is the profile’s fixed vertical threshold only (see geometry.md).
 *
 * <p>{@code height} is the entity's hitbox height (vanilla values; doubles, not
 * {@code 1/16}-quantized — see {@code docs/geometry.md}). It drives the
 * <b>headroom</b> rule: a box top at {@code T} stays standable only where the
 * standing column {@code (T, T+height]} is clear of collision boxes. {@code POINT}
 * keeps {@code height 0} so it stays the pure point-walker (the headroom test then
 * reduces to the buried test) and the oracle baseline.
 */
public record EntityProfile(String name, double width, double height, double reach) {
  /**
   * Living-entity jump peak (~1.2522 blocks): sum of the discrete tick loop from
   * impulse {@code 0.42}, gravity {@code -0.08}, drag {@code ×0.98}. Vanilla
   * exposes the impulse, not this peak height.
   */
  public static final double DEFAULT_JUMP_REACH = 1.2522;

  public static final EntityProfile POINT = new EntityProfile("Point", 0.0, 0.0, 1.0);
  public static final EntityProfile PLAYER = new EntityProfile("Player", 0.6, 1.8, DEFAULT_JUMP_REACH);
  public static final EntityProfile RAVAGER = new EntityProfile("Ravager", 1.95, 2.2, DEFAULT_JUMP_REACH);
  public static final EntityProfile WARDEN = new EntityProfile("Warden", 0.9, 2.9, DEFAULT_JUMP_REACH);
  /** JE Zombie and Witch share {@code 0.6 × 1.95}. */
  public static final EntityProfile ZOMBIE_WITCH =
    new EntityProfile("Zombie/Witch", 0.6, 1.95, DEFAULT_JUMP_REACH);
  public static final EntityProfile SKELETON = new EntityProfile("Skeleton", 0.6, 1.99, DEFAULT_JUMP_REACH);

  // Legacy three-way cycle for settings / sneak+right-click until ProfileRoster wires in.
  private static final EntityProfile[] CYCLE = {POINT, PLAYER, RAVAGER};

  /** The next profile in the cycle ({@code Point -> Player -> Ravager -> Point}). */
  public EntityProfile next() {
    for (int i = 0; i < CYCLE.length; i++) {
      if (CYCLE[i].equals(this)) {
        return CYCLE[(i + 1) % CYCLE.length];
      }
    }
    return PLAYER;
  }

  /**
   * MaLiLib {@link fi.dy.masa.malilib.config.options.ConfigOptionList} entries for
   * the shipped profiles. JSON ids are lowercase; display names match
   * {@link EntityProfile#name()}.
   */
  public enum Option implements IConfigOptionListEntry {
    POINT(EntityProfile.POINT, "point"),
    PLAYER(EntityProfile.PLAYER, "player"),
    RAVAGER(EntityProfile.RAVAGER, "ravager");

    private final EntityProfile profile;
    private final String id;

    Option(EntityProfile profile, String id) {
      this.profile = profile;
      this.id = id;
    }

    public EntityProfile profile() {
      return profile;
    }

    @Override
    public String getStringValue() {
      return id;
    }

    @Override
    public String getDisplayName() {
      return profile.name();
    }

    @Override
    public IConfigOptionListEntry cycle(boolean forward) {
      Option[] values = values();
      int i = ordinal();
      return values[forward
        ? (i + 1) % values.length
        : (i - 1 + values.length) % values.length];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
      if (value != null) {
        for (Option option : values()) {
          if (option.id.equalsIgnoreCase(value)
            || option.profile.name().equalsIgnoreCase(value)) {
            return option;
          }
        }
      }
      return PLAYER;
    }

    /** Map a live {@link EntityProfile} to its option entry (unknown → Player). */
    public static Option of(EntityProfile profile) {
      if (profile != null) {
        for (Option option : values()) {
          if (option.profile.equals(profile)) {
            return option;
          }
        }
      }
      return PLAYER;
    }
  }
}
