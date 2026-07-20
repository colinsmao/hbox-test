package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.ColKey;
import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.WorldBox;

/**
 * Stage B2: the headroom occlusion predicate in {@code exposeBox} — a box top at
 * {@code T} survives only where the standing column {@code (T, T+H]} is clear of
 * collision boxes. Built from synthetic boxes in a single-column index (no world).
 */
final class HeadroomTest {
  private static final double EPS = 1.0e-9;

  // A box at block column (0,0): footprint [minX,maxX]x[minZ,maxZ], vertical [yMin,yMax].
  private static WorldBox box(double minX, double minZ, double maxX, double maxZ, double yMin, double yMax) {
    return new WorldBox(0, (int) Math.floor(yMin), 0, minX, minZ, maxX, maxZ, yMin, yMax);
  }

  // Expose the floor's top against the given ceilings, all in column (0,0).
  private static List<StandableRect> expose(WorldBox floor, double height, WorldBox... ceilings) {
    Map<ColKey, List<WorldBox>> index = new HashMap<>();
    List<WorldBox> column = new ArrayList<>();
    column.add(floor);
    for (WorldBox c : ceilings) {
      column.add(c);
    }
    index.put(new ColKey(0, 0), column);
    List<StandableRect> out = new ArrayList<>();
    SurfaceSelection.exposeBox(floor, index, 0.0, height, out);
    return out;
  }

  private static double area(List<StandableRect> rects) {
    double sum = 0.0;
    for (StandableRect r : rects) {
      sum += (r.maxX() - r.minX()) * (r.maxZ() - r.minZ());
    }
    return sum;
  }

  @Test
  void clearColumnSurvivesFull() {
    WorldBox floor = box(0, 0, 1, 1, 63, 64);
    assertEquals(1.0, area(expose(floor, 1.8)), EPS);
  }

  @Test
  void ceilingWithinHeadroomBuriesFloor() {
    // Player H=1.8: a block one above (65..66) intrudes into (64, 65.8].
    WorldBox floor = box(0, 0, 1, 1, 63, 64);
    WorldBox ceiling = box(0, 0, 1, 1, 65, 66);
    assertTrue(expose(floor, 1.8, ceiling).isEmpty());
  }

  @Test
  void twoHighClearanceSurvives() {
    // Ceiling at 66..67 leaves the column (64, 65.8] clear.
    WorldBox floor = box(0, 0, 1, 1, 63, 64);
    WorldBox ceiling = box(0, 0, 1, 1, 66, 67);
    assertEquals(1.0, area(expose(floor, 1.8, ceiling)), EPS);
  }

  @Test
  void ceilingBottomExactlyAtHeadroomDoesNotBlock() {
    // yMin == T+H (65.8) is just-enough clearance (strict upper bound).
    WorldBox floor = box(0, 0, 1, 1, 63, 64);
    WorldBox ceiling = box(0, 0, 1, 1, 65.8, 66.8);
    assertEquals(1.0, area(expose(floor, 1.8, ceiling)), EPS);
  }

  @Test
  void partialOverhangLeavesPartialSurface() {
    // A ceiling over half the floor removes only that half.
    WorldBox floor = box(0, 0, 1, 1, 63, 64);
    WorldBox ceiling = box(0, 0, 0.5, 1, 65, 66);
    assertEquals(0.5, area(expose(floor, 1.8, ceiling)), EPS);
  }

  @Test
  void pointHeightReproducesBuriedTest() {
    // H=0: a box spanning T (63.5..64.5) buries; a coplanar/own box (yMax==T) does not.
    WorldBox floor = box(0, 0, 1, 1, 63, 64);
    WorldBox spanning = box(0, 0, 1, 1, 63.5, 64.5);
    assertTrue(expose(floor, 0.0, spanning).isEmpty());

    WorldBox coplanar = box(0, 0, 1, 1, 63, 64);
    assertEquals(1.0, area(expose(floor, 0.0, coplanar)), EPS);
  }

