package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.ColKey;
import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.WorldBox;

/**
 * Black-box geometry oracle for a Player-width soul-sand -> path -> full-block row.
 *
 * <p>The fixture and expected values are intentionally written without consulting
 * the production implementation. {@link #evaluate(Fixture)} is the only connection
 * point a follow-up pass must wire to the real geometry pipeline.
 */
final class TerrainEdgeContractTest {
  private static final double EPS = 1.0e-6;
  private static final double ORIGIN_X = 1000.0;
  private static final double ORIGIN_Z = -2000.0;
  private static final double HALF_WIDTH = 0.3;
  private static final double SOUL_TOP = 14.0 / 16.0;
  private static final double PATH_TOP = 15.0 / 16.0;
  private static final double FULL_TOP = 1.0;

  enum RowDirection {
    PLUS_X, MINUS_X, PLUS_Z, MINUS_Z
  }

  enum BlockKind {
    SOUL_SAND, DIRT_PATH, FULL_BLOCK
  }

  enum SpanKind {
    UP, DOWN
  }

  record Rect(double minX, double minZ, double maxX, double maxZ) {}

  record BlockSpec(BlockKind kind, Rect footprint, double collisionTop, double outlineTop) {}

  record SurfaceSpec(Rect footprint, double collisionTop, double visualTop) {}

  record HeightClass(double collisionTop, double visualTop) {}

  /**
   * One oriented shared-boundary interval. "Other" is the surface or wall across
   * the edge, so the oracle records why the edge points up or down independently
   * of the current span classes' storage details.
   */
  record BoundarySpan(
      SpanKind kind,
      boolean alongX,
      boolean maxSide,
      double line,
      double lo,
      double hi,
      double sourceCollisionTop,
      double sourceVisualTop,
      double otherCollisionTop,
      double otherVisualTop,
      double maxExtent) {}

  record Fixture(RowDirection direction, double halfWidth, List<BlockSpec> blocks) {}

  record Observed(List<SurfaceSpec> surfaces, List<BoundarySpan> internalSpans) {}

  @ParameterizedTest
  @EnumSource(RowDirection.class)
  void playerDilationProducesTheCorrectSurfacesAndOrientedInternalSpans(RowDirection direction) {
    Fixture fixture = fixture(direction);

    Observed actual = evaluate(fixture);

    assertSameSurfaceCoverage(expectedSurfaces(direction), actual.surfaces());
    assertContainsExactly(expectedInternalSpans(direction), actual.internalSpans());
  }

  private static Fixture fixture(RowDirection direction) {
    return new Fixture(direction, HALF_WIDTH, List.of(
      new BlockSpec(BlockKind.SOUL_SAND, rect(direction, 0, 0, 1, 1), SOUL_TOP, FULL_TOP),
      new BlockSpec(BlockKind.DIRT_PATH, rect(direction, 1, 0, 2, 1), PATH_TOP, PATH_TOP),
      new BlockSpec(BlockKind.FULL_BLOCK, rect(direction, 2, 0, 3, 1), FULL_TOP, FULL_TOP)));
  }

  private static List<SurfaceSpec> expectedSurfaces(RowDirection direction) {
    /*
     * In local (u,v), u follows soul -> path -> full block.
     *
     * Raw dilated supports:
     *   soul [-.3,1.3]x[-.3,1.3]
     *   path [ .7,2.3]x[-.3,1.3]
     *   full [1.7,3.3]x[-.3,1.3]
     *
     * The higher path clips soul at u=.7; the full block clips path at u=1.7.
     * Path over the undilated soul footprint is raised only on
     * [.7,1]x[0,1]. Subtracting that patch leaves three flush path rectangles.
     */
    return List.of(
      surface(direction, -0.3, -0.3, 0.7, 1.3, SOUL_TOP, FULL_TOP),
      surface(direction, 0.7, 0.0, 1.0, 1.0, PATH_TOP, FULL_TOP),
      surface(direction, 1.0, -0.3, 1.7, 1.3, PATH_TOP, PATH_TOP),
      surface(direction, 0.7, -0.3, 1.0, 0.0, PATH_TOP, PATH_TOP),
      surface(direction, 0.7, 1.0, 1.0, 1.3, PATH_TOP, PATH_TOP),
      surface(direction, 1.7, -0.3, 3.3, 1.3, FULL_TOP, FULL_TOP));
  }

