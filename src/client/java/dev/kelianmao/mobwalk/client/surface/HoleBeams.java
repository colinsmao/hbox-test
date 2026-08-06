package dev.kelianmao.mobwalk.client.surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.kelianmao.mobwalk.client.surface.WorldGeometry.ColumnBoxes;
import dev.kelianmao.mobwalk.client.surface.WorldGeometry.WorldBox;

import net.minecraft.world.level.Level;

/**
 * Drop-edge hole classification: subdivides drop skirts into homogeneous sub-spans,
 * classifies each as HOLE or BENIGN, and gathers intermediate ledges that would trap
 * the entity. Runs once per select (not per frame).
 */
final class HoleBeams {
  private static final double EPS = RectMath.EPS;

  private HoleBeams() {
  }

  // --- Milestone 5: drop-edge hole classification (pure) ---

  enum DropClass {
    HOLE, BENIGN
  }

  record DropClassification(DropClass kind, double fallDistance) {
  }

  /**
   * Classify one drop sub-span. Pure: given the {@link FallColumn} the entity falls
   * down, the flood's reached set, and any intermediate standable ledges between the
   * edge and the reached floor, returns HOLE or BENIGN.
   *
   * <ol>
   * <li>Find the topmost reached surface strictly below {@code collisionTopY} that
   *     crosses the fall column. If none &rarr; HOLE (void / unreached ground).
   * <li>If a reached floor exists at {@code landY}: check whether any surface in
   *     {@code ledges} (dilated standable surfaces with top in {@code (landY,
   *     collisionTopY)}) crosses the column. If yes &rarr; HOLE (entity lands on the
   *     ledge and is trapped). If no &rarr; BENIGN (fall distance = collisionTopY &minus;
   *     landY).
   * </ol>
   */
  static DropClassification classifyDrop(FallColumn fall, double collisionTopY,
      List<StandableRect> reached, List<StandableRect> ledges) {
    StandableRect landing = null;
    for (StandableRect r : reached) {
      if (r.collisionTopY() >= collisionTopY - EPS) {
        continue;
      }
      if (!fall.crosses(r)) {
        continue;
      }
      if (landing == null || r.collisionTopY() > landing.collisionTopY()) {
        landing = r;
      }
    }
    if (landing == null) {
      return new DropClassification(DropClass.HOLE, 0.0);
    }
    double landY = landing.collisionTopY();
    for (StandableRect ledge : ledges) {
      if (ledge.collisionTopY() <= landY + EPS || ledge.collisionTopY() >= collisionTopY - EPS) {
        continue;
      }
      if (!fall.crosses(ledge)) {
        continue;
      }
      return new DropClassification(DropClass.HOLE, collisionTopY - ledge.collisionTopY());
    }
    return new DropClassification(DropClass.BENIGN, collisionTopY - landY);
  }

  // Classify each drop span and return the hole sub-spans (through-walls beam
  // candidates). For each drop span: (1) check if a reached surface exists below
  // across the fall column, (2) if yes, scan the world between collisionTopY and landY for
  // intermediate standable surfaces (ledges) via exposeBox — if any cross the
  // column, the entity gets trapped on the ledge -> HOLE. Because one edge can
  // span several verdicts, the span is SUBDIVIDED at reached-rect boundaries into
  // homogeneous sub-spans. Runs once per select (not per frame).
  static List<BeamSpan> compute(Level level, List<StandableRect> rects,
      List<SkirtSpan> drops, EntityProfile profile, boolean swimmableFluids) {
    if (drops.isEmpty()) {
      return List.of();
    }
    double halfW = profile.width() / 2.0;
    double height = profile.height();
    List<BeamSpan> out = new ArrayList<>();
    List<StandableRect> ledges = new ArrayList<>();
    for (SkirtSpan sp : drops) {
      if (sp.frontier()) {
        continue;
      }
      FallColumn fall = FallColumn.of(sp);
      ledges.clear();
      gatherLedges(level, fall, sp.baseY(), rects, halfW, height, ledges, swimmableFluids);
      holeSubSpans(sp, rects, ledges, out);
    }
    return out;
  }

