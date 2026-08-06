package dev.kelianmao.mobwalk.client.surface;

import java.util.ArrayList;
import java.util.List;

/**
 * Perimeter beam spans of reached hazard surfaces: each non-frontier fluid rect edge
 * minus sub-spans covered by an abutting neighbour with the same {@link HazardClass}
 * and equal {@code collisionTopY} (interior pool seams). No occluder subtract.
 * Package-private for unit tests (synthetic rects, no world).
 */
final class HazardBeams {
  private static final double EPS = RectMath.EPS;

  private HazardBeams() {
  }

  static List<BeamSpan> compute(List<StandableRect> rects) {
    List<BeamSpan> out = new ArrayList<>();
    for (StandableRect r : rects) {
      if (r.frontier() || !r.hazard().isFluid()) {
        continue;
      }
      edgeSpans(rects, r, true, false, out);  // -Z
      edgeSpans(rects, r, true, true, out);   // +Z
      edgeSpans(rects, r, false, false, out); // -X
      edgeSpans(rects, r, false, true, out);  // +X
    }
    return out;
  }

  private static void edgeSpans(List<StandableRect> rects, StandableRect r,
      boolean alongX, boolean maxSide, List<BeamSpan> out) {
    double lo = alongX ? r.minX() : r.minZ();
    double hi = alongX ? r.maxX() : r.maxZ();
    double line = alongX ? (maxSide ? r.maxZ() : r.minZ()) : (maxSide ? r.maxX() : r.minX());
    double rKey = r.collisionTopY();
    HazardClass hazard = r.hazard();

    List<double[]> covered = new ArrayList<>();
    for (StandableRect nb : rects) {
      if (nb == r || nb.hazard() != hazard) {
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
      if (Math.abs(nb.collisionTopY() - rKey) <= EPS) {
        covered.add(new double[] {clo, chi});
      }
    }

    for (double[] iv : RectMath.subtractIntervals(lo, hi, covered)) {
      out.add(new BeamSpan(alongX, line, iv[0], iv[1], r.visualTopY(), hazard));
    }
  }
}
