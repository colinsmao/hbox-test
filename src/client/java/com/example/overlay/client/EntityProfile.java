package com.example.overlay.client;

/**
 * The size/reach of the entity the standable-surface flood is computed for. A
 * Minecraft hitbox is an axis-aligned {@code width x width} square footprint, so
 * {@code width} fully describes the horizontal extent; {@code reach} is the
 * symmetric walkable height threshold (see {@code PLAN.md} "Reachability model").
 *
 * <p>Three profiles ship, cycled in order ({@link #next()}): {@link #POINT}
 * (width 0, the default), {@link #PLAYER} (0.6), {@link #RAVAGER} (1.95).
 * {@code width 0} makes the (upcoming) config-space dilation a no-op, so
 * {@code POINT} reproduces the pre-profile point-particle behavior exactly — both
 * the safe default and an A/B baseline. {@code width} is carried but unused until
 * the dilation stages.
 *
 * <p>{@code reach} replaces the old single {@code MAX_STEP}: one symmetric
 * threshold on {@code |dT|} (the flood is reversible, so this is a single value,
 * not an up/down split). Defaulted to {@code 1.0} for every profile to preserve
 * current flood behavior; per-profile tuning (e.g. a higher jump reach) is
 * deferred. Entity height (headroom) is reserved for later and not modeled here.
 */
public record EntityProfile(String name, double width, double reach) {
	public static final EntityProfile POINT = new EntityProfile("Point", 0.0, 1.0);
	public static final EntityProfile PLAYER = new EntityProfile("Player", 0.6, 1.0);
	public static final EntityProfile RAVAGER = new EntityProfile("Ravager", 1.95, 1.0);

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
