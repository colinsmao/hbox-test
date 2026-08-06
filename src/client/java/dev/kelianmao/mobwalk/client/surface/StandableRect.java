package dev.kelianmao.mobwalk.client.surface;

/**
 * An axis-aligned, horizontal standable patch in absolute world coordinates.
 *
 * <p>Collision shapes are unions of axis-aligned cuboids, so every upward-facing
 * face is an axis-aligned rectangle over {@code [minX,maxX] x [minZ,maxZ]} at a
 * single {@code collisionTopY}. Stored in world-space doubles (the resolved
 * {@code BlockPos} already folded in); see {@code docs/geometry.md} Appendix A
 * for why we skip a 1/16-pixel integer model.
 *
 * <p>{@code collisionTopY} is the collision top all walkability is keyed on.
 * {@code visualTopY} is the visible/outline top when that sits above collision
 * (soul sand, mud); otherwise it equals {@code collisionTopY}. Walkability stays
 * on collision; merge ownership and paint-side skirts/occluders may key on
 * {@code visualTopY} (see {@code docs/geometry.md} "Visible-face top vs collision top").
 *
 * <p>{@code hazard} is the surface's hazard identity ({@link HazardClass}): stamped
 * from the source {@code WorldBox} in {@code exposeBox}, carried through the merge
 * as an ownership axis ({@code hazardPriority}), and printed by {@code /mobwalk dump}.
 * Ordinary solids are {@link HazardClass#NONE}.
 *
 * <p>{@code depth} is flood-distance metadata: the BFS hop-count from the seed at
 * which this surface was reached (0 = seed), aggregated by min over the raw nodes
 * a merged rect covers. {@code -1} means "no flood depth" (constructed outside the
 * flood, or a test fixture). Debug {@code shadeByDepth} reads it for hue.
 *
 * <p>{@code frontier} is the merge radius-tier flag ({@code RadiusTier.FRONTIER}):
 * incomplete selection at the BFS depth limit. Cutoff-ring greying and perimeter
 * suppression key on this flag; exact {@code depth} is not an ownership axis.
 */
public record StandableRect(double minX, double minZ, double maxX, double maxZ,
    double collisionTopY, double visualTopY, HazardClass hazard, int depth, boolean frontier) {
  /** A rect with an explicit visible top and hazard identity but no flood metadata. */
  public StandableRect(double minX, double minZ, double maxX, double maxZ,
      double collisionTopY, double visualTopY, HazardClass hazard) {
    this(minX, minZ, maxX, maxZ, collisionTopY, visualTopY, hazard, -1, false);
  }

  /** A rect with an explicit visible top but no flood metadata ({@code NONE} hazard). */
  public StandableRect(double minX, double minZ, double maxX, double maxZ,
      double collisionTopY, double visualTopY) {
    this(minX, minZ, maxX, maxZ, collisionTopY, visualTopY, HazardClass.NONE, -1, false);
  }

  /** A rect whose visible top coincides with its collision top (the common case). */
  public StandableRect(double minX, double minZ, double maxX, double maxZ, double collisionTopY) {
    this(minX, minZ, maxX, maxZ, collisionTopY, collisionTopY, HazardClass.NONE, -1, false);
  }
}
