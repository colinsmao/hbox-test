package dev.kelianmao.mobwalk.client.surface;

/**
 * The fall column of a drop edge: the rim line itself, over a {@code [lo,hi]}
 * sub-interval of that edge.
 *
 * <p>Surfaces are dilated by the profile half-width, so a rim line is exactly where
 * a point-walker loses support and starts falling straight down it. What can catch
 * the entity is therefore the geometry that {@link RectMath#crossesLine reaches
 * across} that line within {@code [lo,hi]}: every rect tested against a column is
 * itself dilated by the same half-width, so the bare line already answers "would
 * this entity's hitbox touch that surface".
 *
 * @param alongX  {@code true} when the edge runs along X at a fixed Z ({@code line}
 *                is Z, {@code [lo,hi]} the X interval); else along Z.
 * @param maxSide {@code true} when the drop side is {@code +axis}.
 * @param line    fixed coordinate of the rim (Z if {@code alongX}, else X).
 * @param lo      start of the sub-interval on the edge's varying axis.
 * @param hi      end of the sub-interval on the edge's varying axis.
 */
record FallColumn(boolean alongX, boolean maxSide, double line, double lo, double hi) {

  /** The fall column of a whole drop span. */
  static FallColumn of(SkirtSpan sp) {
    return new FallColumn(sp.alongX(), sp.maxSide(), sp.line(), sp.lo(), sp.hi());
  }

  /** The same rim line, narrowed to one sub-interval of the edge. */
  FallColumn clampedTo(double subLo, double subHi) {
    return new FallColumn(alongX, maxSide, line, subLo, subHi);
  }

  /** True iff {@code r} reaches across the rim line within this column's interval. */
  boolean crosses(StandableRect r) {
    if (!RectMath.crossesLine(r, alongX, maxSide, line)) {
      return false;
    }
    double min = alongX ? r.minX() : r.minZ();
    double max = alongX ? r.maxX() : r.maxZ();
    return Math.min(max, hi) - Math.max(min, lo) > RectMath.EPS;
  }
}