  // Pure: subdivide one drop span into homogeneous sub-spans (at reached-rect
  // boundaries), classify each via classifyDrop (with ledge check), and append the
  // contiguous HOLE pieces (coalesced) as BeamSpan records. A single edge can span reached
  // and unreached ground, so classifying the whole edge at once mislabels it.
  // Package-private for unit tests (synthetic reached rects / ledges, no world).
  static void holeSubSpans(SkirtSpan sp, List<StandableRect> reached,
      List<StandableRect> ledges, List<BeamSpan> out) {
    double collisionTopY = sp.baseY();
    FallColumn fall = FallColumn.of(sp);
    double[] cuts = spanBreakpoints(sp, reached);
    double holeLo = Double.NaN;
    double holeHi = 0.0;
    for (int i = 0; i + 1 < cuts.length; i++) {
      double a = cuts[i];
      double b = cuts[i + 1];
      if (b - a <= EPS) {
        continue;
      }
      DropClassification c = classifyDrop(fall.clampedTo(a, b), collisionTopY, reached, ledges);
      if (c.kind() != DropClass.HOLE) {
        continue;
      }
      if (Double.isNaN(holeLo)) {
        holeLo = a;
        holeHi = b;
      } else if (a <= holeHi + EPS) {
        holeHi = b;
      } else {
        out.add(new BeamSpan(sp.alongX(), sp.line(), holeLo, holeHi, sp.visualBaseY(),
          HazardClass.HOLE));
        holeLo = a;
        holeHi = b;
      }
    }
    if (!Double.isNaN(holeLo)) {
      out.add(new BeamSpan(sp.alongX(), sp.line(), holeLo, holeHi, sp.visualBaseY(),
        HazardClass.HOLE));
    }
  }

  // Breakpoints along a drop span's varying axis where its classification can
  // change: the span ends, integer block boundaries, and the varying-axis edges of
  // every reached rect that crosses the rim line. Splitting here makes each sub-span
  // homogeneous (uniform "reached below or not"), so classifyDrop is exact on it.
  // Duplicates collapse to zero-width sub-spans (skipped by caller).
  private static double[] spanBreakpoints(SkirtSpan sp, List<StandableRect> rects) {
    double lo = sp.lo();
    double hi = sp.hi();
    List<Double> cuts = new ArrayList<>();
    cuts.add(lo);
    cuts.add(hi);
    for (int k = (int) Math.floor(lo) + 1; k <= (int) Math.ceil(hi) - 1; k++) {
      addCut(cuts, k, lo, hi);
    }
    for (StandableRect r : rects) {
      if (RectMath.crossesLine(r, sp.alongX(), sp.maxSide(), sp.line())) {
        addCut(cuts, sp.alongX() ? r.minX() : r.minZ(), lo, hi);
        addCut(cuts, sp.alongX() ? r.maxX() : r.maxZ(), lo, hi);
      }
    }
    double[] arr = new double[cuts.size()];
    for (int i = 0; i < arr.length; i++) {
      arr[i] = cuts.get(i);
    }
    Arrays.sort(arr);
    return arr;
  }

  private static void addCut(List<Double> cuts, double c, double lo, double hi) {
    if (c > lo + EPS && c < hi - EPS) {
      cuts.add(c);
    }
  }

  // Scan the world for standable surfaces (via exposeBox) between landY and collisionTopY
  // that cross the fall column — intermediate ledges that would trap the entity. Only
  // called when a reached floor exists below (landY is known). The occluder shell is
  // supplied by WorldSurfaceIndex.tops (the same primitive the flood uses), so the
  // ledge gather cannot re-expose a fragment the flood buried.
  private static void gatherLedges(Level level, FallColumn fall, double collisionTopY,
      List<StandableRect> reached, double halfW, double height, List<StandableRect> out,
      boolean swimmableFluids) {
    gatherLedgesFrom(WorldGeometry.levelColumnBoxes(level, false, swimmableFluids),
      fall, collisionTopY, reached, halfW, height, out);
  }

