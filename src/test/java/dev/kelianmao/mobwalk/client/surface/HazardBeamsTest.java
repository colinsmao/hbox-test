package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Hazard perimeter beams: same-hazard equal-collisionTopY abutters suppress interior
 * seams; leftovers keep BeamSpan.hazard; water|lava abutting edges stay separate.
 */
final class HazardBeamsTest {
  private static final double EPS = 1.0e-6;

  private static BeamSpan find(List<BeamSpan> spans, boolean alongX, double line,
      double lo, double hi) {
    BeamSpan found = null;
    for (BeamSpan s : spans) {
      if (s.alongX() == alongX
          && Math.abs(s.line() - line) < EPS
          && Math.abs(s.lo() - lo) < EPS
          && Math.abs(s.hi() - hi) < EPS) {
        assertTrue(found == null, "duplicate beam span");
        found = s;
      }
    }
    return found;
  }

  @Test
  void loneWaterRectBeamsAllFourEdges() {
    StandableRect water = new StandableRect(0, 0, 1, 1, 64.0, 64.0, HazardClass.WATER);
    List<BeamSpan> beams = HazardBeams.compute(List.of(water));
    assertEquals(4, beams.size());
    for (BeamSpan s : beams) {
      assertEquals(HazardClass.WATER, s.hazard());
    }
  }

  @Test
  void sameHazardSeamSuppressesInterior() {
    // Two water rects abutting on x=1 at same collisionTopY: shared edge has no beam.
    StandableRect left = new StandableRect(0, 0, 1, 1, 64.0, 64.0, HazardClass.WATER);
    StandableRect right = new StandableRect(1, 0, 2, 1, 64.0, 64.0, HazardClass.WATER);
    List<BeamSpan> beams = HazardBeams.compute(List.of(left, right));
    assertTrue(find(beams, false, 1.0, 0.0, 1.0) == null,
      "shared +X of left / -X of right is an interior seam");
    assertTrue(find(beams, false, 0.0, 0.0, 1.0) != null, "left -X remains");
    assertTrue(find(beams, false, 2.0, 0.0, 1.0) != null, "right +X remains");
  }

  @Test
  void waterLavaAbutKeepsBothKinds() {
    StandableRect water = new StandableRect(0, 0, 1, 1, 64.0, 64.0, HazardClass.WATER);
    StandableRect lava = new StandableRect(1, 0, 2, 1, 64.0, 64.0, HazardClass.LAVA);
    List<BeamSpan> beams = HazardBeams.compute(List.of(water, lava));
    BeamSpan waterFace = null;
    BeamSpan lavaFace = null;
    for (BeamSpan s : beams) {
      if (s.alongX() || Math.abs(s.line() - 1.0) > EPS) {
        continue;
      }
      if (s.hazard() == HazardClass.WATER) {
        waterFace = s;
      } else if (s.hazard() == HazardClass.LAVA) {
        lavaFace = s;
      }
    }
    assertTrue(waterFace != null, "water still beams its lava-facing edge");
    assertTrue(lavaFace != null, "lava still beams its water-facing edge");
  }

  @Test
  void frontierHazardEmitsNoBeams() {
    StandableRect water = new StandableRect(0, 0, 1, 1, 64.0, 64.0, HazardClass.WATER, 0, true);
    assertEquals(0, HazardBeams.compute(List.of(water)).size());
  }

  @Test
  void dryRectEmitsNoHazardBeams() {
    StandableRect dry = new StandableRect(0, 0, 1, 1, 64.0);
    assertEquals(0, HazardBeams.compute(List.of(dry)).size());
  }
}
