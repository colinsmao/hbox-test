package dev.kelianmao.mobwalk.client.surface;

import java.util.List;

/**
 * The complete published output of one {@link SurfaceSelection#select}: the drawn
 * surfaces plus every compute-side edge pass derived from them. The five lists are
 * always produced and consumed as a group, so they travel as one immutable object —
 * publishing is then a single reference write and the render thread can never read a
 * torn mix (new rects with the previous selection's holes). All five are computed
 * once per select, so emit just draws them.
 *
 * <p>Deeply immutable: the passes each return a live {@code ArrayList}, so the compact
 * constructor copies them.
 *
 * @param rects      reached, merged standable surfaces (the draw set), colored at draw.
 * @param occluders  upward skirt spans where an edge meets a wall or a ceiling within
 *                   the entity's headroom (the paint rim when raises are active).
 *                   See {@link OccluderSkirts#compute}.
 * @param downSkirts downward drop-skirt spans: each merged-rect edge minus its
 *                   equal-height merge seams minus the occluder sub-spans above.
 *                   See {@link DownSkirts#compute}.
 * @param holes      through-walls beam spans for drops with no reached surface below
 *                   (void, or unreached ground); {@link HazardClass#HOLE}.
 *                   See {@link HoleBeams#compute}.
 * @param hazards    hazard perimeter beam spans (hazard rect edges minus same-hazard
 *                   seams). See {@link HazardBeams#compute}.
 */
public record SelectionSnapshot(
    List<StandableRect> rects,
    List<SkirtSpan> occluders,
    List<SkirtSpan> downSkirts,
    List<BeamSpan> holes,
    List<BeamSpan> hazards) {

  /** Nothing selected: what a cleared selection and a level change publish. */
  public static final SelectionSnapshot EMPTY =
    new SelectionSnapshot(List.of(), List.of(), List.of(), List.of(), List.of());

  public SelectionSnapshot {
    rects = List.copyOf(rects);
    occluders = List.copyOf(occluders);
    downSkirts = List.copyOf(downSkirts);
    holes = List.copyOf(holes);
    hazards = List.copyOf(hazards);
  }

  /**
   * Whether anything is drawn. Keyed on {@link #rects()} <b>alone</b>: the edge passes
   * are all derived from the reached surfaces, so no rects means nothing to draw at
   * all — this is the single gate for both the overlay's draw visibility and
   * {@code SurfaceEmitter}'s early-out (which also suppresses skirts and beams).
   */
  public boolean isEmpty() {
    return rects.isEmpty();
  }
}