  @Test
  void directlyOnTopBoxBuriesAtPointHeight() {
    // Regression: a block resting DIRECTLY on the surface (yMin == T) must occlude
    // even at H=0 (the embedded/stacked-top case). The headroom term yMin < T+H is
    // false here (yMin == T, H == 0), so this relies on the buried term yMin <= T.
    WorldBox floor = box(0, 0, 1, 1, 63, 64);
    WorldBox onTop = box(0, 0, 1, 1, 64, 65);
    assertTrue(expose(floor, 0.0, onTop).isEmpty());
  }

  @Test
  void embeddedTopBuriedForEveryProfileHeight() {
    // A full block stacked directly on another: the lower top is embedded and must
    // never be standable for Point (0), Player (1.8), or Ravager (2.2).
    WorldBox floor = box(0, 0, 1, 1, 63, 64);
    WorldBox onTop = box(0, 0, 1, 1, 64, 65);
    assertTrue(expose(floor, 0.0, onTop).isEmpty());
    assertTrue(expose(floor, 1.8, onTop).isEmpty());
    assertTrue(expose(floor, 2.2, onTop).isEmpty());
  }

  @Test
  void partialOnTopBoxLeavesPartialSurfaceAtPointHeight() {
    // A block covering half the surface directly on top (yMin == T) at H=0 leaves
    // only the uncovered half.
    WorldBox floor = box(0, 0, 1, 1, 63, 64);
    WorldBox onTop = box(0, 0, 0.5, 1, 64, 65);
    assertEquals(0.5, area(expose(floor, 0.0, onTop)), EPS);
  }

  /**
   * Occluders-from-below geometry (motivating case: lantern on a wall). Standing
   * lantern body (7/16) is wider than its cap (4/16); under Ravager dilation the
   * cap leaves a body ring unless a wall rising from the block below is in the
   * occluder index — the same reason {@code gatherLedges} scans
   * {@code floor(landY) - 1}.
   */
  @Test
  void ravagerLanternBodyRingSurvivesCapAlone() {
    double halfW = EntityProfile.RAVAGER.width() / 2.0;
    double height = EntityProfile.RAVAGER.height();
    WorldBox body = box(5.0 / 16.0, 5.0 / 16.0, 11.0 / 16.0, 11.0 / 16.0, 57.0, 57.0 + 7.0 / 16.0);
    WorldBox cap = box(6.0 / 16.0, 6.0 / 16.0, 10.0 / 16.0, 10.0 / 16.0, 57.0 + 7.0 / 16.0, 57.0 + 9.0 / 16.0);
    List<StandableRect> exposed = exposeDilated(body, halfW, height, cap);
    assertTrue(area(exposed) > EPS, "body ring must survive when wall is absent");
    for (StandableRect r : exposed) {
      assertEquals(57.0 + 7.0 / 16.0, r.collisionTopY(), EPS);
    }
  }

  @Test
  void wallRisingThroughLanternBuriesBodyTopForRavager() {
    double halfW = EntityProfile.RAVAGER.width() / 2.0;
    double height = EntityProfile.RAVAGER.height();
    // Wall post [4,12]/16 × [0,1.5] from y=56 (same column as the lantern).
    WorldBox wall = box(4.0 / 16.0, 4.0 / 16.0, 12.0 / 16.0, 12.0 / 16.0, 56.0, 57.5);
    WorldBox body = box(5.0 / 16.0, 5.0 / 16.0, 11.0 / 16.0, 11.0 / 16.0, 57.0, 57.0 + 7.0 / 16.0);
    WorldBox cap = box(6.0 / 16.0, 6.0 / 16.0, 10.0 / 16.0, 10.0 / 16.0, 57.0 + 7.0 / 16.0, 57.0 + 9.0 / 16.0);
    assertTrue(exposeDilated(body, halfW, height, wall, cap).isEmpty());
  }

  private static List<StandableRect> exposeDilated(WorldBox target, double halfW, double height,
      WorldBox... others) {
    Map<ColKey, List<WorldBox>> index = new HashMap<>();
    List<WorldBox> column = new ArrayList<>();
    column.add(target);
    for (WorldBox o : others) {
      column.add(o);
    }
    index.put(new ColKey(0, 0), column);
    List<StandableRect> out = new ArrayList<>();
    SurfaceSelection.exposeBox(target, index, halfW, height, out);
    return out;
  }
}
