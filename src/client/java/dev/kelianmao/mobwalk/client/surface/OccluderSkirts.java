package dev.kelianmao.mobwalk.client.surface;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.kelianmao.mobwalk.client.surface.WorldGeometry.ColumnBoxes;
import dev.kelianmao.mobwalk.client.surface.WorldGeometry.WorldBox;

import net.minecraft.world.level.Level;

/**
 * Upward wall/ceiling skirt spans of a reached set: where a dilated edge abuts a
 * {@linkplain #wallOccluder wall/ceiling} box, coalesced along the edge. Package-private
 * for unit tests (synthetic boxes / {@link ColumnBoxes}, no live world required).
 *
 * <p>{@code visual=false} keys the rim on {@code collisionTopY}; {@code visual=true}
 * on {@code visualTopY}. See {@code docs/geometry.md} "Visible-face top vs collision top".
 */
final class OccluderSkirts {
  private static final double EPS = RectMath.EPS;

  private OccluderSkirts() {
  }

  // Grouping key for the occluder-span merge: orientation + side + line + base
  // height + hazard, each double quantized to 1/1024 so collinear spans on the
  // same edge at one height hash together (opposite sides at one coordinate stay
  // distinct). Hazard keeps WATER|LAVA from coalescing into one color.
  private record SpanGroupKey(boolean alongX, boolean maxSide, long line, long baseY,
      HazardClass hazard) {
  }

  // True iff box is a wall/ceiling for rim: occludes, yMax > rim, and
  // yMin <= rim+height. Fluids never mark up-skirts. height==0 is walls only;
  // height>0 also admits ceilings in the standing column. <= on the lower bound
  // (vs exposeBox's strict <) keeps Point's at-floor walls; abutment in
  // occluderSpansForRect rejects own-floor cases. rim is collisionTopY or
  // visualTopY (same dual key as DownSkirts.compute).
  static boolean wallOccluder(WorldBox b, double rim, double height) {
    return b.occludes()
      && b.yMax() > rim + EPS
      && b.yMin() <= rim + height + EPS;
  }

  // Upward skirt spans where a wallOccluder abuts a dilated edge. visual selects
  // the rim (collision vs paint); maxExtent = top − rim. Pure (candidates given).
  static void occluderSpansForRect(StandableRect r, List<WorldBox> candidates,
      double halfW, double height, boolean visual, List<SkirtSpan> out) {
    double collisionTopY = r.collisionTopY();
    double visualTopY = r.visualTopY();
    double rim = visual ? visualTopY : collisionTopY;
    int depth = r.depth();
    boolean frontier = r.frontier();
    HazardClass hazard = r.hazard();
    for (WorldBox b : candidates) {
      if (!wallOccluder(b, rim, height)) {
        continue;
      }
      double oMinX = b.minX() - halfW;
      double oMinZ = b.minZ() - halfW;
      double oMaxX = b.maxX() + halfW;
      double oMaxZ = b.maxZ() + halfW;
      double extent = b.yMax() - rim;

      double zLo = Math.max(oMinZ, r.minZ());
      double zHi = Math.min(oMaxZ, r.maxZ());
      if (zHi - zLo > EPS) {
        if (Math.abs(oMinX - r.maxX()) < EPS) {
          out.add(new SkirtSpan(false, true, r.maxX(), zLo, zHi, collisionTopY, visualTopY,
            SkirtSpan.Direction.UP, extent, depth, frontier, hazard));
        }
        if (Math.abs(oMaxX - r.minX()) < EPS) {
          out.add(new SkirtSpan(false, false, r.minX(), zLo, zHi, collisionTopY, visualTopY,
            SkirtSpan.Direction.UP, extent, depth, frontier, hazard));
        }
      }
      double xLo = Math.max(oMinX, r.minX());
      double xHi = Math.min(oMaxX, r.maxX());
      if (xHi - xLo > EPS) {
        if (Math.abs(oMinZ - r.maxZ()) < EPS) {
          out.add(new SkirtSpan(true, true, r.maxZ(), xLo, xHi, collisionTopY, visualTopY,
            SkirtSpan.Direction.UP, extent, depth, frontier, hazard));
        }
        if (Math.abs(oMaxZ - r.minZ()) < EPS) {
          out.add(new SkirtSpan(true, false, r.minZ(), xLo, xHi, collisionTopY, visualTopY,
            SkirtSpan.Direction.UP, extent, depth, frontier, hazard));
        }
      }
    }
  }

