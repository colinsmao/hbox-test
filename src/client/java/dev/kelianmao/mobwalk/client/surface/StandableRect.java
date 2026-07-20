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
 * <p>{@code collisionTopY} is the <b>collision</b> top — the height all walkability math
 * (flood reachability, occlusion, holes) is keyed on. {@code visualTopY} is a
 * <b>draw-only</b> raise: for a block that collides lower than it renders (soul
 * sand, mud), it is the block's visible/outline top so the marker can be drawn on
 * the face you actually see instead of buried inside the block; for every other
 * block it equals {@code collisionTopY}. Nothing but rendering reads it (see
 * {@code docs/geometry.md} "Visible-face top vs collision top").
 *
 * <p>{@code depth} is a <b>debug-only</b> flood-distance tag: the BFS hop-count
 * from the seed at which this surface was reached (0 = seed), aggregated by min
 * over the raw nodes a merged rect covers. {@code -1} means "no flood depth"
 * (constructed outside the flood, or a test fixture); the renderer draws that
 * grey. Nothing but the debug depth-coloring reads it.
 */
public record StandableRect(double minX, double minZ, double maxX, double maxZ,
    double collisionTopY, double visualTopY, int depth) {
  /** A rect with an explicit visible top but no flood depth ({@code depth = -1}). */
  public StandableRect(double minX, double minZ, double maxX, double maxZ,
      double collisionTopY, double visualTopY) {
    this(minX, minZ, maxX, maxZ, collisionTopY, visualTopY, -1);
  }

  /** A rect whose visible top coincides with its collision top (the common case). */
  public StandableRect(double minX, double minZ, double maxX, double maxZ, double collisionTopY) {
    this(minX, minZ, maxX, maxZ, collisionTopY, collisionTopY, -1);
  }
}
