package dev.kelianmao.mobwalk.client.surface;

import java.util.ArrayList;
import java.util.List;

/**
 * Downward drop-skirt spans of a reached set: each merged-rect edge minus equal-height
 * (or visual) neighbour coverage and minus wall/ceiling occluder sub-spans on that edge.
 * Package-private for unit tests (synthetic rects, no world).
 *
 * <p>{@code visual=false} keys seam/occluder coverage on {@code collisionTopY} (hole
 * classifier substrate). {@code visual=true} keys on {@code visualTopY} (paint height).
 * See {@code docs/geometry.md} "Visible-face top vs collision top".
 */
final class DownSkirts {
  private static final double EPS = RectMath.EPS;

  private DownSkirts() {
  }

  static List<SkirtSpan> compute(List<StandableRect> rects,
      List<SkirtSpan> occluders, boolean visual) {
    List<SkirtSpan> out = new ArrayList<>();
    for (StandableRect r : rects) {
      edgeDownSpans(rects, occluders, r, true, false, visual, out);  // -Z edge
      edgeDownSpans(rects, occluders, r, true, true, visual, out);   // +Z edge
      edgeDownSpans(rects, occluders, r, false, false, visual, out); // -X edge
      edgeDownSpans(rects, occluders, r, false, true, visual, out);  // +X edge
    }
    return out;
  }

  // Append the drop sub-spans of one rect edge. alongX: the edge runs along X at a
  // fixed Z; maxSide: the +axis edge. Coverage from equal-or-higher neighbours and
  // from occluder spans on this edge is subtracted; abutting *lower* neighbours set
  // maxExtent on leftover intervals (rimKey − neighbourKey). Open leftovers stay
  // UNLIMITED.
  private static void edgeDownSpans(List<StandableRect> rects, List<SkirtSpan> occluders,
      StandableRect r, boolean alongX, boolean maxSide, boolean visual, List<SkirtSpan> out) {
    double lo = alongX ? r.minX() : r.minZ();
    double hi = alongX ? r.maxX() : r.maxZ();
    double line = alongX ? (maxSide ? r.maxZ() : r.minZ()) : (maxSide ? r.maxX() : r.minX());
    // The height two rects/occluders must share to count as a continuation (seam)
    // rather than a step: collisionTopY for the drop pass, visualTopY for the skirt
    // pass. Only this key differs between the passes; the geometry is identical.
    double rKey = visual ? r.visualTopY() : r.collisionTopY();

    List<double[]> covered = new ArrayList<>();
    // Abutting lower neighbours: [overlapLo, overlapHi, neighbourKey]. Used to clamp
    // leftover drop intervals to the gap (tallest lower key wins when several overlap).
    List<double[]> lands = new ArrayList<>();
    for (StandableRect nb : rects) {
      if (nb == r) {
        continue;
      }
      boolean abuts;
      double clo;
      double chi;
      if (alongX) {
        abuts = Math.abs((maxSide ? nb.minZ() - r.maxZ() : nb.maxZ() - r.minZ())) < EPS;
        clo = Math.max(nb.minX(), r.minX());
        chi = Math.min(nb.maxX(), r.maxX());
      } else {
        abuts = Math.abs((maxSide ? nb.minX() - r.maxX() : nb.maxX() - r.minX())) < EPS;
        clo = Math.max(nb.minZ(), r.minZ());
        chi = Math.min(nb.maxZ(), r.maxZ());
      }
      if (!abuts || chi - clo <= EPS) {
        continue;
      }
      // Collision pass: equal collisionTopY is a merge seam. Visual pass: equal visualTopY
      // is a flush continuation, and a *higher* visual neighbour is a taller face
      // the lower side must not hang a down-skirt into (the high side still skirts;
      // the low side already has / will get an up-skirt when collision rises).
      // A *lower* neighbour is a land stop for the high side's leftover drop.
      double nbKey = visual ? nb.visualTopY() : nb.collisionTopY();
      if (visual) {
        if (nbKey < rKey - EPS) {
          lands.add(new double[] {clo, chi, nbKey});
        } else {
          covered.add(new double[] {clo, chi});
        }
      } else if (Math.abs(nbKey - rKey) <= EPS) {
        covered.add(new double[] {clo, chi});
      } else if (nbKey < rKey - EPS) {
        lands.add(new double[] {clo, chi, nbKey});
      }
    }
    for (SkirtSpan s : occluders) {
      if (s.alongX() != alongX || s.maxSide() != maxSide) {
        continue;
      }
      if (Math.abs(s.line() - line) > EPS
          || Math.abs((visual ? s.visualBaseY() : s.baseY()) - rKey) > EPS) {
        continue;
      }
      double clo = Math.max(s.lo(), lo);
      double chi = Math.min(s.hi(), hi);
      if (chi - clo > EPS) {
        covered.add(new double[] {clo, chi});
      }
    }

    for (double[] iv : RectMath.subtractIntervals(lo, hi, covered)) {
      appendLandClampedDownSpans(alongX, maxSide, line, iv[0], iv[1], r, rKey, lands, out);
    }
  }

