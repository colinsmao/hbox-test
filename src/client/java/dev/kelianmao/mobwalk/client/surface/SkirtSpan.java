package dev.kelianmao.mobwalk.client.surface;

/**
 * One edge skirt span: a sub-span of a standable surface edge drawn as a vertical
 * marker either <b>up</b> (wall/ceiling) or <b>down</b> (drop).
 *
 * <p>Computed once per select and published in the snapshot. {@code maxExtent} is
 * how far along {@link #direction()} the marker may run before a stop surface
 * (wall top above, or floor below). {@link #UNLIMITED} means no geometric stop —
 * draw uses the full Appearance height for that direction.
 *
 * @param alongX      {@code true} when the edge runs along X at a fixed Z
 *                    ({@code line} is Z, {@code [lo,hi]} the X interval); else along Z.
 * @param maxSide     {@code true} for the {@code +axis} edge of the source rect.
 * @param line        fixed coordinate of the edge (Z if {@code alongX}, else X).
 * @param lo          start of the sub-span on its varying axis.
 * @param hi          end of the sub-span on its varying axis.
 * @param baseY       collision surface top {@code T}.
 * @param visualBaseY draw-height base (visible-face top when raised).
 * @param direction   {@link Direction#UP} or {@link Direction#DOWN}.
 * @param maxExtent   max run length along {@code direction}; unlimited = {@link #UNLIMITED}.
 * @param depth       source rect flood-depth tag ({@code -1} = none).
 * @param frontier    source rect cutoff-frontier flag.
 */
public record SkirtSpan(boolean alongX, boolean maxSide, double line,
    double lo, double hi, double baseY, double visualBaseY,
    Direction direction, double maxExtent, int depth, boolean frontier) {

  /** Vertical run direction for an edge skirt. */
  public enum Direction {
    UP,
    DOWN
  }

  /** Unlimited geometric extent (open drop / use full Appearance height). */
  public static final double UNLIMITED = Double.POSITIVE_INFINITY;

  /** A span with no flood metadata (test fixtures / pre-flood construction). */
  public SkirtSpan(boolean alongX, boolean maxSide, double line,
      double lo, double hi, double baseY, double visualBaseY,
      Direction direction, double maxExtent) {
    this(alongX, maxSide, line, lo, hi, baseY, visualBaseY, direction, maxExtent, -1, false);
  }

  public boolean isUp() {
    return direction == Direction.UP;
  }

  public boolean isDown() {
    return direction == Direction.DOWN;
  }
}
