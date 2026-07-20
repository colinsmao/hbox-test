package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.kelianmao.mobwalk.client.surface.RectMath.Rect;

/**
 * Sanity tests over the pure rect ops in {@link RectMath}
 * ({@code subtractRects} / {@code union} / {@code mergeAll} /
 * {@code footprintAdjacent}). They build synthetic rects only — no world, no game
 * loop — and pin current behavior so later stages can refactor with a net.
 */
final class SurfaceGeometryTest {
  private static final double EPS = 1.0e-9;

  private static double area(List<Rect> rects) {
    double sum = 0.0;
    for (Rect r : rects) {
      sum += (r.maxX() - r.minX()) * (r.maxZ() - r.minZ());
    }
    return sum;
  }

  @Test
  void subtractDisjointKeepsBase() {
    Rect base = new Rect(0, 0, 4, 4);
    List<Rect> out = RectMath.subtractRects(base, List.of(new Rect(10, 10, 12, 12)));
    assertEquals(16.0, area(out), EPS);
  }

  @Test
  void subtractCenterHoleLeavesFrame() {
    Rect base = new Rect(0, 0, 3, 3);
    List<Rect> out = RectMath.subtractRects(base, List.of(new Rect(1, 1, 2, 2)));
    // 9 minus the 1x1 hole, partitioned into non-overlapping pieces.
    assertEquals(8.0, area(out), EPS);
  }

  @Test
  void subtractFullCoverLeavesNothing() {
    Rect base = new Rect(0, 0, 2, 2);
    List<Rect> out = RectMath.subtractRects(base, List.of(new Rect(-1, -1, 3, 3)));
    assertTrue(out.isEmpty());
  }

  @Test
  void unionOfOverlappingRectsCoversCombinedArea() {
    // Two unit squares overlapping in a 1x1 quarter -> union area 7 (4+4-1).
    List<Rect> out = RectMath.union(List.of(
      new Rect(0, 0, 2, 2),
      new Rect(1, 1, 3, 3)));
    assertEquals(7.0, area(out), EPS);
  }

  @Test
  void mergeAllCollapsesAbuttingStrip() {
    List<StandableRect> merged = RectMath.mergeAll(List.of(
      new StandableRect(0, 0, 1, 1, 64.0),
      new StandableRect(1, 0, 2, 1, 64.0)));
    assertEquals(1, merged.size());
    StandableRect r = merged.get(0);
    assertEquals(0.0, r.minX(), EPS);
    assertEquals(2.0, r.maxX(), EPS);
  }

  @Test
  void mergeAllKeepsDistinctHeights() {
    List<StandableRect> merged = RectMath.mergeAll(List.of(
      new StandableRect(0, 0, 1, 1, 64.0),
      new StandableRect(1, 0, 2, 1, 65.0)));
    assertEquals(2, merged.size());
  }

  @Test
  void footprintAdjacentEdgeSharingConnects() {
    StandableRect a = new StandableRect(0, 0, 1, 1, 64.0);
    StandableRect b = new StandableRect(1, 0, 2, 1, 64.0);
    assertTrue(RectMath.footprintAdjacent(a, b));
  }

  @Test
  void footprintAdjacentDiagonalDoesNotConnect() {
    StandableRect a = new StandableRect(0, 0, 1, 1, 64.0);
    StandableRect b = new StandableRect(1, 1, 2, 2, 64.0);
    assertFalse(RectMath.footprintAdjacent(a, b));
  }

  @Test
  void footprintAdjacentOverlapConnects() {
    StandableRect a = new StandableRect(0, 0, 2, 2, 64.0);
    StandableRect b = new StandableRect(1, 1, 3, 3, 64.0);
    assertTrue(RectMath.footprintAdjacent(a, b));
  }

  @Test
  void downSkirtInheritsSurfaceDepth() {
    // A skirt span carries its source rect's flood-depth so the two share a band.
    StandableRect r = new StandableRect(0, 0, 1, 1, 64.0, 64.0, 3);
    List<SkirtSpan> spans = SurfaceSelection.computeDownSkirts(List.of(r), List.of(), false);
    assertFalse(spans.isEmpty());
    for (SkirtSpan s : spans) {
      assertEquals(3, s.depth());
    }
  }