  private static List<BoundarySpan> expectedInternalSpans(RowDirection direction) {
    double pixel = 1.0 / 16.0;
    return List.of(
      /*
       * Soul -> path at the set-back u=.7: collision rises by 1/16, so this is
       * an UP edge owned by soul sand. Both visible tops are 1, so there is no
       * DOWN edge at this visually flush junction.
       */
      edge(direction, SpanKind.UP, 0.7, -0.3, 1.3, true,
        SOUL_TOP, FULL_TOP, PATH_TOP, FULL_TOP, PATH_TOP - FULL_TOP),

      /*
       * Raised path patch -> ordinary path at u=1: collision is continuous but
       * visible height falls by 1/16. Only the raised side points DOWN. The
       * interval is exactly v=[0,1], the undilated soul footprint; it must not
       * leak into the two .3-wide dilation wings. Curtain length = 1/16.
       */
      edge(direction, SpanKind.DOWN, 1.0, 0.0, 1.0, true,
        PATH_TOP, FULL_TOP, PATH_TOP, PATH_TOP, pixel),

      /*
       * Path -> full block at the set-back u=1.7. Movement from path meets a
       * wall (UP); movement from the full block meets a drop (DOWN). These are
       * opposite oriented edges over exactly the same shared interval.
       */
      edge(direction, SpanKind.UP, 1.7, -0.3, 1.3, true,
        PATH_TOP, PATH_TOP, FULL_TOP, FULL_TOP, pixel),
      edge(direction, SpanKind.DOWN, 1.7, -0.3, 1.3, false,
        FULL_TOP, FULL_TOP, PATH_TOP, PATH_TOP, pixel));
  }

  private static SurfaceSpec surface(RowDirection direction,
      double minU, double minV, double maxU, double maxV,
      double collisionTop, double visualTop) {
    return new SurfaceSpec(rect(direction, minU, minV, maxU, maxV), collisionTop, visualTop);
  }

  private static BoundarySpan edge(RowDirection direction, SpanKind kind,
      double u, double loV, double hiV, boolean towardPlusU,
      double sourceCollisionTop, double sourceVisualTop,
      double otherCollisionTop, double otherVisualTop, double maxExtent) {
    return switch (direction) {
      case PLUS_X -> new BoundarySpan(kind, false, towardPlusU,
        ORIGIN_X + u, ORIGIN_Z + loV, ORIGIN_Z + hiV,
        sourceCollisionTop, sourceVisualTop, otherCollisionTop, otherVisualTop, maxExtent);
      case MINUS_X -> new BoundarySpan(kind, false, !towardPlusU,
        ORIGIN_X - u, ORIGIN_Z + loV, ORIGIN_Z + hiV,
        sourceCollisionTop, sourceVisualTop, otherCollisionTop, otherVisualTop, maxExtent);
      case PLUS_Z -> new BoundarySpan(kind, true, towardPlusU,
        ORIGIN_Z + u, ORIGIN_X + loV, ORIGIN_X + hiV,
        sourceCollisionTop, sourceVisualTop, otherCollisionTop, otherVisualTop, maxExtent);
      case MINUS_Z -> new BoundarySpan(kind, true, !towardPlusU,
        ORIGIN_Z - u, ORIGIN_X + loV, ORIGIN_X + hiV,
        sourceCollisionTop, sourceVisualTop, otherCollisionTop, otherVisualTop, maxExtent);
    };
  }