  // Split one uncovered drop interval at land breakpoints; each piece gets
  // maxExtent = rKey − tallest overlapping lower neighbour key, or UNLIMITED.
  // Adjacent pieces with the same extent are coalesced (several coplanar lower
  // rects along one edge must not shatter a single curtain).
  private static void appendLandClampedDownSpans(boolean alongX, boolean maxSide, double line,
      double lo, double hi, StandableRect r, double rKey, List<double[]> lands,
      List<SkirtSpan> out) {
    List<Double> breaks = new ArrayList<>();
    breaks.add(lo);
    breaks.add(hi);
    for (double[] land : lands) {
      double clo = land[0];
      double chi = land[1];
      if (chi <= lo + EPS || clo >= hi - EPS) {
        continue;
      }
      if (clo > lo + EPS && clo < hi - EPS) {
        breaks.add(clo);
      }
      if (chi > lo + EPS && chi < hi - EPS) {
        breaks.add(chi);
      }
    }
    breaks.sort(Double::compareTo);
    List<Double> unique = new ArrayList<>();
    for (double b : breaks) {
      if (unique.isEmpty() || Math.abs(unique.get(unique.size() - 1) - b) > EPS) {
        unique.add(b);
      }
    }

    double runLo = Double.NaN;
    double runHi = Double.NaN;
    double runExtent = Double.NaN;
    for (int i = 0; i + 1 < unique.size(); i++) {
      double a = unique.get(i);
      double b = unique.get(i + 1);
      if (b - a <= EPS) {
        continue;
      }
      double stopKey = Double.NEGATIVE_INFINITY;
      for (double[] land : lands) {
        if (land[1] <= a + EPS || land[0] >= b - EPS) {
          continue;
        }
        stopKey = Math.max(stopKey, land[2]);
      }
      double maxExtent = stopKey > Double.NEGATIVE_INFINITY / 2.0
        ? rKey - stopKey
        : SkirtSpan.UNLIMITED;
      if (maxExtent <= EPS && maxExtent != SkirtSpan.UNLIMITED) {
        continue;
      }
      if (!Double.isNaN(runLo)
          && Math.abs(a - runHi) <= EPS
          && sameExtent(runExtent, maxExtent)) {
        runHi = b;
        continue;
      }
      if (!Double.isNaN(runLo)) {
        out.add(new SkirtSpan(alongX, maxSide, line, runLo, runHi, r.collisionTopY(), r.visualTopY(),
          SkirtSpan.Direction.DOWN, runExtent, r.depth(), r.frontier(), r.hazard()));
      }
      runLo = a;
      runHi = b;
      runExtent = maxExtent;
    }
    if (!Double.isNaN(runLo)) {
      out.add(new SkirtSpan(alongX, maxSide, line, runLo, runHi, r.collisionTopY(), r.visualTopY(),
        SkirtSpan.Direction.DOWN, runExtent, r.depth(), r.frontier(), r.hazard()));
    }
  }

  private static boolean sameExtent(double a, double b) {
    if (Double.isInfinite(a) || Double.isInfinite(b)) {
      return a == b;
    }
    return Math.abs(a - b) <= EPS;
  }
}
