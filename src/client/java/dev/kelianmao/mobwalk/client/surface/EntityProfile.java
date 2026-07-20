package dev.kelianmao.mobwalk.client.surface;

/**
 * The size/reach of the entity the standable-surface flood is computed for. A
 * Minecraft hitbox is an axis-aligned {@code width x width} square footprint, so
 * {@code width} fully describes the horizontal extent; {@code reach} is the
 * walkable height threshold (see {@code docs/geometry.md} "Reachability model").
 *
 * <p>Builtin seeds live here as constants; enable flags, order, and the live
 * cycle are owned by {@link dev.kelianmao.mobwalk.client.config.ProfileRoster}
 * / {@link dev.kelianmao.mobwalk.client.config.RosterProfileOption}.
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
}