  @Test
  void splitFrontierKeepsFrontierSeparateFromInner() {
    // Three abutting same-height nodes at depths {0, 1, 2} with limit=2.
    // Without the frontier split, a plain coplanar merge would collapse them
    // into one rect with depth 0. With frontier split, the depth-2 node stays separate.
    List<StandableRect> nodes = List.of(
      new StandableRect(0, 0, 1, 1, 64.0),
      new StandableRect(1, 0, 2, 1, 64.0),
      new StandableRect(2, 0, 3, 1, 64.0));
    int[] depths = {0, 1, 2};
    int limit = 2;
    List<StandableRect> result = RectMath.mergeCoplanarSplitFrontier(
      nodes, depths, limit);

    // Must produce at least two rects: one inner, one frontier.
    assertTrue(result.size() >= 2,
      "expected inner + frontier, got " + result.size() + " rects");

    boolean hasInner = false;
    boolean hasFrontier = false;
    for (StandableRect r : result) {
      if (r.depth() < limit) {
        hasInner = true;
      }
      if (r.depth() >= limit) {
        hasFrontier = true;
      }
    }
    assertTrue(hasInner, "no inner rect found");
    assertTrue(hasFrontier, "no frontier rect found");
  }

  @Test
  void splitFrontierAbutsInner() {
    // Frontier and inner rects must share an edge so seam suppression applies.
    List<StandableRect> nodes = List.of(
      new StandableRect(0, 0, 1, 1, 64.0),
      new StandableRect(1, 0, 2, 1, 64.0),
      new StandableRect(2, 0, 3, 1, 64.0));
    int[] depths = {0, 1, 2};
    int limit = 2;
    List<StandableRect> result = RectMath.mergeCoplanarSplitFrontier(
      nodes, depths, limit);

    StandableRect inner = null;
    StandableRect frontier = null;
    for (StandableRect r : result) {
      if (r.depth() >= limit) {
        frontier = r;
      } else {
        // Pick the inner rect closest to the frontier.
        if (inner == null || r.maxX() > inner.maxX()) {
          inner = r;
        }
      }
    }
    assert inner != null && frontier != null;
    // They should abut: inner's maxX == frontier's minX (or vice versa).
    boolean abuts = Math.abs(inner.maxX() - frontier.minX()) < EPS
      || Math.abs(frontier.maxX() - inner.minX()) < EPS
      || Math.abs(inner.maxZ() - frontier.minZ()) < EPS
      || Math.abs(frontier.maxZ() - inner.minZ()) < EPS;
    assertTrue(abuts, "frontier and inner rects should share an edge");
  }

  @Test
  void splitFrontierPreservesArea() {
    // The total area after frontier-split merge must equal the input area.
    List<StandableRect> nodes = List.of(
      new StandableRect(0, 0, 1, 1, 64.0),
      new StandableRect(1, 0, 2, 1, 64.0),
      new StandableRect(2, 0, 3, 1, 64.0),
      new StandableRect(3, 0, 4, 1, 64.0));
    int[] depths = {0, 1, 2, 3};
    int limit = 3;
    List<StandableRect> result = RectMath.mergeCoplanarSplitFrontier(
      nodes, depths, limit);

    double inputArea = 4.0;
    double outputArea = 0.0;
    for (StandableRect r : result) {
      outputArea += (r.maxX() - r.minX()) * (r.maxZ() - r.minZ());
    }
    assertEquals(inputArea, outputArea, EPS);
  }

  @Test
  void splitFrontierAllInnerNoFrontier() {
    // When no node is at the limit, all nodes merge into one inner rect.
    List<StandableRect> nodes = List.of(
      new StandableRect(0, 0, 1, 1, 64.0),
      new StandableRect(1, 0, 2, 1, 64.0));
    int[] depths = {0, 1};
    int limit = 5;
    List<StandableRect> result = RectMath.mergeCoplanarSplitFrontier(
      nodes, depths, limit);

    assertEquals(1, result.size());
    assertTrue(result.get(0).depth() < limit);
    assertEquals(0.0, result.get(0).minX(), EPS);
    assertEquals(2.0, result.get(0).maxX(), EPS);
  }
}
