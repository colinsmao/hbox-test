package dev.kelianmao.mobwalk.client.surface;

/**
 * Hazard identity on a standable surface / world box / beam marker.
 * {@link #NONE} is ordinary geometry. {@link #HOLE} is beam-marker identity only
 * (trap drop edges) — never stamped on standable surfaces or world boxes.
 * {@link #priority()} orders merge ownership within a radius tier (higher claims
 * first), so a new kind slots in by picking a priority without touching the
 * partition algorithm. Water and lava arrive from vanilla fluid tags at world
 * read; solid hazards ({@link #SOUL_SAND}, {@link #MAGMA}) stamp from block identity.
 */
public enum HazardClass {
  NONE(0),
  HOLE(0),  // Beam-only marker; not a surface merge tag
  WATER(1),
  LAVA(2),
  SOUL_SAND(3),
  MAGMA(4);

  private final int priority;

  HazardClass(int priority) {
    this.priority = priority;
  }

  /** Higher values claim overlap before lower ones in the merge ownership product. */
  public int priority() {
    return priority;
  }

  /**
   * Whether this kind is a swimmable fluid surface (escape-cap / fluid emission).
   * Non-fluid hazards ({@link #HOLE}, {@link #SOUL_SAND}, {@link #MAGMA}) stay {@code false}.
   */
  public boolean isFluid() {
    return this == WATER || this == LAVA;
  }

  /**
   * Solid block-effect hazards painted with coplanar punch after dilated expose
   * (soul sand / magma). Fluids stay fully dilated and are not solid hazards.
   */
  public boolean isSolidHazard() {
    return this == SOUL_SAND || this == MAGMA;
  }
}
