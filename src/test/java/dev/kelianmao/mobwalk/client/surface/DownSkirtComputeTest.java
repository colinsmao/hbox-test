package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Milestone 5 Step 2: the compute-side down-skirt extraction
 * ({@code SurfaceSelection.computeDownSkirts}) reproduces the old render-side
 * {@code openSpans}-minus-occluders logic on synthetic rects (no world). Behavior
 * must be pixel-identical, so these assert the exact drop sub-spans a rect edge
 * yields: a lone rect skirts all four edges fully; an equal-height merge seam is
 * suppressed (partial coverage leaves the unshared remainder); and a wall/ceiling
 * occluder sub-span on an edge is subtracted (it gets an upward skirt instead).
 */
final class DownSkirtComputeTest {
  private static final double EPS = 1.0e-6;

  private static SkirtSpan find(List<SkirtSpan> spans, boolean alongX, boolean maxSide, double line) {
    SkirtSpan found = null;
    for (SkirtSpan s : spans) {
      if (s.alongX() == alongX && s.maxSide() == maxSide && Math.abs(s.line() - line) < EPS) {
        assertTrue(found == null, "expected a single span per edge in these fixtures");
        found = s;
      }
    }
    return found;
  }

  @Test
  void loneRectSkirtsAllFourEdgesFully() {
    StandableRect r = new StandableRect(0, 0, 1, 1, 64.0);
    List<SkirtSpan> spans = SurfaceSelection.computeDownSkirts(List.of(r), List.of(), false);
    assertEquals(4, spans.size());
    // -Z / +Z edges run along X over [0,1]; -X / +X edges run along Z over [0,1].
    SkirtSpan minZ = find(spans, true, false, 0.0);
    SkirtSpan maxZ = find(spans, true, true, 1.0);
    SkirtSpan minX = find(spans, false, false, 0.0);
    SkirtSpan maxX = find(spans, false, true, 1.0);
    for (SkirtSpan s : List.of(minZ, maxZ, minX, maxX)) {
      assertEquals(0.0, s.lo(), EPS);
      assertEquals(1.0, s.hi(), EPS);
      assertEquals(64.0, s.baseY(), EPS);
      assertTrue(s.isDown());
      assertEquals(SkirtSpan.UNLIMITED, s.maxExtent());
    }
  }

  @Test
  void equalHeightSeamIsSuppressed() {
    // Two coplanar rects abutting along X at x = 1 (a merge seam): the shared
    // edge drops no skirt; the four outer edges do.
    StandableRect left = new StandableRect(0, 0, 1, 1, 64.0);
    StandableRect right = new StandableRect(1, 0, 2, 1, 64.0);
    List<SkirtSpan> spans = SurfaceSelection.computeDownSkirts(List.of(left, right), List.of(), false);
    // left's +X edge (x = 1) and right's -X edge (x = 1) are both fully shared.
    assertTrue(find(spans, false, true, 1.0) == null, "left +X seam suppressed");
    assertTrue(find(spans, false, false, 1.0) == null, "right -X seam suppressed");
    // Outer edges survive: left -X at x = 0, right +X at x = 2.
    assertTrue(find(spans, false, false, 0.0) != null);
    assertTrue(find(spans, false, true, 2.0) != null);
  }

  @Test
  void partialSeamLeavesUnsharedRemainder() {
    // A big rect [0,2]x[0,1] and a sliver [2,3]x[0,0.4] abutting its +X edge over
    // only z in [0,0.4]: the +X edge drops a skirt only over the unshared z [0.4,1].
    StandableRect big = new StandableRect(0, 0, 2, 1, 64.0);
    StandableRect sliver = new StandableRect(2, 0, 3, 0.4, 64.0);
    List<SkirtSpan> spans = SurfaceSelection.computeDownSkirts(List.of(big, sliver), List.of(), false);
    // Big's +X edge (x = 2) leftover after the [0,0.4] seam -> [0.4,1].
    SkirtSpan remainder = null;
    for (SkirtSpan s : spans) {
      if (!s.alongX() && s.maxSide() && Math.abs(s.line() - 2.0) < EPS && s.baseY() == 64.0
          && s.lo() > 0.3) {
        remainder = s;
      }
    }
    assertTrue(remainder != null, "expected an unshared remainder on the +X edge");
    assertEquals(0.4, remainder.lo(), EPS);
    assertEquals(1.0, remainder.hi(), EPS);
  }

  @Test
  void occluderSubSpanIsSubtracted() {
    // A wall on the +X edge (an UP skirt over the full edge) turns that edge into
    // an upward skirt, so no downward skirt is emitted there; the other three
    // edges still drop.
    StandableRect r = new StandableRect(0, 0, 1, 1, 64.0);
    SkirtSpan wall = new SkirtSpan(false, true, 1.0, 0.0, 1.0, 64.0, 64.0,
      SkirtSpan.Direction.UP, 1.0);
    List<SkirtSpan> spans = SurfaceSelection.computeDownSkirts(List.of(r), List.of(wall), false);
    assertTrue(find(spans, false, true, 1.0) == null, "+X edge is a wall, no down skirt");
    assertEquals(3, spans.size());
  }

