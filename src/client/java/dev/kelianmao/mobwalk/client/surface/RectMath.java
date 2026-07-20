package dev.kelianmao.mobwalk.client.surface;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, stateless rect/interval algebra used by {@link SurfaceSelection}:
 * subtract/union/merge, footprint adjacency, the static flood, and related
 * helpers. No world access and no held state.
 */
public final class RectMath {
  private RectMath() {
  }

  // An axis-aligned XZ rectangle (world coords), the per-box footprint clip in
  // exposeBox and the mutable merge accumulator. Package-private so the pure
  // geometry ops can be unit-tested with synthetic rects (no world).
  record Rect(double minX, double minZ, double maxX, double maxZ) {
  }

  // Grouping key for the merge: two doubles quantized to 1/1024 of a block
  // (finer than any collision-box edge, incl. dilated 0.3 / 0.975 later) so
  // equal spans hash together despite float noise.
  private record SpanKey(long a, long b) {
  }

  // Tolerance for the double coordinate compares (box edges are multiples of
  // 1/16). Used to drop subtraction slivers and to test edge adjacency/overlap.
  private static final double EPS = 1.0e-6;

  // BFS over merged rects: an edge exists iff footprints share an edge with
  // positive overlap and the height difference is within reach (a single
  // threshold). Seeds are the merged rects that cover a seed surface. Each
  // reached rect is re-emitted carrying its BFS hop-count from the seed (0 =
  // seed) as its debug flood-depth tag (see StandableRect.depth).
  // Package-private for unit tests (synthetic rects, no world).
  static List<StandableRect> flood(List<StandableRect> rects, List<StandableRect> seeds, double reach) {
    int n = rects.size();
    boolean[] visited = new boolean[n];
    int[] depth = new int[n];
    Deque<Integer> queue = new ArrayDeque<>();
    for (int i = 0; i < n; i++) {
      if (coversAnySeed(rects.get(i), seeds)) {
        visited[i] = true;
        depth[i] = 0;
        queue.addLast(i);
      }
    }

    List<StandableRect> out = new ArrayList<>();
    while (!queue.isEmpty()) {
      int i = queue.pollFirst();
      StandableRect cur = rects.get(i);
      out.add(withDepth(cur, depth[i]));
      for (int j = 0; j < n; j++) {
        if (visited[j]) {
          continue;
        }
        StandableRect other = rects.get(j);
        if (Math.abs(other.collisionTopY() - cur.collisionTopY()) <= reach + EPS && footprintAdjacent(cur, other)) {
          visited[j] = true;
          depth[j] = depth[i] + 1;
          queue.addLast(j);
        }
      }
    }
    return out;
  }

  // Copy of a rect carrying a debug flood-depth tag (see StandableRect.depth).
  private static StandableRect withDepth(StandableRect r, int depth) {
    return new StandableRect(r.minX(), r.minZ(), r.maxX(), r.maxZ(),
      r.collisionTopY(), r.visualTopY(), depth);
  }

  // For each merged rect, the min flood-depth over the raw (pre-merge) reached
  // nodes it covers: same collision top (|dTopY| < EPS) and positive-area XZ
  // overlap. Merge is area-preserving over the reached nodes, so every merged
  // rect is covered by >= 1 node and thus gets a depth (never left at -1).
  // Package-private for unit tests (synthetic rects, no world).
  static int[] depthForMerged(List<StandableRect> merged, List<StandableRect> rawNodes, int[] rawDepths) {
    int[] out = new int[merged.size()];
    for (int i = 0; i < merged.size(); i++) {
      StandableRect m = merged.get(i);
      int best = -1;
      for (int k = 0; k < rawNodes.size(); k++) {
        StandableRect r = rawNodes.get(k);
        if (Math.abs(r.collisionTopY() - m.collisionTopY()) > EPS) {
          continue;
        }
        if (Math.min(r.maxX(), m.maxX()) - Math.max(r.minX(), m.minX()) <= EPS
            || Math.min(r.maxZ(), m.maxZ()) - Math.max(r.minZ(), m.minZ()) <= EPS) {
          continue;
        }
        if (best < 0 || rawDepths[k] < best) {
          best = rawDepths[k];
        }
      }
      out[i] = best;
    }
    return out;
  }

