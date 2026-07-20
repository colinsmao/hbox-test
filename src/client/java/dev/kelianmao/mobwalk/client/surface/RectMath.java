package dev.kelianmao.mobwalk.client.surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, stateless rect/interval algebra used by {@link SurfaceSelection}:
 * subtract/union/merge, footprint adjacency, and related helpers. No world
 * access and no held state.
 */
public final class RectMath {
  private RectMath() {
  }

  // An axis-aligned XZ rectangle (world coords), the per-box footprint clip in
  // exposeBox and the mutable merge accumulator. Package-private so the pure
  // geometry ops can be unit-tested with synthetic rects (no world).
  record Rect(double minX, double minZ, double maxX, double maxZ) {
  }

  // One ordered surface class plus its raw rectangles. Layers are processed
  // highest-first by {@link #priorityPartition}. Package-private for tests.
  record PriorityLayer<C>(C priorityClass, List<Rect> rects) {
  }

  // One non-overlapping output rectangle owned by a winning priority class.
  // Package-private for tests.
  record ClassifiedRect<C>(Rect rect, C priorityClass) {
  }

  // Radius certainty tier for merge ownership. INNER orders before FRONTIER in
  // the composite priority product (see docs/geometry.md "Merge contract").
  private enum RadiusTier {
    INNER,
    FRONTIER
  }

  // Composite ownership class: radius tier outer, surface class (today
  // visualTopY) inner. Equality for strip-merge grouping uses EPS on visual.
  private record OwnershipClass(RadiusTier tier, double visualTopY) {
  }

  // Grouping key for the merge: two doubles quantized to 1/1024 of a block
  // (finer than any collision-box edge, incl. dilated 0.3 / 0.975 later) so
  // equal spans hash together despite float noise.
  private record SpanKey(long a, long b) {
  }

  // Tolerance for the double coordinate compares (box edges are multiples of
  // 1/16). Used to drop subtraction slivers and to test edge adjacency/overlap.
  // Package-visible so SurfaceSelection shares one epsilon.
  static final double EPS = 1.0e-6;

  // Two surfaces are footprint-connected if their rects either overlap with
  // positive area (the same walkable patch — happens once dilated neighbor tops
  // grow into each other) or share an edge with positive overlap: abut along X
  // (one's maxX == the other's minX) with Z-overlap, or along Z with X-overlap.
  // The edge test stops a partial-footprint surface (e.g. a stair tread) from
  // connecting on a side it does not physically touch; the overlap test keeps
  // dilated patches connected even when their spans are offset (no clean edge).
  // For Point, tops never overlap, so only the edge test fires (as before).
  static boolean footprintAdjacent(StandableRect a, StandableRect b) {
    double xOverlap = Math.min(a.maxX(), b.maxX()) - Math.max(a.minX(), b.minX());
    double zOverlap = Math.min(a.maxZ(), b.maxZ()) - Math.max(a.minZ(), b.minZ());
    if (xOverlap > EPS && zOverlap > EPS) {
      return true;
    }
    if (zOverlap > EPS
        && (Math.abs(a.maxX() - b.minX()) < EPS || Math.abs(b.maxX() - a.minX()) < EPS)) {
      return true;
    }
    return xOverlap > EPS
        && (Math.abs(a.maxZ() - b.minZ()) < EPS || Math.abs(b.maxZ() - a.minZ()) < EPS);
  }

  // Test helper: coplanar merge with every node treated as inner (all-zero
  // depths, limit above 0). Same union + strip-merge as the production path's
  // inner bucket; package-private for unit tests that need merge without a
  // frontier split.
  static List<StandableRect> mergeAll(List<StandableRect> input) {
    return mergeCoplanarSplitFrontier(input, new int[input.size()], input.size() + 1);
  }

