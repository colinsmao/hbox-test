package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The durable contract of the coplanar merge
 * ({@code RectMath.mergeCoplanarSplitFrontier}): the output is a
 * <b>non-overlapping XZ partition per collision height</b>. These are the Tier-1
 * invariants that must hold across every future milestone (see
 * {@code docs/geometry.md} "Merge contract"):
 *
 * <ol>
 *   <li>non-overlap per collision height — two rects sharing {@code collisionTopY}
 *       have disjoint XZ interiors;
 *   <li>coverage preserved — the output covers exactly the union of the inputs;
 *   <li>{@code collisionTopY} fidelity — output heights come from the inputs.
 * </ol>
 *
 * <p>The fixture reproduces the dump's cross-block overlap: two blocks at one
 * {@code collisionTopY} whose dilated tops overlap, one flush and one raised to a
 * taller visible shell by a neighbour. Grouping the merge on {@code visualTopY}
 * leaves that overlap in the output — the source of the false {@code fall=0} hole
 * beams. The merge must partition the collision band by ordered surface classes:
 * union within one class, then subtract geometry already claimed by higher classes.
 */
final class MergeContractTest {
  private static final double EPS = 1.0e-9;

  // flush and raised share collisionTopY=1.0 and overlap in XZ over [1,2]x[0,1];
  // they differ only in the draw-only visualTopY (1.0 vs 1.25).
  private static final StandableRect FLUSH = new StandableRect(0, 0, 2, 1, 1.0, 1.0);
  private static final StandableRect RAISED = new StandableRect(1, 0, 3, 1, 1.0, 1.25);
  private static final List<StandableRect> INPUT = List.of(FLUSH, RAISED);

  private static List<StandableRect> merged() {
    return RectMath.mergeAll(INPUT);
  }

  @Test
  void crossVisualOverlapIsNonOverlappingPerCollision() {
    assertNonOverlappingPerCollision(merged());
  }

  @Test
  void crossVisualOverlapPreservesCoverage() {
    assertSameCoverage(INPUT, merged());
  }

  @Test
  void crossVisualOverlapPreservesCollisionTopY() {
    for (StandableRect r : merged()) {
      boolean fromInput = INPUT.stream()
        .anyMatch(in -> Math.abs(in.collisionTopY() - r.collisionTopY()) <= EPS);
      assertTrue(fromInput,
        () -> "output collisionTopY " + r.collisionTopY() + " not present in inputs");
    }
  }

  // Tier 2 — current priority class: the overlap sliver takes the raised
  // visualTopY, while flush-only area stays flush. Every probe has one owner;
  // checking only for the expected class would let the old overlapping output pass.
  @Test
  void crossVisualOverlapResolvesToOneHighestPriorityOwner() {
    List<StandableRect> out = merged();
    assertEquals(1.0, soleOwner(out, 0.5, 0.5).visualTopY(), EPS,
      "flush-only area should keep flush visualTopY");
    assertEquals(1.25, soleOwner(out, 1.5, 0.5).visualTopY(), EPS,
      "overlap should belong to the higher visual class");
    assertEquals(1.25, soleOwner(out, 2.5, 0.5).visualTopY(), EPS,
      "raised-only area should keep raised visualTopY");
  }

  @Test
  void threePriorityClassesClaimHighestFirstAndMergeWithinClass() {
    List<StandableRect> input = List.of(
      new StandableRect(0, 0, 4, 1, 1.0, 1.0),
      new StandableRect(1, 0, 3, 1, 1.0, 1.25),
      new StandableRect(2, 0, 4, 1, 1.0, 1.5),
      new StandableRect(4, 0, 5, 1, 1.0, 1.5));
    List<StandableRect> out = RectMath.mergeAll(input);

    assertNonOverlappingPerCollision(out);
    assertSameCoverage(input, out);
    assertEquals(1.0, soleOwner(out, 0.5, 0.5).visualTopY(), EPS);
    assertEquals(1.25, soleOwner(out, 1.5, 0.5).visualTopY(), EPS);
    assertEquals(1.5, soleOwner(out, 2.5, 0.5).visualTopY(), EPS);
    assertEquals(1.5, soleOwner(out, 4.5, 0.5).visualTopY(), EPS);

    long highRuns = out.stream()
      .filter(r -> Math.abs(r.visualTopY() - 1.5) <= EPS)
      .count();
    assertEquals(1, highRuns,
      "abutting pieces in the same winning class should coalesce");
  }

  @Test
  void innerTierClaimsOverlapBeforeHigherVisualFrontier() {
    StandableRect innerFlush = new StandableRect(0, 0, 2, 1, 1.0, 1.0);
    StandableRect frontierRaised = new StandableRect(1, 0, 3, 1, 1.0, 1.25);
    List<StandableRect> input = List.of(innerFlush, frontierRaised);
    int limit = 2;

    List<StandableRect> out = RectMath.mergeCoplanarSplitFrontier(
      input, new int[] {1, limit}, limit);

    assertNonOverlappingPerCollision(out);
    assertSameCoverage(input, out);
    StandableRect overlap = soleOwner(out, 1.5, 0.5);
    assertEquals(1.0, overlap.visualTopY(), EPS,
      "inner tier should own overlap before frontier surface priority is considered");
    assertEquals(1, overlap.depth(),
      "inner winner should keep aggregate inner depth");
    StandableRect frontierOnly = soleOwner(out, 2.5, 0.5);
    assertEquals(1.25, frontierOnly.visualTopY(), EPS,
      "frontier-only area should keep its winning surface class");
    assertEquals(limit, frontierOnly.depth(),
      "frontier-only area should keep the cutoff depth");
  }