  @Test
  void visualStepBetweenSameCollisionTopRaisesSkirt() {
    // The neighbour-raise split: a path lip drawn on a soul-sand cube top
    // (visualTopY 65.0) abuts the flush path proper (visualTopY 64.9375); both share
    // collisionTopY 64.9375. The collision pass sees a seam, the visual pass a step
    // that only the raised (higher visual) side skirts — the flush side faces a
    // taller neighbour and must not hang a reverse down-skirt into it.
    StandableRect flush = new StandableRect(0, 0, 1, 1, 64.9375, 64.9375);
    StandableRect raised = new StandableRect(1, 0, 2, 1, 64.9375, 65.0);
    List<SkirtSpan> drop =
      SurfaceSelection.computeDownSkirts(List.of(flush, raised), List.of(), false);
    assertTrue(find(drop, false, true, 1.0) == null, "collision seam: no drop skirt (flush +X)");
    assertTrue(find(drop, false, false, 1.0) == null, "collision seam: no drop skirt (raised -X)");
    List<SkirtSpan> skirts =
      SurfaceSelection.computeDownSkirts(List.of(flush, raised), List.of(), true);
    assertTrue(find(skirts, false, true, 1.0) == null, "flush faces taller neighbour: no reverse skirt");
    SkirtSpan step = find(skirts, false, false, 1.0);
    assertTrue(step != null, "visible step: raised -X skirts toward flush");
    assertEquals(65.0, step.visualBaseY(), EPS);
  }

  @Test
  void mergedCrossVisualOverlapHasNoFalseCollisionDrop() {
    // Dump-shaped fixture: overlapping flush + raised at one collisionTopY.
    // After the merge contract fix they tile (abut, no overlap); the collision
    // down-skirt pass must see a seam at the shared rim, not a false drop that
    // would feed hole beams with fall=0.
    List<StandableRect> merged = RectMath.mergeAll(List.of(
      new StandableRect(0, 0, 2, 1, 1.0, 1.0),
      new StandableRect(1, 0, 3, 1, 1.0, 1.25)));
    List<SkirtSpan> drops =
      SurfaceSelection.computeDownSkirts(merged, List.of(), false);
    assertTrue(find(drops, false, true, 1.0) == null,
      "shared rim at x=1 must not be a collision drop after merge");
    assertTrue(find(drops, false, false, 1.0) == null,
      "shared rim at x=1 must not be a collision drop after merge");
  }

  @Test
  void sameVisualTopDifferentCollisionSuppressesSkirt() {
    // Soul sand's own remnant (64.875 collision, 65.0 visual) abutting the path lip
    // (64.9375 collision, 65.0 visual): equal visualTopY, so the visual pass draws no
    // skirt between them even though their collision tops differ.
    StandableRect remnant = new StandableRect(0, 0, 1, 1, 64.875, 65.0);
    StandableRect lip = new StandableRect(1, 0, 2, 1, 64.9375, 65.0);
    List<SkirtSpan> skirts =
      SurfaceSelection.computeDownSkirts(List.of(remnant, lip), List.of(), true);
    assertTrue(find(skirts, false, true, 1.0) == null, "no visible step -> no skirt (remnant +X)");
    assertTrue(find(skirts, false, false, 1.0) == null, "no visible step -> no skirt (lip -X)");
  }

  @Test
  void steppedDownSkirtMaxExtentIsGapToLowerSurface() {
    // High surface at 65 abutting low at 64: the DOWN skirt on the high side must
    // stop at the lower painted top (maxExtent == 1).
    StandableRect low = new StandableRect(0, 0, 1, 1, 64.0);
    StandableRect high = new StandableRect(1, 0, 2, 1, 65.0);
    List<SkirtSpan> skirts =
      SurfaceSelection.computeDownSkirts(List.of(low, high), List.of(), true);
    SkirtSpan step = find(skirts, false, false, 1.0);
    assertTrue(step != null, "high -X faces a drop onto low");
    assertEquals(1.0, step.maxExtent(), EPS);
  }

  @Test
  void waterRectDownSkirtsCarryWaterHazard() {
    StandableRect water = new StandableRect(0, 0, 1, 1, 64.0, 64.0, HazardClass.WATER);
    List<SkirtSpan> spans = SurfaceSelection.computeDownSkirts(List.of(water), List.of(), false);
    assertEquals(4, spans.size());
    for (SkirtSpan s : spans) {
      assertEquals(HazardClass.WATER, s.hazard());
    }
  }
}