  private static Rect rect(RowDirection direction,
      double minU, double minV, double maxU, double maxV) {
    double[][] points = {
      point(direction, minU, minV),
      point(direction, minU, maxV),
      point(direction, maxU, minV),
      point(direction, maxU, maxV)
    };
    double minX = Double.POSITIVE_INFINITY;
    double minZ = Double.POSITIVE_INFINITY;
    double maxX = Double.NEGATIVE_INFINITY;
    double maxZ = Double.NEGATIVE_INFINITY;
    for (double[] point : points) {
      minX = Math.min(minX, point[0]);
      minZ = Math.min(minZ, point[1]);
      maxX = Math.max(maxX, point[0]);
      maxZ = Math.max(maxZ, point[1]);
    }
    return new Rect(minX, minZ, maxX, maxZ);
  }

  private static double[] point(RowDirection direction, double u, double v) {
    return switch (direction) {
      case PLUS_X -> new double[] {ORIGIN_X + u, ORIGIN_Z + v};
      case MINUS_X -> new double[] {ORIGIN_X - u, ORIGIN_Z + v};
      case PLUS_Z -> new double[] {ORIGIN_X + v, ORIGIN_Z + u};
      case MINUS_Z -> new double[] {ORIGIN_X + v, ORIGIN_Z - u};
    };
  }

  /**
   * Connect this adapter to synthetic WorldBoxes -> expose/merge -> occluder and
   * visual down-skirt classification. It must derive its result from the fixture;
   * returning the expected lists would defeat the contract.
   */
  private static Observed evaluate(Fixture fixture) {
    double halfW = fixture.halfWidth();
    double height = 1.8;

    List<WorldBox> boxes = new ArrayList<>();
    Map<ColKey, List<WorldBox>> index = new HashMap<>();
    for (BlockSpec block : fixture.blocks()) {
      Rect fp = block.footprint();
      int bx = (int) Math.floor(fp.minX());
      int bz = (int) Math.floor(fp.minZ());
      WorldBox box = new WorldBox(bx, 0, bz,
        fp.minX(), fp.minZ(), fp.maxX(), fp.maxZ(),
        0.0, block.collisionTop(), block.collisionTop(), block.outlineTop());
      boxes.add(box);
      index.computeIfAbsent(new ColKey(bx, bz), k -> new ArrayList<>()).add(box);
    }

    List<StandableRect> raw = new ArrayList<>();
    for (WorldBox box : boxes) {
      SurfaceSelection.exposeBox(box, index, halfW, height, raw);
    }
    List<StandableRect> merged = RectMath.mergeCoplanar(raw);

    List<SkirtSpan> occluders = new ArrayList<>();
    for (StandableRect r : merged) {
      SurfaceSelection.occluderSpansForRect(r, boxes, halfW, height, occluders);
    }
    occluders = SurfaceSelection.mergeOccluderSpans(occluders);

    List<SkirtSpan> down =
      SurfaceSelection.computeDownSkirts(merged, occluders, true);

    boolean rowAlongX = fixture.direction() == RowDirection.PLUS_X
      || fixture.direction() == RowDirection.MINUS_X;

    List<BoundarySpan> internal = new ArrayList<>();
    for (SkirtSpan s : occluders) {
      // Shared faces between blocks in the row are perpendicular to the row axis.
      if (s.alongX() == rowAlongX) {
        continue;
      }
      SurfaceSpec other = abuttingSurface(merged, s.alongX(), s.maxSide(), s.line(),
        s.lo(), s.hi());
      if (other == null) {
        continue;
      }
      internal.add(new BoundarySpan(SpanKind.UP, s.alongX(), s.maxSide(),
        s.line(), s.lo(), s.hi(),
        s.baseY(), s.visualBaseY(), other.collisionTop(), other.visualTop(), s.maxExtent()));
    }
    for (SkirtSpan s : down) {
      if (s.alongX() == rowAlongX) {
        continue;
      }
      SurfaceSpec other = abuttingSurface(merged, s.alongX(), s.maxSide(), s.line(),
        s.lo(), s.hi());
      if (other == null) {
        continue;
      }
      internal.add(new BoundarySpan(SpanKind.DOWN, s.alongX(), s.maxSide(),
        s.line(), s.lo(), s.hi(),
        s.baseY(), s.visualBaseY(), other.collisionTop(), other.visualTop(), s.maxExtent()));
    }

    List<SurfaceSpec> surfaces = new ArrayList<>();
    for (StandableRect r : merged) {
      surfaces.add(new SurfaceSpec(
        new Rect(r.minX(), r.minZ(), r.maxX(), r.maxZ()),
        r.collisionTopY(), r.visualTopY()));
    }
    return new Observed(surfaces, internal);
  }