  // A merged rect is a seed iff it is coplanar with and overlaps (positive area)
  // one of the seed block's surfaces; merge partitions the union, so the surface
  // lands in exactly one merged rect.
  private static boolean coversAnySeed(StandableRect rect, List<StandableRect> seeds) {
    for (StandableRect seed : seeds) {
      if (Math.abs(rect.collisionTopY() - seed.collisionTopY()) <= EPS
          && Math.min(rect.maxX(), seed.maxX()) - Math.max(rect.minX(), seed.minX()) > EPS
          && Math.min(rect.maxZ(), seed.maxZ()) - Math.max(rect.minZ(), seed.minZ()) > EPS) {
        return true;
      }
    }
    return false;
  }

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

  // Production merge used by the lazy flood path. Splits raw nodes into inner
  // (depth < limit) and frontier (depth >= limit), unions each independently,
  // then subtracts the inner area from the frontier so the two tile cleanly
  // with no overlap (inner has priority in the overlap zone where dilated
  // surfaces grow into each other). Result: the frontier ring keeps its real
  // depth and is never collapsed into the inner blob, so the renderer's
  // depth-based perimeter suppression and grey-blend work correctly.
  // Groups by collisionTopY and visualTopY (within EPS); overlapping
  // translucent quads would double-blend into darker seams without the union
  // re-cut. Grouping on visualTopY keeps raised paint (honey/cactus at
  // 15/16→1.0) from contaminating flush coplanar neighbours (dirt path at
  // 15/16→15/16). Greedy strip-merge is not a minimal partition, but any miss
  // only costs an extra interior skirt, never reachability.
  // Package-private for unit tests.
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
      .comparingDouble((Integer a) -> nodes.get(a).collisionTopY())
      .thenComparingDouble(a -> nodes.get(a).visualTopY()));
    for (int i = 0; i < idx.length; i++) {
      sorted.set(i, nodes.get(idx[i]));
      sortedDepths[i] = nodeDepths[idx[i]];
    }

    List<StandableRect> out = new ArrayList<>();
    int i = 0;
    while (i < sorted.size()) {
      double collisionTopY = sorted.get(i).collisionTopY();
      double visualTopY = sorted.get(i).visualTopY();
      int j = i + 1;
      while (j < sorted.size()
          && sorted.get(j).collisionTopY() - collisionTopY <= EPS
          && Math.abs(sorted.get(j).visualTopY() - visualTopY) <= EPS) {
        j++;
      }

      List<Rect> innerRaw = new ArrayList<>();
      List<Rect> frontierRaw = new ArrayList<>();
      for (int k = i; k < j; k++) {
        StandableRect r = sorted.get(k);
        Rect rect = new Rect(r.minX(), r.minZ(), r.maxX(), r.maxZ());
        if (sortedDepths[k] >= limit) {
          frontierRaw.add(rect);
        } else {
          innerRaw.add(rect);
        }
      }

      // Union + strip-merge inner nodes.
      List<Rect> innerMerged = stripMerge(union(innerRaw));

      // Subtract the merged inner area from each frontier node, then union
      // + strip-merge the remnants. The subtraction removes the dilation
      // overlap so inner and frontier tile cleanly (inner has priority).
      List<Rect> frontierRemnants = new ArrayList<>();
      for (Rect fr : frontierRaw) {
        frontierRemnants.addAll(subtractRects(fr, innerMerged));
      }
      List<Rect> frontierMerged = stripMerge(union(frontierRemnants));

      // Tag depths: inner rects get min covering depth, frontier rects
      // get limit (they only overlap frontier raw nodes after subtraction).
      for (Rect r : innerMerged) {
        int best = -1;
        for (int k = i; k < j; k++) {
          StandableRect node = sorted.get(k);
          if (Math.min(node.maxX(), r.maxX()) - Math.max(node.minX(), r.minX()) <= EPS
              || Math.min(node.maxZ(), r.maxZ()) - Math.max(node.minZ(), r.minZ()) <= EPS) {
            continue;
          }
          if (best < 0 || sortedDepths[k] < best) {
            best = sortedDepths[k];
          }
        }
        out.add(new StandableRect(r.minX(), r.minZ(), r.maxX(), r.maxZ(),
          collisionTopY, visualTopY, best));
      }
      for (Rect r : frontierMerged) {
        out.add(new StandableRect(r.minX(), r.minZ(), r.maxX(), r.maxZ(),
          collisionTopY, visualTopY, limit));
      }
      i = j;
    }
    return out;
  }

  // The X-then-Z greedy strip merge loop used by mergeCoplanarSplitFrontier
  // (runs once per inner/frontier bucket).
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
