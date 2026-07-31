package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.kelianmao.mobwalk.client.surface.WorldGeometry.ColumnBoxes;
import dev.kelianmao.mobwalk.client.surface.HazardClass;
import dev.kelianmao.mobwalk.client.surface.WorldGeometry.WorldBox;

/**
 * Stage A1: the pure occluder-edge classification ({@code occluderSpansForRect} /
 * {@code wallOccluder}) — upward (wall/ceiling) skirt spans for a surface edge,
 * built from synthetic collision boxes (no world). Covers the wall / drop / step /
 * equal-height / dilation cases from the plan.
 */
final class OccluderClassificationTest {
  private static final double EPS = 1.0e-6;

  // A full-block collision box at block (x,y,z): footprint [x,x+1]x[z,z+1],
  // vertical [y, y+1].
  private static WorldBox block(int x, int y, int z) {
    return new WorldBox(x, y, z, x, z, x + 1, z + 1, y, y + 1);
  }

  // Non-occluding fluid surface (same shape as FluidClipContractTest.fluidSurface).
  private static WorldBox fluidSurface(int bx, int by, int bz, double height, HazardClass hazard) {
    double top = by + height;
    return new WorldBox(bx, by, bz, bx, bz, bx + 1, bz + 1, by, top, top, top, hazard, false);
  }

  private static List<SkirtSpan> classify(StandableRect r, double halfW, double height, WorldBox... boxes) {
    List<SkirtSpan> out = new ArrayList<>();
    SurfaceSelection.occluderSpansForRect(r, List.of(boxes), halfW, height, out);
    return out;
  }

  @Test
  void wallAcrossEdgeEmitsUpSpan() {
    // Floor top at T=64 over [0,1]x[0,1]; a full block rises from 64..65 to the +X.
    StandableRect floor = new StandableRect(0, 0, 1, 1, 64.0);
    List<SkirtSpan> spans = classify(floor, 0.0, 0.0, block(1, 64, 0));
    assertEquals(1, spans.size());
    SkirtSpan s = spans.get(0);
    assertEquals(false, s.alongX());          // X-edge: fixed X, varying Z
    assertEquals(1.0, s.line(), EPS);          // at the +X edge x = maxX
    assertEquals(0.0, s.lo(), EPS);
    assertEquals(1.0, s.hi(), EPS);
    assertEquals(64.0, s.baseY(), EPS);
    assertTrue(s.isUp());
    assertEquals(1.0, s.maxExtent(), EPS);     // wall top 65 − visual base 64
  }

  @Test
  void deepDropAcrossEdgeEmitsNoUpSpan() {
    // A floor three blocks below across the +X edge does not rise above T.
    StandableRect floor = new StandableRect(0, 0, 1, 1, 64.0);
    List<SkirtSpan> spans = classify(floor, 0.0, 0.0, block(1, 60, 0));
    assertTrue(spans.isEmpty());
  }

  @Test
  void stepDownNeighbourEmitsNoUpSpan() {
    // A reachable lower neighbour (top at 63) is a drop/step, not a wall.
    StandableRect floor = new StandableRect(0, 0, 1, 1, 64.0);
    List<SkirtSpan> spans = classify(floor, 0.0, 0.0, block(1, 62, 0));
    assertTrue(spans.isEmpty());
  }

  @Test
  void equalHeightNeighbourEmitsNoUpSpan() {
    // A coplanar neighbour (top at 64) is a continuation, not a wall.
    StandableRect floor = new StandableRect(0, 0, 1, 1, 64.0);
    List<SkirtSpan> spans = classify(floor, 0.0, 0.0, block(1, 63, 0));
    assertTrue(spans.isEmpty());
  }

  @Test
  void dilatedWallFoundAtSetBackEdge() {
    // Player half-width 0.3: the floor beside a +X wall is pulled back to maxX=0.7,
    // and the wall (block 1..2) is still found, its up-span sitting at the set-back
    // edge x = 0.7 (= W/2 off the real face at x = 1.0).
    StandableRect floor = new StandableRect(-0.3, -0.3, 0.7, 1.3, 64.0);
    List<SkirtSpan> spans = classify(floor, 0.3, 0.0, block(1, 64, 0));
    assertEquals(1, spans.size());
    SkirtSpan s = spans.get(0);
    assertEquals(false, s.alongX());
    assertEquals(0.7, s.line(), EPS);
    assertEquals(1.0, s.maxExtent(), EPS);
  }

  @Test
  void mergeCoalescesStackedOccluderTops() {
    // Two stacked wall boxes (64..65 and 65..66) on the same +X edge -> one span,
    // taking the taller occluder top.
    StandableRect floor = new StandableRect(0, 0, 1, 1, 64.0);
    List<SkirtSpan> spans = classify(floor, 0.0, 2.0, block(1, 64, 0), block(1, 65, 0));
    List<SkirtSpan> merged = SurfaceSelection.mergeOccluderSpans(spans);
    assertEquals(1, merged.size());
    assertEquals(2.0, merged.get(0).maxExtent(), EPS); // stop at 66 − base 64
  }

  @Test
  void fluidSurfaceAcrossEdgeEmitsNoUpSpan() {
    // A non-occluding water fluid surface abutting the +X edge rises above T the same
    // way a wall would, but occlusion is a volume property — fluid surfaces must not
    // mark up-skirts.
    StandableRect floor = new StandableRect(0, 0, 1, 1, 64.0);
    WorldBox water = fluidSurface(1, 64, 0, 8.0 / 9.0, HazardClass.WATER);
    List<SkirtSpan> spans = classify(floor, 0.0, 0.0, water);
    assertTrue(spans.isEmpty());
  }

  @Test
  void computeOccludersFromMarksWallNotFluidSurface() {
    // Port-level: wall column marks an up-skirt; water fluid-surface column marks none.
    StandableRect floor = new StandableRect(0, 0, 1, 1, 64.0);
    EntityProfile point = EntityProfile.POINT;
    ColumnBoxes wallWorld = (x, y, z) -> {
      if (x == 1 && z == 0 && y == 64) {
        return List.of(block(1, 64, 0));
      }
      return List.of();
    };
    List<SkirtSpan> wallSpans = SurfaceSelection.computeOccludersFrom(
      wallWorld, 0, 256, List.of(floor), point);
    assertEquals(1, wallSpans.size());
    assertTrue(wallSpans.get(0).isUp());

    ColumnBoxes waterWorld = (x, y, z) -> {
      if (x == 1 && z == 0 && y == 64) {
        return List.of(fluidSurface(1, 64, 0, 8.0 / 9.0, HazardClass.WATER));
      }
      return List.of();
    };
    List<SkirtSpan> waterSpans = SurfaceSelection.computeOccludersFrom(
      waterWorld, 0, 256, List.of(floor), point);
    assertTrue(waterSpans.isEmpty());
  }
}
