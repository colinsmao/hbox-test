package dev.kelianmao.mobwalk.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.kelianmao.mobwalk.client.SurfaceSelection.ColKey;
import dev.kelianmao.mobwalk.client.SurfaceSelection.WorldBox;

import org.junit.jupiter.api.Test;

/**
 * Milestone 6 Step 2a: the draw-only {@code visualTopY} raise in {@code exposeBox}.
 * A box that IS its block's topmost collision surface and whose block renders taller
 * than it collides (soul sand, mud) exposes the visible/outline top; everything else
 * keeps {@code visualTopY == topY}. The walkability math is unaffected (all keyed on
 * the collision {@code topY}); these assert only the carried visible top.
 */
final class VisualTopTest {
  private static final double EPS = 1.0e-9;

  // A single-box column (no occluders) exposed at Point (halfW=0, height=0), so the
  // box's full top survives and its visualTopY is the raise rule's output.
  private static StandableRect expose(double yMin, double yMax,
      double blockCollisionTop, double blockOutlineTop) {
    WorldBox box = new WorldBox(0, (int) Math.floor(yMin), 0,
      0, 0, 1, 1, yMin, yMax, blockCollisionTop, blockOutlineTop);
    Map<ColKey, List<WorldBox>> index = new HashMap<>();
    List<WorldBox> column = new ArrayList<>();
    column.add(box);
    index.put(new ColKey(0, 0), column);
    List<StandableRect> out = new ArrayList<>();
    SurfaceSelection.exposeBox(box, index, 0.0, 0.0, out);
    assertEquals(1, out.size());
    return out.get(0);
  }

  @Test
  void soulSandRaisesToVisibleTop() {
    // Collides at 0.875, renders as a full cube: draw on the visible face (1.0).
    StandableRect r = expose(0.0, 0.875, 0.875, 1.0);
    assertEquals(0.875, r.topY(), EPS);
    assertEquals(1.0, r.visualTopY(), EPS);
  }

  @Test
  void fullBlockVisualEqualsCollision() {
    StandableRect r = expose(0.0, 1.0, 1.0, 1.0);
    assertEquals(1.0, r.visualTopY(), EPS);
  }

  @Test
  void fenceTopNotLoweredByShorterOutline() {
    // Fence collides at 1.5 but its outline maxes at 1.0; the raise only ever
    // lifts (outline > collision), so the fence top stays at its collision top.
    StandableRect r = expose(0.0, 1.5, 1.5, 1.0);
    assertEquals(1.5, r.visualTopY(), EPS);
  }

  @Test
  void stairLowerTreadNotLifted() {
    // The lower tread's top (0.5) is NOT the block's topmost collision surface
    // (1.0), so it is not lifted even though the block outlines to 1.0.
    StandableRect r = expose(0.0, 0.5, 1.0, 1.0);
    assertEquals(0.5, r.visualTopY(), EPS);
  }

  @Test
  void mergeKeepsMatchingVisualTopsTogether() {
    // Two abutting soul-sand-like patches (same collision + visible) merge.
    List<StandableRect> merged = RectMath.mergeCoplanar(List.of(
      new StandableRect(0, 0, 1, 1, 0.875, 1.0),
      new StandableRect(1, 0, 2, 1, 0.875, 1.0)));
    assertEquals(1, merged.size());
    assertEquals(0.875, merged.get(0).topY(), EPS);
    assertEquals(1.0, merged.get(0).visualTopY(), EPS);
  }

  @Test
  void mergeDoesNotContaminateDifferentVisualTops() {
    // Same collision topY (e.g. dirt path 15/16 + honey 15/16→1.0): each keeps
    // its own visualTopY — the raised neighbour must not lift the flush path.
    List<StandableRect> merged = RectMath.mergeCoplanar(List.of(
      new StandableRect(0, 0, 1, 1, 0.9375, 0.9375),
      new StandableRect(1, 0, 2, 1, 0.9375, 1.0)));
    assertEquals(2, merged.size());
    StandableRect path = merged.stream()
      .filter(r -> Math.abs(r.visualTopY() - 0.9375) < EPS).findFirst().orElseThrow();
    StandableRect honey = merged.stream()
      .filter(r -> Math.abs(r.visualTopY() - 1.0) < EPS).findFirst().orElseThrow();
    assertEquals(0.9375, path.topY(), EPS);
    assertEquals(0.9375, path.visualTopY(), EPS);
    assertEquals(0.9375, honey.topY(), EPS);
    assertEquals(1.0, honey.visualTopY(), EPS);
  }