  /** Surface across the exterior side of an edge, preferring the tallest visual top. */
  private static SurfaceSpec abuttingSurface(List<StandableRect> rects,
      boolean alongX, boolean maxSide, double line, double lo, double hi) {
    SurfaceSpec best = null;
    for (StandableRect nb : rects) {
      boolean abuts;
      double overlapLo;
      double overlapHi;
      if (alongX) {
        abuts = Math.abs((maxSide ? nb.minZ() : nb.maxZ()) - line) < EPS;
        overlapLo = Math.max(nb.minX(), lo);
        overlapHi = Math.min(nb.maxX(), hi);
      } else {
        abuts = Math.abs((maxSide ? nb.minX() : nb.maxX()) - line) < EPS;
        overlapLo = Math.max(nb.minZ(), lo);
        overlapHi = Math.min(nb.maxZ(), hi);
      }
      if (!abuts || overlapHi - overlapLo <= EPS) {
        continue;
      }
      SurfaceSpec candidate = new SurfaceSpec(
        new Rect(nb.minX(), nb.minZ(), nb.maxX(), nb.maxZ()),
        nb.collisionTopY(), nb.visualTopY());
      if (best == null || candidate.visualTop() > best.visualTop() + EPS) {
        best = candidate;
      }
    }
    return best;
  }

  /**
   * Compares the union covered by each (collisionTop, visualTop) class. The
   * guillotine/merge implementation is free to partition the same L-shaped area
   * into different rectangles.
   */
  private static void assertSameSurfaceCoverage(
      List<SurfaceSpec> expected, List<SurfaceSpec> actual) {
    List<HeightClass> classes = new ArrayList<>();
    for (SurfaceSpec surface : expected) {
      addHeightClass(classes, surface);
    }
    for (SurfaceSpec surface : actual) {
      assertTrue(surface.footprint().maxX() - surface.footprint().minX() > EPS);
      assertTrue(surface.footprint().maxZ() - surface.footprint().minZ() > EPS);
      assertTrue(hasHeightClass(classes, surface),
        () -> "unexpected height class " + surface.collisionTop() + "/" + surface.visualTop());
    }
    for (HeightClass heightClass : classes) {
      List<Rect> wanted = footprintsAt(expected, heightClass);
      List<Rect> observed = footprintsAt(actual, heightClass);
      assertNonOverlapping(observed);
      assertSameRectUnion(wanted, observed, heightClass);
    }
  }

  private static void addHeightClass(List<HeightClass> classes, SurfaceSpec surface) {
    if (!hasHeightClass(classes, surface)) {
      classes.add(new HeightClass(surface.collisionTop(), surface.visualTop()));
    }
  }

  private static boolean hasHeightClass(List<HeightClass> classes, SurfaceSpec surface) {
    return classes.stream().anyMatch(heightClass ->
      close(heightClass.collisionTop(), surface.collisionTop())
        && close(heightClass.visualTop(), surface.visualTop()));
  }

  private static List<Rect> footprintsAt(
      List<SurfaceSpec> surfaces, HeightClass heightClass) {
    return surfaces.stream()
      .filter(surface -> close(surface.collisionTop(), heightClass.collisionTop())
        && close(surface.visualTop(), heightClass.visualTop()))
      .map(SurfaceSpec::footprint)
      .toList();
  }

  private static void assertNonOverlapping(List<Rect> rects) {
    for (int i = 0; i < rects.size(); i++) {
      for (int j = i + 1; j < rects.size(); j++) {
        Rect a = rects.get(i);
        Rect b = rects.get(j);
        double overlapX = Math.min(a.maxX(), b.maxX()) - Math.max(a.minX(), b.minX());
        double overlapZ = Math.min(a.maxZ(), b.maxZ()) - Math.max(a.minZ(), b.minZ());
        assertTrue(overlapX <= EPS || overlapZ <= EPS,
          () -> "overlapping output rectangles " + a + " and " + b);
      }
    }
  }