  // Production merge used by the lazy flood path. Groups by collisionTopY (the
  // partition key — see docs/geometry.md "Merge contract"). Within each collision
  // band: form composite ownership layers (INNER surface classes highest-first,
  // then FRONTIER surface classes highest-first — today surface class is
  // visualTopY), run one priority partition, strip-merge equal ownership classes,
  // then tag INNER depth by min covering node and FRONTIER depth by limit.
  // Greedy strip-merge is not a minimal partition, but a miss only costs an
  // extra interior skirt, never reachability. Package-private for unit tests.
  static List<StandableRect> mergeCoplanarSplitFrontier(
      List<StandableRect> nodes, int[] nodeDepths, int limit) {
    if (nodes.isEmpty()) {
      return List.of();
    }
    List<StandableRect> sorted = new ArrayList<>(nodes);
    int[] sortedDepths = new int[nodeDepths.length];
    Integer[] idx = new Integer[nodes.size()];
    for (int i = 0; i < idx.length; i++) {
      idx[i] = i;
    }
    Arrays.sort(idx, Comparator
      .comparingDouble((Integer a) -> nodes.get(a).collisionTopY()));
    for (int i = 0; i < idx.length; i++) {
      sorted.set(i, nodes.get(idx[i]));
      sortedDepths[i] = nodeDepths[idx[i]];
    }

    List<StandableRect> out = new ArrayList<>();
    int i = 0;
    while (i < sorted.size()) {
      double collisionTopY = sorted.get(i).collisionTopY();
      int j = i + 1;
      while (j < sorted.size()
          && sorted.get(j).collisionTopY() - collisionTopY <= EPS) {
        j++;
      }

      List<StandableRect> innerNodes = new ArrayList<>();
      List<StandableRect> frontierNodes = new ArrayList<>();
      List<Integer> innerDepths = new ArrayList<>();
      for (int k = i; k < j; k++) {
        if (sortedDepths[k] >= limit) {
          frontierNodes.add(sorted.get(k));
        } else {
          innerNodes.add(sorted.get(k));
          innerDepths.add(sortedDepths[k]);
        }
      }

      List<PriorityLayer<OwnershipClass>> layers = new ArrayList<>();
      layers.addAll(ownershipLayers(RadiusTier.INNER, innerNodes));
      layers.addAll(ownershipLayers(RadiusTier.FRONTIER, frontierNodes));
      List<ClassifiedRect<OwnershipClass>> coalesced =
        stripMergeEqualOwnership(priorityPartition(layers));

      for (ClassifiedRect<OwnershipClass> cell : coalesced) {
        OwnershipClass ownership = cell.priorityClass();
        int depth;
        if (ownership.tier() == RadiusTier.INNER) {
          depth = minCoveringDepth(cell.rect(), innerNodes, innerDepths);
          if (depth < 0) {
            continue;
          }
        } else {
          depth = limit;
        }
        out.add(new StandableRect(
          cell.rect().minX(), cell.rect().minZ(),
          cell.rect().maxX(), cell.rect().maxZ(),
          collisionTopY, ownership.visualTopY(), depth));
      }
      i = j;
    }
    return out;
  }

  // Class-agnostic priority partition: layers arrive highest-first. Each class
  // unions and strip-merges its own geometry, then receives only the area not
  // already claimed by a higher class. Package-private for unit tests.
  static <C> List<ClassifiedRect<C>> priorityPartition(List<PriorityLayer<C>> layers) {
    List<Rect> claimed = new ArrayList<>();
    List<ClassifiedRect<C>> out = new ArrayList<>();
    for (PriorityLayer<C> layer : layers) {
      if (layer.rects().isEmpty()) {
        continue;
      }
      List<Rect> merged = stripMerge(union(layer.rects()));
      List<Rect> exclusive = new ArrayList<>();
      for (Rect r : merged) {
        exclusive.addAll(subtractRects(r, claimed));
      }
      for (Rect r : exclusive) {
        out.add(new ClassifiedRect<>(r, layer.priorityClass()));
      }
      if (!exclusive.isEmpty()) {
        claimed = union(concat(claimed, exclusive));
      }
    }
    return out;
  }

  // Build ownership layers for one radius tier, highest visualTopY first.
  private static List<PriorityLayer<OwnershipClass>> ownershipLayers(
      RadiusTier tier, List<StandableRect> nodes) {
    if (nodes.isEmpty()) {
      return List.of();
    }
    List<StandableRect> sorted = new ArrayList<>(nodes);
    sorted.sort(Comparator.comparingDouble(StandableRect::visualTopY).reversed());
    List<PriorityLayer<OwnershipClass>> layers = new ArrayList<>();
    int i = 0;
    while (i < sorted.size()) {
      double visualTopY = sorted.get(i).visualTopY();
      int j = i + 1;
      while (j < sorted.size()
          && Math.abs(sorted.get(j).visualTopY() - visualTopY) <= EPS) {
        j++;
      }
      List<Rect> rects = new ArrayList<>(j - i);
      for (int k = i; k < j; k++) {
        StandableRect n = sorted.get(k);
        rects.add(new Rect(n.minX(), n.minZ(), n.maxX(), n.maxZ()));
      }
      layers.add(new PriorityLayer<>(new OwnershipClass(tier, visualTopY), rects));
      i = j;
    }
    return layers;
  }

  private static int minCoveringDepth(
      Rect cell, List<StandableRect> nodes, List<Integer> depths) {
    int best = -1;
    for (int k = 0; k < nodes.size(); k++) {
      StandableRect n = nodes.get(k);
      if (Math.min(n.maxX(), cell.maxX()) - Math.max(n.minX(), cell.minX()) <= EPS
          || Math.min(n.maxZ(), cell.maxZ()) - Math.max(n.minZ(), cell.minZ()) <= EPS) {
        continue;
      }
      int d = depths.get(k);
      if (best < 0 || d < best) {
        best = d;
      }
    }
    return best;
  }

