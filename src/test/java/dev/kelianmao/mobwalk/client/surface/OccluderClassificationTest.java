package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.ColKey;
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
    SurfaceSelection.occluderSpansForRect(r, List.of(boxes), halfW, height, false, out);
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
  void mergeKeepsAbuttingWaterAndLavaSpansSeparate() {
    // Same +X edge / baseY, abutting along Z: WATER [0,1] then LAVA [1,2] must not
    // coalesce (hazard is part of SpanGroupKey so each keeps its own color).
    SkirtSpan water = new SkirtSpan(false, true, 1.0, 0.0, 1.0, 64.0, 64.0,
      SkirtSpan.Direction.UP, 1.0, 0, false, HazardClass.WATER);
    SkirtSpan lava = new SkirtSpan(false, true, 1.0, 1.0, 2.0, 64.0, 64.0,
      SkirtSpan.Direction.UP, 1.0, 0, false, HazardClass.LAVA);
    List<SkirtSpan> merged = SurfaceSelection.mergeOccluderSpans(List.of(water, lava));
    assertEquals(2, merged.size());
    assertEquals(HazardClass.WATER, merged.get(0).hazard());
    assertEquals(HazardClass.LAVA, merged.get(1).hazard());
  }

  @Test
  void mergeStillCoalescesSameHazardAbuttingSpans() {
    SkirtSpan a = new SkirtSpan(false, true, 1.0, 0.0, 1.0, 64.0, 64.0,
      SkirtSpan.Direction.UP, 1.0, 0, false, HazardClass.WATER);
    SkirtSpan b = new SkirtSpan(false, true, 1.0, 1.0, 2.0, 64.0, 64.0,
      SkirtSpan.Direction.UP, 1.0, 0, false, HazardClass.WATER);
    List<SkirtSpan> merged = SurfaceSelection.mergeOccluderSpans(List.of(a, b));
    assertEquals(1, merged.size());
    assertEquals(0.0, merged.get(0).lo(), EPS);
    assertEquals(2.0, merged.get(0).hi(), EPS);
    assertEquals(HazardClass.WATER, merged.get(0).hazard());
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
  void pathBesideRaisedSoulIsWallOnlyOnCollisionRim() {
    // Soul sand draw rim 65.0, collision 64.875; path top 64.9375 abuts +X.
    // Collision rim: path rises above soul collision → UP extent 1/16.
    // Visual rim: path sits below the paint → not a wall, no UP (and no negative extent).
    StandableRect soul = new StandableRect(0, 0, 1, 1, 64.875, 65.0);
    WorldBox path = new WorldBox(1, 0, 0, 1, 0, 2, 1, 0.0, 64.9375);
    List<SkirtSpan> collision = new ArrayList<>();
    SurfaceSelection.occluderSpansForRect(soul, List.of(path), 0.0, 0.0, false, collision);
    assertEquals(1, collision.size());
    assertTrue(collision.get(0).isUp());
    assertEquals(64.9375 - 64.875, collision.get(0).maxExtent(), EPS);

    List<SkirtSpan> visual = new ArrayList<>();
    SurfaceSelection.occluderSpansForRect(soul, List.of(path), 0.0, 0.0, true, visual);
    assertTrue(visual.isEmpty(), "path below paint rim must not emit a visual UP");
  }

  @Test
  void dualRimOrchestrationKeepsCollisionClaimAndVisualWingDown() {
    // select()-shaped: collision claims soul→path; visual omits that UP, keeps wing DOWNs.
    double halfW = 0.3;
    double height = 1.8;
    double soulTop = 14.0 / 16.0;
    double pathTop = 15.0 / 16.0;
    double fullTop = 1.0;
    WorldBox soul = new WorldBox(0, 0, 0, 0, 0, 1, 1, 0.0, soulTop, soulTop, fullTop);
    WorldBox path = new WorldBox(1, 0, 0, 1, 0, 2, 1, 0.0, pathTop, pathTop, pathTop);
    Map<ColKey, List<WorldBox>> index = new HashMap<>();
    index.put(new ColKey(0, 0), List.of(soul));
    index.put(new ColKey(1, 0), List.of(path));
    List<WorldBox> boxes = List.of(soul, path);
    List<StandableRect> raw = new ArrayList<>();
    SurfaceSelection.exposeBox(soul, index, halfW, height, raw);
    SurfaceSelection.exposeBox(path, index, halfW, height, raw);
    List<StandableRect> merged = RectMath.mergeAll(raw);

    List<SkirtSpan> collisionOcc = new ArrayList<>();
    for (StandableRect r : merged) {
      SurfaceSelection.occluderSpansForRect(r, boxes, halfW, height, false, collisionOcc);
    }
    collisionOcc = SurfaceSelection.mergeOccluderSpans(collisionOcc);
    List<SkirtSpan> dropEdges =
      SurfaceSelection.computeDownSkirts(merged, collisionOcc, false);

    List<SkirtSpan> visualOcc = new ArrayList<>();
    for (StandableRect r : merged) {
      SurfaceSelection.occluderSpansForRect(r, boxes, halfW, height, true, visualOcc);
    }
    visualOcc = SurfaceSelection.mergeOccluderSpans(visualOcc);
    List<SkirtSpan> visualDowns =
      SurfaceSelection.computeDownSkirts(merged, visualOcc, true);

    // Soul remnant +X at the set-back x=0.7: collision UP claims it; no collision drop.
    boolean collisionUpAtSetback = false;
    for (SkirtSpan s : collisionOcc) {
      if (!s.alongX() && s.maxSide() && Math.abs(s.line() - 0.7) < EPS
          && Math.abs(s.baseY() - soulTop) < EPS) {
        collisionUpAtSetback = true;
        assertTrue(s.maxExtent() > 0.0, "collision UP extent must be positive");
      }
    }
    assertTrue(collisionUpAtSetback, "collision rim must mark soul→path as UP");
    for (SkirtSpan s : dropEdges) {
      if (!s.alongX() && s.maxSide() && Math.abs(s.line() - 0.7) < EPS
          && Math.abs(s.baseY() - soulTop) < EPS) {
        assertTrue(false, "collision drop must not remain on soul→path set-back");
      }
    }

    // Visual: no UP from soul at that set-back; wing DOWNs at v=[-0.3,0] and [1,1.3].
    for (SkirtSpan s : visualOcc) {
      if (!s.alongX() && s.maxSide() && Math.abs(s.line() - 0.7) < EPS
          && Math.abs(s.baseY() - soulTop) < EPS) {
        assertTrue(false, "visual rim must not mark path-below-paint as UP");
      }
      assertTrue(s.maxExtent() > 0.0, "no non-positive visual UP extents");
    }
    double pixel = 1.0 / 16.0;
    boolean wingLo = false;
    boolean wingHi = false;
    for (SkirtSpan s : visualDowns) {
      // Soul +X set-back: fixed X, varying Z → alongX=false, maxSide=true.
      if (s.alongX() || !s.maxSide() || Math.abs(s.line() - 0.7) > EPS) {
        continue;
      }
      if (Math.abs(s.baseY() - soulTop) > EPS) {
        continue;
      }
      if (Math.abs(s.lo() - (-0.3)) < EPS && Math.abs(s.hi() - 0.0) < EPS) {
        wingLo = true;
        assertEquals(pixel, s.maxExtent(), EPS);
      }
      if (Math.abs(s.lo() - 1.0) < EPS && Math.abs(s.hi() - 1.3) < EPS) {
        wingHi = true;
        assertEquals(pixel, s.maxExtent(), EPS);
      }
    }
    assertTrue(wingLo && wingHi, "visual downs must include both flush-path wing skirts");
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
