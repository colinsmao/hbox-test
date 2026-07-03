package com.example.overlay.client;

/**
 * The size/reach of the entity the standable-surface flood is computed for. A
 * Minecraft hitbox is an axis-aligned {@code width x width} square footprint, so
 * {@code width} fully describes the horizontal extent; {@code reach} is the
 * walkable height threshold (see {@code docs/geometry.md} "Reachability model").
 *
 * <p>Three profiles ship, cycled in order ({@link #next()}): {@link #POINT}
 * (width 0, the default), {@link #PLAYER} (0.6), {@link #RAVAGER} (1.95).
 * {@code width 0} makes the (upcoming) config-space dilation a no-op, so
 * {@code POINT} reproduces the pre-profile point-particle behavior exactly — both
 * the safe default and an A/B baseline. {@code width} is carried but unused until
 * the dilation stages.
 *
 * <p>{@code reach} is the walkable height threshold on {@code |dT|}: two surfaces
 * connect when their height difference is at most {@code reach}. Reachability is
 * plain yes/no — a location is reachable or it is not — so this is a single scalar
 * ({@code 1.0} for every profile).
 *
 * <p>{@code height} is the entity's hitbox height (vanilla values; doubles, not
 * {@code 1/16}-quantized — see {@code docs/geometry.md}). It drives the
 * <b>headroom</b> rule: a box top at {@code T} stays standable only where the
 * standing column {@code (T, T+height]} is clear of collision boxes. {@code POINT}
 * keeps {@code height 0} so it stays the pure point-walker (the headroom test then
 * reduces to today's buried test) and the oracle baseline.
 */
public record EntityProfile(String name, double width, double height, double reach) {
	public static final EntityProfile POINT = new EntityProfile("Point", 0.0, 0.0, 1.0);
	public static final EntityProfile PLAYER = new EntityProfile("Player", 0.6, 1.8, 1.0);
	public static final EntityProfile RAVAGER = new EntityProfile("Ravager", 1.95, 2.2, 1.0);

	// Cycle order for the sneak+right-click-at-nothing toggle.
	private static final EntityProfile[] CYCLE = {POINT, PLAYER, RAVAGER};

	/** The next profile in the cycle ({@code Point -> Player -> Ravager -> Point}). */
	public EntityProfile next() {
		for (int i = 0; i < CYCLE.length; i++) {
			if (CYCLE[i].equals(this)) {
				return CYCLE[(i + 1) % CYCLE.length];
			}
		}
		return POINT;
	}
}
