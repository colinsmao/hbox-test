package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.WorldBox;

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

  private static List<OccluderSpan> classify(StandableRect r, double halfW, double height, WorldBox... boxes) {
    List<OccluderSpan> out = new ArrayList<>();
    SurfaceSelection.occluderSpansForRect(r, List.of(boxes), halfW, height, out);
    return out;
  }

  @Test
  void wallAcrossEdgeEmitsUpSpan() {
    // Floor top at T=64 over [0,1]x[0,1]; a full block rises from 64..65 to the +X.
    StandableRect floor = new StandableRect(0, 0, 1, 1, 64.0);
    List<OccluderSpan> spans = classify(floor, 0.0, 0.0, block(1, 64, 0));
    assertEquals(1, spans.size());
    OccluderSpan s = spans.get(0);
    assertEquals(false, s.alongX());          // X-edge: fixed X, varying Z
    assertEquals(1.0, s.line(), EPS);          // at the +X edge x = maxX
    assertEquals(0.0, s.lo(), EPS);
    assertEquals(1.0, s.hi(), EPS);
    assertEquals(64.0, s.baseY(), EPS);
    assertEquals(65.0, s.topY(), EPS);         // occluder top = box yMax
  }

  @Test
  void deepDropAcrossEdgeEmitsNoUpSpan() {
    // A floor three blocks below across the +X edge does not rise above T.
    StandableRect floor = new StandableRect(0, 0, 1, 1, 64.0);
    List<OccluderSpan> spans = classify(floor, 0.0, 0.0, block(1, 60, 0));
    assertTrue(spans.isEmpty());
  }

  @Test
  void stepDownNeighbourEmitsNoUpSpan() {
    // A reachable lower neighbour (top at 63) is a drop/step, not a wall.
    StandableRect floor = new StandableRect(0, 0, 1, 1, 64.0);
    List<OccluderSpan> spans = classify(floor, 0.0, 0.0, block(1, 62, 0));
    assertTrue(spans.isEmpty());
  }

  @Test
  void equalHeightNeighbourEmitsNoUpSpan() {
    // A coplanar neighbour (top at 64) is a continuation, not a wall.
    StandableRect floor = new StandableRect(0, 0, 1, 1, 64.0);
    List<OccluderSpan> spans = classify(floor, 0.0, 0.0, block(1, 63, 0));
    assertTrue(spans.isEmpty());
  }

  @Test
  void dilatedWallFoundAtSetBackEdge() {
    // Player half-width 0.3: the floor beside a +X wall is pulled back to maxX=0.7,
    // and the wall (block 1..2) is still found, its up-span sitting at the set-back
    // edge x = 0.7 (= W/2 off the real face at x = 1.0).
    StandableRect floor = new StandableRect(-0.3, -0.3, 0.7, 1.3, 64.0);
    List<OccluderSpan> spans = classify(floor, 0.3, 0.0, block(1, 64, 0));
    assertEquals(1, spans.size());
    OccluderSpan s = spans.get(0);
    assertEquals(false, s.alongX());
    assertEquals(0.7, s.line(), EPS);
    assertEquals(65.0, s.topY(), EPS);
  }

  @Test
  void mergeCoalescesStackedOccluderTops() {
    // Two stacked wall boxes (64..65 and 65..66) on the same +X edge -> one span,
    // taking the taller occluder top.
    StandableRect floor = new StandableRect(0, 0, 1, 1, 64.0);
    List<OccluderSpan> spans = classify(floor, 0.0, 2.0, block(1, 64, 0), block(1, 65, 0));
    List<OccluderSpan> merged = SurfaceSelection.mergeOccluderSpans(spans);
    assertEquals(1, merged.size());
    assertEquals(66.0, merged.get(0).topY(), EPS);
  }
}