  // Coalesce abutting cells that share an ownership class (same radius tier and
  // visualTopY within EPS). Re-merges shatterings left by priority subtract.
  private static List<ClassifiedRect<OwnershipClass>> stripMergeEqualOwnership(
      List<ClassifiedRect<OwnershipClass>> cells) {
    if (cells.isEmpty()) {
      return List.of();
    }
    List<ClassifiedRect<OwnershipClass>> sorted = new ArrayList<>(cells);
    sorted.sort(Comparator
      .comparing((ClassifiedRect<OwnershipClass> c) -> c.priorityClass().tier())
      .thenComparingDouble(c -> c.priorityClass().visualTopY()));
    List<ClassifiedRect<OwnershipClass>> out = new ArrayList<>();
    int i = 0;
    while (i < sorted.size()) {
      OwnershipClass ownership = sorted.get(i).priorityClass();
      int j = i + 1;
      while (j < sorted.size()
          && sorted.get(j).priorityClass().tier() == ownership.tier()
          && Math.abs(sorted.get(j).priorityClass().visualTopY()
            - ownership.visualTopY()) <= EPS) {
        j++;
      }
      List<Rect> rects = new ArrayList<>(j - i);
      for (int k = i; k < j; k++) {
        rects.add(sorted.get(k).rect());
      }
      for (Rect r : stripMerge(rects)) {
        out.add(new ClassifiedRect<>(r, ownership));
      }
      i = j;
    }
    return out;
  }

  private static List<Rect> concat(List<Rect> a, List<Rect> b) {
    List<Rect> both = new ArrayList<>(a.size() + b.size());
    both.addAll(a);
    both.addAll(b);
    return both;
  }

  // The X-then-Z greedy strip merge loop used by mergeCoplanarSplitFrontier
  // (runs once per ownership / priority class).
  private static List<Rect> stripMerge(List<Rect> rects) {
    int before;
    do {
      before = rects.size();
      rects = mergeAlong(rects, true);
      rects = mergeAlong(rects, false);
    } while (rects.size() < before);
    return rects;
  }

  // Re-cut a set of (possibly overlapping) coplanar rects into a non-overlapping
  // set covering exactly their union, so translucent tops never double-blend and
  // the greedy strip-merge (which assumes non-overlapping input) is well-defined.
  // Vertical-slab sweep: split at every X edge, and within each slab union the
  // Z-intervals of the rects that span it. Because every rect edge is a slab
  // boundary, a rect either fully covers a slab or not at all (no partial cells).
  // halfW == 0 (Point) yields only abutting tops, so the union is a no-op on area.
  static List<Rect> union(List<Rect> rects) {
    if (rects.size() < 2) {
      return rects;
    }
    int n = rects.size();
    double[] xs = new double[n * 2];
    for (int i = 0; i < n; i++) {
      xs[2 * i] = rects.get(i).minX();
      xs[2 * i + 1] = rects.get(i).maxX();
    }
    Arrays.sort(xs);

    List<Rect> out = new ArrayList<>();
    List<double[]> intervals = new ArrayList<>();
    for (int s = 0; s + 1 < xs.length; s++) {
      double x0 = xs[s];
      double x1 = xs[s + 1];
      if (x1 - x0 <= EPS) {
        continue;
      }
      intervals.clear();
      for (int k = 0; k < n; k++) {
        Rect r = rects.get(k);
        if (r.minX() <= x0 + EPS && r.maxX() >= x1 - EPS) {
          intervals.add(new double[] {r.minZ(), r.maxZ()});
        }
      }
      if (intervals.isEmpty()) {
        continue;
      }
      intervals.sort(Comparator.comparingDouble(iv -> iv[0]));
      double zlo = intervals.get(0)[0];
      double zhi = intervals.get(0)[1];
      for (int k = 1; k < intervals.size(); k++) {
        double[] iv = intervals.get(k);
        if (iv[0] <= zhi + EPS) {
          if (iv[1] > zhi) {
            zhi = iv[1];
          }
        } else {
          out.add(new Rect(x0, zlo, x1, zhi));
          zlo = iv[0];
          zhi = iv[1];
        }
      }
      out.add(new Rect(x0, zlo, x1, zhi));
    }
    return out;
  }