  private static void assertSameRectUnion(
      List<Rect> expected, List<Rect> actual, HeightClass heightClass) {
    List<Double> xs = boundaries(expected, actual, true);
    List<Double> zs = boundaries(expected, actual, false);
    for (int xi = 0; xi + 1 < xs.size(); xi++) {
      for (int zi = 0; zi + 1 < zs.size(); zi++) {
        double x = (xs.get(xi) + xs.get(xi + 1)) / 2.0;
        double z = (zs.get(zi) + zs.get(zi + 1)) / 2.0;
        boolean wanted = covered(expected, x, z);
        boolean observed = covered(actual, x, z);
        assertTrue(wanted == observed,
          () -> "coverage mismatch at " + x + "," + z + " for " + heightClass);
      }
    }
  }

  private static List<Double> boundaries(
      List<Rect> expected, List<Rect> actual, boolean xAxis) {
    List<Double> values = new ArrayList<>();
    for (Rect rect : concat(expected, actual)) {
      values.add(xAxis ? rect.minX() : rect.minZ());
      values.add(xAxis ? rect.maxX() : rect.maxZ());
    }
    Collections.sort(values);
    List<Double> unique = new ArrayList<>();
    for (double value : values) {
      if (unique.isEmpty() || !close(unique.get(unique.size() - 1), value)) {
        unique.add(value);
      }
    }
    return unique;
  }

  private static List<Rect> concat(List<Rect> first, List<Rect> second) {
    List<Rect> both = new ArrayList<>(first);
    both.addAll(second);
    return both;
  }

  private static boolean covered(List<Rect> rects, double x, double z) {
    return rects.stream().anyMatch(rect ->
      x > rect.minX() && x < rect.maxX() && z > rect.minZ() && z < rect.maxZ());
  }

  private static <T> void assertContainsExactly(List<T> expected, List<T> actual) {
    List<T> unmatched = new ArrayList<>(actual);
    for (T wanted : expected) {
      int match = matchingIndex(wanted, unmatched);
      assertTrue(match >= 0, () -> "missing " + wanted + " in " + actual);
      unmatched.remove(match);
    }
    assertTrue(unmatched.isEmpty(), () -> "unexpected values " + unmatched);
  }

  private static <T> int matchingIndex(T wanted, List<T> candidates) {
    for (int i = 0; i < candidates.size(); i++) {
      if (same(wanted, candidates.get(i))) {
        return i;
      }
    }
    return -1;
  }

  private static boolean same(Object a, Object b) {
    if (a instanceof SurfaceSpec x && b instanceof SurfaceSpec y) {
      return same(x.footprint(), y.footprint())
        && close(x.collisionTop(), y.collisionTop())
        && close(x.visualTop(), y.visualTop());
    }
    if (a instanceof BoundarySpan x && b instanceof BoundarySpan y) {
      return x.kind() == y.kind()
        && x.alongX() == y.alongX()
        && x.maxSide() == y.maxSide()
        && close(x.line(), y.line())
        && close(x.lo(), y.lo())
        && close(x.hi(), y.hi())
        && close(x.sourceCollisionTop(), y.sourceCollisionTop())
        && close(x.sourceVisualTop(), y.sourceVisualTop())
        && close(x.otherCollisionTop(), y.otherCollisionTop())
        && close(x.otherVisualTop(), y.otherVisualTop())
        && close(x.maxExtent(), y.maxExtent());
    }
    return a.equals(b);
  }

  private static boolean same(Rect a, Rect b) {
    return close(a.minX(), b.minX())
      && close(a.minZ(), b.minZ())
      && close(a.maxX(), b.maxX())
      && close(a.maxZ(), b.maxZ());
  }

  private static boolean close(double a, double b) {
    if (Double.isInfinite(a) || Double.isInfinite(b)) {
      return a == b;
    }
    return Math.abs(a - b) <= EPS;
  }
}
