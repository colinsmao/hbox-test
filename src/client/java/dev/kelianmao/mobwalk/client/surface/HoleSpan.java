package dev.kelianmao.mobwalk.client.surface;

/**
 * One <b>hole</b> drop-edge span: a drop sub-span (a {@link SkirtSpan} with
 * {@link SkirtSpan.Direction#DOWN}) that the Milestone 5 classifier
 * ({@code SurfaceSelection.classifyDrop}) labelled a trap — a mob leaving this
 * edge falls into the void, or onto a topmost landing that is not in the reached
 * set, and cannot climb back. Marked by a <b>through-walls vertical beam</b>
 * rising from the cliff-edge top {@code T}, so it reads even when the rim is
 * occluded by terrain (drawn in the depth-off {@code FILLED} pipeline).
 *
 * <p>Computed on the client/extraction thread (it reads collision boxes below the
 * fall footprint — see {@code SurfaceSelection.computeHoles}) once per select, and
 * published in the snapshot alongside the {@link SkirtSpan}s. Geometry mirrors a
 * down skirt (the beam is drawn at the same rim); {@code fallDistance} is carried
 * for later refinement (Step 4/5).
 *
 * @param alongX       {@code true} when the edge runs along X at a fixed Z
 *                     ({@code line} is Z, {@code [lo,hi]} the X interval); else along Z.
 * @param maxSide      {@code true} for the {@code +axis} edge of the source rect.
 * @param line         the fixed coordinate of the rim edge (Z if {@code alongX}, else X).
 * @param lo           start of the hole sub-span on its varying axis.
 * @param hi           end of the hole sub-span on its varying axis.
 * @param baseY        the cliff-edge top {@code T} the beam rises from.
 * @param fallDistance {@code T - landing.topY} for a trap landing, or {@code 0} for the
 *                     void (no landing at all).
 * @param visualBaseY  the source rect's visible-face top (draw-only): where the beam
 *                     rises from when the renderer draws on the visible face; equals
 *                     {@code baseY} except for render-taller-than-collide blocks. See
 *                     {@link StandableRect}.
 */
public record HoleSpan(boolean alongX, boolean maxSide, double line,
    double lo, double hi, double baseY, double fallDistance, double visualBaseY) {
  /** A span whose visible base coincides with its collision base (the common case). */
  public HoleSpan(boolean alongX, boolean maxSide, double line,
      double lo, double hi, double baseY, double fallDistance) {
    this(alongX, maxSide, line, lo, hi, baseY, fallDistance, baseY);
  }
}