  // Pure kernel of gatherLedges: reads the world only through the ColumnBoxes port,
  // driving a WorldSurfaceIndex. Finds candidate boxes whose top lies in
  // (landY, collisionTopY) in the columns around the fall column, then exposes each via
  // index.tops(candidate) — the SAME occluder-shell primitive the flood uses. So a
  // fragment the flood buried (a headroom ceiling above the rim, a wide-entity
  // occluder a column or two out) can never be re-exposed here, and no window is
  // re-derived to drift. Package-private for tests (synthetic world via the port).
  static void gatherLedgesFrom(ColumnBoxes source, FallColumn fall, double collisionTopY,
      List<StandableRect> reached, double halfW, double height, List<StandableRect> out) {
    // Find landY: the topmost reached surface below collisionTopY crossing the column.
    double landY = Double.NEGATIVE_INFINITY;
    for (StandableRect r : reached) {
      if (r.collisionTopY() >= collisionTopY - EPS) {
        continue;
      }
      if (!fall.crosses(r)) {
        continue;
      }
      if (r.collisionTopY() > landY) {
        landY = r.collisionTopY();
      }
    }
    if (landY == Double.NEGATIVE_INFINITY) {
      return;
    }
    // Band covers candidate finding (down to floor(landY)-1 for shapes rising from the
    // row below) and every candidate's headroom shell (up to floor(collisionTopY+height)+1,
    // since candidate tops are < collisionTopY). tops() ensures its own shell within it.
    int bandLo = (int) Math.floor(landY) - 1;
    int bandHi = (int) Math.floor(collisionTopY + height) + 1;
    SurfaceSelection.WorldSurfaceIndex surfaces =
      new SurfaceSelection.WorldSurfaceIndex(source, halfW, height, bandLo, bandHi);
    // Candidate boxes: tops strictly in (landY, collisionTopY) in the columns whose
    // dilated tops can still reach the fall column — the rim column and its
    // neighbours across the line, plus the span's own columns, each widened by the
    // dilation ceil(halfW). Derived from the column itself rather than from a probe
    // footprint, so the scan window cannot shrink with the classification region
    // (the drift LedgeExposureContractTest guards). The occluder shell each candidate
    // needs on top of this is tops()'s concern.
    int reachCols = (int) Math.ceil(halfW) + 1;
    int spanLo = (int) Math.floor(fall.lo()) - reachCols;
    int spanHi = (int) Math.ceil(fall.hi()) + reachCols;
    int crossLo = (int) Math.floor(fall.line()) - reachCols;
    int crossHi = (int) Math.floor(fall.line()) + reachCols;
    int xLo = fall.alongX() ? spanLo : crossLo;
    int xHi = fall.alongX() ? spanHi : crossHi;
    int zLo = fall.alongX() ? crossLo : spanLo;
    int zHi = fall.alongX() ? crossHi : spanHi;
    int scanLo = (int) Math.floor(landY) - 1;
    int scanHi = (int) Math.ceil(collisionTopY);
    List<WorldBox> candidates = new ArrayList<>();
    for (int x = xLo; x <= xHi; x++) {
      for (int z = zLo; z <= zHi; z++) {
        surfaces.ensureRows(x, z, scanLo, scanHi);
        List<WorldBox> column = surfaces.column(x, z);
        if (column == null) {
          continue;
        }
        for (WorldBox wb : column) {
          double top = wb.yMax();
          if (top > landY + EPS && top < collisionTopY - EPS) {
            candidates.add(wb);
          }
        }
      }
    }
    // Expose each candidate against the flood's occluder shell; keep fragments in
    // (landY, collisionTopY) that cross the fall column.
    for (WorldBox cand : candidates) {
      for (StandableRect s : surfaces.tops(cand)) {
        if (s.collisionTopY() <= landY + EPS || s.collisionTopY() >= collisionTopY - EPS) {
          continue;
        }
        if (fall.crosses(s)) {
          out.add(s);
        }
      }
    }
  }
}