  // Coalesce occluder spans that are collinear (same orientation + edge line +
  // base height) and overlap/abut along the edge into one span, taking the max
  // occluder top — so stacked/adjacent occluder boxes don't emit overlapping
  // double-blending up-skirts.
  static List<SkirtSpan> mergeOccluderSpans(List<SkirtSpan> spans) {
    if (spans.size() < 2) {
      return spans;
    }
    Map<SpanGroupKey, List<SkirtSpan>> groups = new LinkedHashMap<>();
    for (SkirtSpan s : spans) {
      SpanGroupKey key = new SpanGroupKey(s.alongX(), s.maxSide(),
        Math.round(s.line() * 1024.0), Math.round(s.baseY() * 1024.0), s.hazard());
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
    }
    List<SkirtSpan> out = new ArrayList<>();
    for (List<SkirtSpan> group : groups.values()) {
      group.sort(Comparator.comparingDouble(SkirtSpan::lo));
      SkirtSpan head = group.get(0);
      double lo = head.lo();
      double hi = head.hi();
      // Absolute wall stop (visualBase + extent); coalesce takes the max.
      double stop = head.visualBaseY() + head.maxExtent();
      // baseY is fixed per group (grouped on it); the visible base can differ
      // when a raised block abuts a flush one, so take the max like stop.
      double visualBase = head.visualBaseY();
      // Coalesced spans can come from different surfaces (same edge line/height,
      // different depth); take the min so the merged marker reads as the nearest
      // surface's band (mirrors the max-stop handling above). Frontier only when
      // every piece is frontier — an inner piece keeps the span drawable.
      // Hazard is fixed per group (SpanGroupKey).
      int spanDepth = head.depth();
      boolean spanFrontier = head.frontier();
      HazardClass spanHazard = head.hazard();
      for (int i = 1; i < group.size(); i++) {
        SkirtSpan s = group.get(i);
        if (s.lo() <= hi + EPS) {
          hi = Math.max(hi, s.hi());
          stop = Math.max(stop, s.visualBaseY() + s.maxExtent());
          visualBase = Math.max(visualBase, s.visualBaseY());
          spanDepth = RectMath.minDepth(spanDepth, s.depth());
          spanFrontier = spanFrontier && s.frontier();
        } else {
          out.add(new SkirtSpan(head.alongX(), head.maxSide(),
            head.line(), lo, hi, head.baseY(), visualBase,
            SkirtSpan.Direction.UP, stop - visualBase, spanDepth, spanFrontier, spanHazard));
          lo = s.lo();
          hi = s.hi();
          stop = s.visualBaseY() + s.maxExtent();
          visualBase = s.visualBaseY();
          spanDepth = s.depth();
          spanFrontier = s.frontier();
          spanHazard = s.hazard();
        }
      }
      out.add(new SkirtSpan(head.alongX(), head.maxSide(),
        head.line(), lo, hi, head.baseY(), visualBase,
        SkirtSpan.Direction.UP, stop - visualBase, spanDepth, spanFrontier, spanHazard));
    }
    return out;
  }

  // World-reading wrapper: near each reached surface, gather boxes and classify
  // UP skirts via occluderSpansForRect. Once per stick action. visual picks the
  // rim (collision vs paint). ColumnBoxes only — fluid surfaces cannot mark UPs.
  static List<SkirtSpan> compute(Level level, List<StandableRect> rects,
      EntityProfile profile, boolean swimmableFluids, boolean visual) {
    return computeFrom(
      WorldGeometry.levelColumnBoxes(level, false, swimmableFluids),
      level.getMinY(), level.getMaxY(), rects, profile, visual);
  }

  // Pure kernel of compute: reads the world only through the ColumnBoxes port.
  // Keeps this pass's own XZ window and per-rect Y window (clamped to
  // worldMinY/worldMaxY) — not WorldSurfaceIndex / occluderColumns, whose flood
  // band and undilated-box window would silently drop occluders here.
  static List<SkirtSpan> computeFrom(ColumnBoxes source, int worldMinY, int worldMaxY,
      List<StandableRect> rects, EntityProfile profile) {
    return computeFrom(source, worldMinY, worldMaxY, rects, profile, false);
  }

  static List<SkirtSpan> computeFrom(ColumnBoxes source, int worldMinY, int worldMaxY,
      List<StandableRect> rects, EntityProfile profile, boolean visual) {
    if (rects.isEmpty()) {
      return List.of();
    }
    double halfW = profile.width() / 2.0;
    double height = profile.height();
    List<SkirtSpan> out = new ArrayList<>();
    List<WorldBox> candidates = new ArrayList<>();
    for (StandableRect r : rects) {
      double rim = visual ? r.visualTopY() : r.collisionTopY();
      int xLo = (int) Math.floor(r.minX() - halfW) - 1;
      int xHi = (int) Math.ceil(r.maxX() + halfW);
      int zLo = (int) Math.floor(r.minZ() - halfW) - 1;
      int zHi = (int) Math.ceil(r.maxZ() + halfW);
      int yLo = Math.max((int) Math.floor(rim) - 1, worldMinY);
      int yHi = Math.min((int) Math.floor(rim + height) + 1, worldMaxY);
      candidates.clear();
      for (int x = xLo; x <= xHi; x++) {
        for (int z = zLo; z <= zHi; z++) {
          for (int y = yLo; y <= yHi; y++) {
            candidates.addAll(source.at(x, y, z));
          }
        }
      }
      occluderSpansForRect(r, candidates, halfW, height, visual, out);
    }
    return mergeOccluderSpans(out);
  }
}
