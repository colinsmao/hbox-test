package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.ColKey;
import dev.kelianmao.mobwalk.client.surface.WorldGeometry.WorldBox;

/**
 * Scaffolding collision boxes are non-occluding support surfaces: standable tops
 * that skip burial/headroom clip (same {@code occludes=false} gate as fluids).
 */
final class ScaffoldingOcclusionTest {
  private static final double EPS = 1.0e-9;
  /** Vanilla stable scaffolding lid: top plate from 14/16 to 1. */
  private static final double LID_MIN = 14.0 / 16.0;
  private static final double HEIGHT = EntityProfile.PLAYER.height();
  private static final double HALF_W = EntityProfile.PLAYER.width() / 2.0;

  @Test
  void occludingStackedLidClipsLowerViaHeadroom() {
    // Documents the failure mode: with occludes=true the upper lid sits in the
    // lower's standing column and wipes it (player H=1.8).
    WorldBox lower = scaffoldingLid(0, 64, 0, true);
    WorldBox upper = scaffoldingLid(0, 65, 0, true);
    List<StandableRect> out = expose(lower, 0.0, HEIGHT, lower, upper);
    assertEquals(0.0, area(out), EPS, "occluding upper lid must headroom-clip lower");
  }

  @Test
  void nonOccludingStackedLidLeavesLowerIntact() {
    WorldBox lower = scaffoldingLid(0, 64, 0, false);
    WorldBox upper = scaffoldingLid(0, 65, 0, false);
    List<StandableRect> out = expose(lower, 0.0, HEIGHT, lower, upper);
    assertEquals(1.0, area(out), EPS, "non-occluding upper lid must not clip lower");
    assertEquals(65.0, out.get(0).collisionTopY(), EPS);
  }

  @Test
  void floorUnderNonOccludingLidKeepsHeadroom() {
    WorldBox floor = solid(0, 63, 0, 63.0, 64.0);
    WorldBox lid = scaffoldingLid(0, 64, 0, false);
    List<StandableRect> out = expose(floor, 0.0, HEIGHT, floor, lid);
    assertEquals(1.0, area(out), EPS, "scaffolding lid must not rob floor headroom");
  }

  @Test
  void solidStillClipsNonOccludingLid() {
    WorldBox lid = scaffoldingLid(0, 64, 0, false);
    // Full block in the cell above — buried over the lid top at 65.
    WorldBox ceiling = solid(0, 65, 0, 65.0, 66.0);
    List<StandableRect> out = expose(lid, HALF_W, HEIGHT, lid, ceiling);
    assertEquals(0.0, area(out), EPS, "solids still clip a scaffolding top");
  }

  private static WorldBox scaffoldingLid(int bx, int by, int bz, boolean occludes) {
    double yMin = by + LID_MIN;
    double yMax = by + 1.0;
    return new WorldBox(bx, by, bz, bx, bz, bx + 1, bz + 1, yMin, yMax, yMax, yMax,
      HazardClass.NONE, occludes);
  }

  private static WorldBox solid(int bx, int by, int bz, double yMin, double yMax) {
    return new WorldBox(bx, by, bz, bx, bz, bx + 1, bz + 1, yMin, yMax, yMax, yMax,
      HazardClass.NONE, true);
  }

  private static List<StandableRect> expose(WorldBox target, double halfW, double height,
      WorldBox... boxes) {
    Map<ColKey, List<WorldBox>> index = new HashMap<>();
    for (WorldBox b : boxes) {
      index.computeIfAbsent(new ColKey(b.bx(), b.bz()), k -> new ArrayList<>()).add(b);
    }
    List<StandableRect> out = new ArrayList<>();
    SurfaceSelection.exposeBox(target, index, halfW, height, out);
    return out;
  }

  private static double area(List<StandableRect> rects) {
    double sum = 0.0;
    for (StandableRect r : rects) {
      sum += (r.maxX() - r.minX()) * (r.maxZ() - r.minZ());
    }
    return sum;
  }
}
