package dev.kelianmao.mobwalk.client;

/**
 * An axis-aligned, horizontal standable patch in absolute world coordinates.
 *
 * <p>Collision shapes are unions of axis-aligned cuboids, so every upward-facing
 * face is an axis-aligned rectangle over {@code [minX,maxX] x [minZ,maxZ]} at a
 * single {@code topY}. Stored in world-space doubles (the resolved
 * {@code BlockPos} already folded in); see {@code PLAN.md} for why we skip a
 * 1/16-pixel integer model.
 *
 * <p>{@code topY} is the <b>collision</b> top — the height all walkability math
 * (flood reachability, occlusion, holes) is keyed on. {@code visualTopY} is a
 * <b>draw-only</b> raise: for a block that collides lower than it renders (soul
 * sand, mud), it is the block's visible/outline top so the marker can be drawn on
 * the face you actually see instead of buried inside the block; for every other
 * block it equals {@code topY}. Nothing but rendering reads it (see
 * {@code docs/geometry.md} "Visible-face top vs collision top").
 */
public record StandableRect(double minX, double minZ, double maxX, double maxZ,
		double topY, double visualTopY) {
	/** A rect whose visible top coincides with its collision top (the common case). */
	public StandableRect(double minX, double minZ, double maxX, double maxZ, double topY) {
		this(minX, minZ, maxX, maxZ, topY, topY);
	}
}
