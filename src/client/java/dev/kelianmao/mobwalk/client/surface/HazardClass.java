package dev.kelianmao.mobwalk.client.surface;

/**
 * Hazard identity on a standable surface / world box. {@link #NONE} is ordinary
 * geometry. {@link #priority()} orders merge ownership within a radius tier
 * (higher claims first), so a new kind slots in by picking a priority without
 * touching the partition algorithm. Water and lava arrive from vanilla fluid
 * tags at world read; later hazards (magma, soul sand) add constants here.
 */
public enum HazardClass {
  NONE(0),
  WATER(1),
  LAVA(2);

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
   * Non-fluid hazards (future magma, soul sand) stay {@code false}.
   */
  public boolean isFluid() {
    return this == WATER || this == LAVA;
  }
}