  @Test
  void auxiliaryConstructorDefaultsVisualToCollision() {
    StandableRect r = new StandableRect(0, 0, 1, 1, 64.0);
    assertEquals(64.0, r.visualTopY(), EPS);
  }

  // Path (col 0) + neighbour (col 1), expose the path box at Player halfW.
  private static List<StandableRect> exposePathBeside(double neighborYMax,
      double neighborCollisionTop, double neighborOutlineTop, double halfW) {
    WorldBox path = new WorldBox(0, 0, 0,
      0, 0, 1, 1, 0.0, 0.9375, 0.9375, 0.9375);
    WorldBox neighbor = new WorldBox(1, 0, 0,
      1, 0, 2, 1, 0.0, neighborYMax, neighborCollisionTop, neighborOutlineTop);
    Map<ColKey, List<WorldBox>> index = new HashMap<>();
    index.put(new ColKey(0, 0), new ArrayList<>(List.of(path)));
    index.put(new ColKey(1, 0), new ArrayList<>(List.of(neighbor)));
    List<StandableRect> out = new ArrayList<>();
    SurfaceSelection.exposeBox(path, index, halfW, 1.8, out);
    return out;
  }

  @Test
  void pathDilatedOverSoulSandRaisesOverlapOnly() {
    // Path 15/16 flush next to soul sand 14/16→1.0; Player halfW=0.3 dilates the
    // path into the soul-sand column. Overlap keeps collision topY (path) but
    // raises visualTopY to the soul-sand outline; path-only remnant stays flush.
    List<StandableRect> out = exposePathBeside(0.875, 0.875, 1.0, 0.3);
    StandableRect raised = out.stream()
      .filter(r -> Math.abs(r.visualTopY() - 1.0) < EPS).findFirst().orElseThrow();
    StandableRect flush = out.stream()
      .filter(r -> Math.abs(r.visualTopY() - 0.9375) < EPS).findFirst().orElseThrow();
    assertEquals(0.9375, raised.topY(), EPS);
    assertEquals(1.0, raised.visualTopY(), EPS);
    // Overlap is the path dilation into the soul-sand undilated column [1,2]x[0,1].
    assertEquals(1.0, raised.minX(), EPS);
    assertEquals(1.3, raised.maxX(), EPS);
    assertEquals(0.0, raised.minZ(), EPS);
    assertEquals(1.0, raised.maxZ(), EPS);
    assertEquals(0.9375, flush.topY(), EPS);
    assertEquals(0.9375, flush.visualTopY(), EPS);
  }

  @Test
  void pathDilatedOverFullBlockNeighborDoesNotRaise() {
    // Neighbour outline == collision (plain full block): not a raise candidate,
    // even though outline (1.0) is above the path top.
    List<StandableRect> out = exposePathBeside(1.0, 1.0, 1.0, 0.3);
    assertEquals(1, out.size());
    assertEquals(0.9375, out.get(0).topY(), EPS);
    assertEquals(0.9375, out.get(0).visualTopY(), EPS);
  }

  @Test
  void pathBesideSoulSandAtPointDoesNotSplit() {
    // Point halfW=0: dilated footprint stays in the path column, no overlap with
    // the soul-sand undilated core → single flush rect (existing Point behaviour).
    List<StandableRect> out = exposePathBeside(0.875, 0.875, 1.0, 0.0);
    assertEquals(1, out.size());
    assertEquals(0.9375, out.get(0).topY(), EPS);
    assertEquals(0.9375, out.get(0).visualTopY(), EPS);
    assertEquals(0.0, out.get(0).minX(), EPS);
    assertEquals(1.0, out.get(0).maxX(), EPS);
  }
}