  @Test
  void surfacePriorityAndDepthReductionApplyWithinEachRadiusTier() {
    StandableRect innerLow = new StandableRect(0, 0, 3, 1, 1.0, 1.0);
    StandableRect innerHigh = new StandableRect(1, 0, 2, 1, 1.0, 1.25);
    StandableRect frontierLow = new StandableRect(3, 0, 6, 1, 1.0, 1.0);
    StandableRect frontierHigh = new StandableRect(4, 0, 5, 1, 1.0, 1.25);
    List<StandableRect> input = List.of(
      innerLow, innerHigh, frontierLow, frontierHigh);
    int limit = 3;

    List<StandableRect> out = RectMath.mergeCoplanarSplitFrontier(
      input, new int[] {0, 1, limit, limit + 4}, limit);

    assertNonOverlappingPerCollision(out);
    assertSameCoverage(input, out);
    StandableRect innerOverlap = soleOwner(out, 1.5, 0.5);
    assertEquals(1.25, innerOverlap.visualTopY(), EPS,
      "higher visual class should own overlap within the inner tier");
    assertEquals(0, innerOverlap.depth(),
      "inner depth should be the minimum over all covering inner nodes");
    StandableRect frontierOverlap = soleOwner(out, 4.5, 0.5);
    assertEquals(1.25, frontierOverlap.visualTopY(), EPS,
      "higher visual class should own overlap within the frontier tier");
    assertEquals(limit, frontierOverlap.depth(),
      "frontier depth should canonicalize to the cutoff limit");
    StandableRect frontierLowOnly = soleOwner(out, 5.5, 0.5);
    assertEquals(1.0, frontierLowOnly.visualTopY(), EPS,
      "frontier low-only area should retain its surface class");
    assertEquals(limit, frontierLowOnly.depth(),
      "all frontier output should use the cutoff limit");
  }

  private static StandableRect soleOwner(
      List<StandableRect> rects, double x, double z) {
    List<StandableRect> owners = rects.stream()
      .filter(r -> x > r.minX() && x < r.maxX()
        && z > r.minZ() && z < r.maxZ())
      .toList();
    assertEquals(1, owners.size(),
      "expected one owner at " + x + "," + z + " but found " + owners);
    return owners.get(0);
  }

  private static void assertNonOverlappingPerCollision(List<StandableRect> rects) {
    for (int i = 0; i < rects.size(); i++) {
      for (int j = i + 1; j < rects.size(); j++) {
        StandableRect a = rects.get(i);
        StandableRect b = rects.get(j);
        if (Math.abs(a.collisionTopY() - b.collisionTopY()) > EPS) {
          continue;
        }
        double ox = Math.min(a.maxX(), b.maxX()) - Math.max(a.minX(), b.minX());
        double oz = Math.min(a.maxZ(), b.maxZ()) - Math.max(a.minZ(), b.minZ());
        assertTrue(ox <= EPS || oz <= EPS,
          () -> "rects overlap at equal collisionTopY: " + a + " and " + b);
      }
    }
  }

  // Coverage equality via cell-center sampling over the combined boundary grid,
  // restricted to a shared collision height (the fixture is one band).
  private static void assertSameCoverage(List<StandableRect> a, List<StandableRect> b) {
    List<Double> xs = boundaries(a, b, true);
    List<Double> zs = boundaries(a, b, false);
    for (int xi = 0; xi + 1 < xs.size(); xi++) {
      for (int zi = 0; zi + 1 < zs.size(); zi++) {
        double x = (xs.get(xi) + xs.get(xi + 1)) / 2.0;
        double z = (zs.get(zi) + zs.get(zi + 1)) / 2.0;
        boolean wanted = covered(a, x, z);
        boolean observed = covered(b, x, z);
        assertTrue(wanted == observed,
          () -> "coverage mismatch at " + x + "," + z);
      }
    }
  }

  private static List<Double> boundaries(
      List<StandableRect> a, List<StandableRect> b, boolean xAxis) {
    List<Double> values = new ArrayList<>();
    List<StandableRect> both = new ArrayList<>(a);
    both.addAll(b);
    for (StandableRect r : both) {
      values.add(xAxis ? r.minX() : r.minZ());
      values.add(xAxis ? r.maxX() : r.maxZ());
    }
    Collections.sort(values);
    List<Double> unique = new ArrayList<>();
    for (double v : values) {
      if (unique.isEmpty() || Math.abs(unique.get(unique.size() - 1) - v) > EPS) {
        unique.add(v);
      }
    }
    return unique;
  }

  private static boolean covered(List<StandableRect> rects, double x, double z) {
    return rects.stream().anyMatch(r ->
      x > r.minX() && x < r.maxX() && z > r.minZ() && z < r.maxZ());
  }
}
