package dev.kelianmao.mobwalk.client.surface;

/**
 * Undirected climb test between two standable surfaces: the lower supplies a
 * vertical budget, and the pair connects iff the higher's {@code collisionTopY}
 * is at or below that budget. Land pairs use {@code reach}; when the lower
 * {@link HazardClass#isFluid()} and the higher does not, the budget is also
 * capped by {@code FLUID_SURFACE_DROP + fluidEscape} (see docs/geometry.md Escape
 * cap).
 *
 * <p>Built once per {@code select} from the profile and Generic
 * {@code fluidEscapeHeight}; the pure flood layer never reads config.
 */
record ClimbRule(double reach, double fluidEscape) {
  /**
   * Still-fluid surface drop: a source sits {@code 8/9} up its cell, so adding
   * {@code 1/9} converts a rim height measured from the fluid <b>block</b>
   * top into a height above the plane the rect actually carries.
   */
  static final double FLUID_SURFACE_DROP = 1.0 / 9.0;

  /**
   * Whether {@code a} and {@code b} form a climb edge. Same verdict either
   * argument order; always spends the lower rect's budget.
   */
  boolean climbs(StandableRect a, StandableRect b) {
    StandableRect lower;
    StandableRect higher;
    if (a.collisionTopY() <= b.collisionTopY()) {
      lower = a;
      higher = b;
    } else {
      lower = b;
      higher = a;
    }
    double budget = lower.collisionTopY() + reach;
    if (lower.hazard().isFluid() && !higher.hazard().isFluid()) {
      budget = Math.min(budget,
        lower.collisionTopY() + FLUID_SURFACE_DROP + fluidEscape);
    }
    return higher.collisionTopY() <= budget + RectMath.EPS;
  }
}