  // Merge rects that share the perpendicular span and abut/overlap along the
  // merge axis. alongX: group by (minZ,maxZ), extend X; else group by (minX,maxX),
  // extend Z. Non-overlapping input, so "abut" (gap <= EPS) is the merge test.
  private static List<Rect> mergeAlong(List<Rect> rects, boolean alongX) {
    Map<SpanKey, List<Rect>> groups = new LinkedHashMap<>();
    for (Rect r : rects) {
      SpanKey key = alongX ? spanKey(r.minZ(), r.maxZ()) : spanKey(r.minX(), r.maxX());
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
    }

    List<Rect> out = new ArrayList<>();
    for (List<Rect> group : groups.values()) {
      group.sort(Comparator.comparingDouble(r -> alongX ? r.minX() : r.minZ()));
      Rect cur = group.get(0);
      for (int k = 1; k < group.size(); k++) {
        Rect r = group.get(k);
        if (alongX) {
          if (r.minX() <= cur.maxX() + EPS) {
            cur = new Rect(cur.minX(), cur.minZ(), Math.max(cur.maxX(), r.maxX()), cur.maxZ());
          } else {
            out.add(cur);
            cur = r;
          }
        } else {
          if (r.minZ() <= cur.maxZ() + EPS) {
            cur = new Rect(cur.minX(), cur.minZ(), cur.maxX(), Math.max(cur.maxZ(), r.maxZ()));
          } else {
            out.add(cur);
            cur = r;
          }
        }
      }
      out.add(cur);
    }
    return out;
  }

  private static SpanKey spanKey(double lo, double hi) {
    return new SpanKey(Math.round(lo * 1024.0), Math.round(hi * 1024.0));
  }

  // Smaller of two debug flood-depths, treating -1 ("no depth") as absent so it
  // never wins over a real depth (only two -1s yield -1). Package-private so
  // SurfaceSelection's occluder-span merge can call it.
  static int minDepth(int a, int b) {
    if (a < 0) {
      return b;
    }
    if (b < 0) {
      return a;
    }
    return Math.min(a, b);
  }

  // Positive-area intersection of two XZ rects, or null if they miss / only touch.
  // Package-private so SurfaceSelection's neighbour-raise split can call it.
  static Rect intersectRect(Rect a, Rect b) {
    double minX = Math.max(a.minX(), b.minX());
    double maxX = Math.min(a.maxX(), b.maxX());
    double minZ = Math.max(a.minZ(), b.minZ());
    double maxZ = Math.min(a.maxZ(), b.maxZ());
    if (maxX - minX <= EPS || maxZ - minZ <= EPS) {
      return null;
    }
    return new Rect(minX, minZ, maxX, maxZ);
  }

  // [lo,hi] minus the union of covered intervals, as the remaining open sub-spans
  // (left-to-right sweep over the sorted intervals). The double-precision twin of
  // the old render-side subtractSpans. Package-private so SurfaceSelection's
  // down-skirt pass can call it.
  static List<double[]> subtractIntervals(double lo, double hi, List<double[]> covered) {
    covered.sort(Comparator.comparingDouble(c -> c[0]));
    List<double[]> out = new ArrayList<>();
    double cur = lo;
    for (double[] c : covered) {
      if (c[0] > cur + EPS) {
        out.add(new double[] {cur, Math.min(c[0], hi)});
      }
      cur = Math.max(cur, c[1]);
      if (cur >= hi - EPS) {
        break;
      }
    }
    if (hi - cur > EPS) {
      out.add(new double[] {cur, hi});
    }
    return out;
  }

  // Subtract every occluder from base, returning the remaining 0..N rectangles
  // (guillotine subtraction: each cut splits a piece into up to 4 leftovers).
  static List<Rect> subtractRects(Rect base, List<Rect> occluders) {
    List<Rect> pieces = new ArrayList<>();
    pieces.add(base);
    for (Rect occluder : occluders) {
      List<Rect> next = new ArrayList<>();
      for (Rect piece : pieces) {
        subtractOne(piece, occluder, next);
      }
      pieces = next;
      if (pieces.isEmpty()) {
        break;
      }
    }
    return pieces;
  }

  // Add the parts of piece not covered by occluder to out (up to 4 rects).
  private static void subtractOne(Rect piece, Rect occluder, List<Rect> out) {
    double ixMin = Math.max(piece.minX(), occluder.minX());
    double ixMax = Math.min(piece.maxX(), occluder.maxX());
    double izMin = Math.max(piece.minZ(), occluder.minZ());
    double izMax = Math.min(piece.maxZ(), occluder.maxZ());

    if (ixMax - ixMin <= EPS || izMax - izMin <= EPS) {
      out.add(piece);
      return;
    }
    if (ixMin - piece.minX() > EPS) {
      out.add(new Rect(piece.minX(), piece.minZ(), ixMin, piece.maxZ()));
    }
    if (piece.maxX() - ixMax > EPS) {
      out.add(new Rect(ixMax, piece.minZ(), piece.maxX(), piece.maxZ()));
    }
    if (izMin - piece.minZ() > EPS) {
      out.add(new Rect(ixMin, piece.minZ(), ixMax, izMin));
    }
    if (piece.maxZ() - izMax > EPS) {
      out.add(new Rect(ixMin, izMax, ixMax, piece.maxZ()));
    }
  }
}
