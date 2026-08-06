package dev.kelianmao.mobwalk.client.surface;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.kelianmao.mobwalk.MobWalk;
import dev.kelianmao.mobwalk.client.surface.WorldGeometry.ColumnBoxes;
import dev.kelianmao.mobwalk.client.surface.WorldGeometry.WorldBox;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Computes and holds the set of standable surfaces ({@link StandableRect}) drawn
 * by the overlay. In-memory only, not persisted.
 *
 * <p><b>v4 model: dilated arrangement &rarr; merge &rarr; flood (geometric
 * adjacency).</b> {@link #select} works in three phases:
 * <ol>
 * <li><b>Dilated arrangement</b> ({@link #exposeBox}): gather every collision box
 *     in a window of half-extent {@code radius} blocks (plus a {@code floor(W)+1}
 *     occluder margin so every box that can trim an edge candidate is captured —
 *     candidate and occluder each grow by {@code W/2}, so two cells up to
 *     {@code floor(W)+1} apart still interact — the same reach
 *     {@link LazyFlood} uses for neighbour search), grow each box's
 *     footprint by the entity half-width {@code W/2} (Minkowski sum with the
 *     {@code WxW} square is just the rect grown on every side), and keep a box-top
 *     at height {@code T} over the footprint where <b>no dilated box spans
 *     immediately above it</b> ({@code minY <= T < maxY}). That single
 *     spans-above/buried test is the occlusion: it both cuts a lower top back by
 *     {@code W/2} near a taller box and supplies that taller box's own (dilated)
 *     top as the surface to stand on. Non-burying overlaps (an air gap between two
 *     tops) stay as <b>distinct levels</b> (not {@code max collisionTopY}), so multi-level
 *     is preserved. {@code W = 0} (Point) makes dilation a no-op — neighbor
 *     footprints only abut (zero overlap), so it reproduces the per-block result.
 * <li><b>Merge</b> coplanar ({@code |dTopY| < EPS}) rects into maximal rectangles.
 *     Dilated neighbor tops grow into each other, so each level is first
 *     <b>re-cut to a non-overlapping union</b> (vertical-slab sweep) — otherwise
 *     overlapping translucent quads double-blend into darker seams — then a greedy
 *     strip merge along X then Z collapses the grid back, so a flat floor becomes
 *     one rect instead of many cells (clean skirts, fewer quads).
 * <li><b>Flood</b> from the click origin (raw dilated footprints of the clicked
 *     block, non-emitted) into the exposed-top graph by <b>geometric
 *     adjacency</b>: two rects are connected iff their footprints share an edge
 *     with positive overlap ({@link RectMath#footprintAdjacent}) and the lower of
 *     the two can climb to the higher within the active {@link EntityProfile}'s
 *     {@code reach} (one undirected edge). Seed-block exposed
 *     tops start at depth 0; other tops adjacent to the origin start at depth 1.
 *     This one test subsumes the old same-block / own-column / 4-neighbor-column
 *     cases: a glass pane on a block connects to that block's exposed ring because
 *     their footprints abut at the hole edges, no special case needed. A dilated
 *     perch over the void floods directly because adjacency is geometric (no column).
 * </ol>
 *
 * <p>For a gap of width {@code g} flanked by support, each side grows {@code W/2},
 * leaving {@code g - W} uncovered: {@code g <= W} bridges, {@code g > W} leaves a
 * hole — "can't fall into a hole smaller than yourself". Drop edges that trap the
 * entity are labelled by {@link HoleBeams} after the flood.
 *
 * <p><b>Radius is a BFS depth limit</b> (max hop-count from the click origin), not a
 * spatial X/Z window: horizontal reach is unbounded; termination comes from the
 * hop-count cap plus a Y band of about {@code oy ± radius}. Connectivity gating is
 * unchanged (a drop {@code > reach} or a disconnected patch is never reached).
 *
 * <p>Not thread-safe by design. It is mutated only on the client thread
 * ({@code select}/{@code clear}); the render thread reads only the immutable
 * {@link #allRects()} snapshot the overlay publishes into a {@code volatile} field.
 */
public final class SurfaceSelection {
  // A dilated standable surface tagged with its source cell, the lazy flood's
  // node. The cell bounds the column-local neighbour search; equality is by value
  // (StandableRect is a record) so the visited set dedupes naturally.
  private record CellSurface(StandableRect rect, int cx, int cz) {
  }

  /**
   * Non-emitted click origin: the raw pre-occlusion dilated footprint of one
   * clicked-block collision box at its {@code collisionTopY}. Probes enter the
   * first BFS wave via adjacency only; they are never painted.
   */
  record OriginProbe(double minX, double minZ, double maxX, double maxZ,
      double collisionTopY, HazardClass hazard) {
  }

  /**
   * An exposed top considered for the origin wave, carrying whether its source
   * box is the clicked seed block (depth 0) or a neighbour (depth 1).
   */
  record OriginCandidate(StandableRect rect, int cx, int cz, boolean fromSeedBlock) {
  }

  /**
   * One node of the flood's initial wave: an exposed top with its shortest-path
   * depth from the click origin (0 = seed-block top, 1 = first hop).
   */
  record SeedWaveEntry(StandableRect rect, int cx, int cz, int depth) {
  }

  // Column index key (block X/Z) for the per-column box lookup used to bound the
  // occluder search to the candidate's immediate neighborhood. Package-private so
  // the headroom predicate (exposeBox) can be unit-tested with a synthetic index.
  record ColKey(int x, int z) {
  }

  // Shared lazy world-surface index: the per-column collision boxes found so far,
  // which block rows have been queried, and the memoized per-box exposure (tops).
  // The flood (LazyFlood) owns this index; HoleBeams.gatherLedgesFrom also uses it
  // so the occluder shell has ONE definition — occluderColumns in XZ, rows
  // floor(yMax)-1 .. floor(yMax+height)+1 in Y (the headroom extension) — and cannot
  // drift between the two callers. Reads the world only through ColumnBoxes.
  static final class WorldSurfaceIndex {
    private final ColumnBoxes source;
    private final double halfW;
    private final double height;
    private final int yLo;
    private final int yHi;
    private final Map<ColKey, List<WorldBox>> index = new HashMap<>();
    private final Map<ColKey, BitSet> scanned = new HashMap<>();
    private final Map<WorldBox, List<StandableRect>> boxSurfaces = new HashMap<>();

    WorldSurfaceIndex(ColumnBoxes source, double halfW, double height, int yLo, int yHi) {
      this.source = source;
      this.halfW = halfW;
      this.height = height;
      this.yLo = yLo;
      this.yHi = yHi;
    }

    // The boxes of column (cx,cz) discovered so far, or null if never scanned.
    // Callers snapshot size() before calling tops (which may append occluder rows
    // to this same live list).
    List<WorldBox> column(int cx, int cz) {
      return index.get(new ColKey(cx, cz));
    }

    // Query (and memoize) the collision boxes in column (cx,cz) for every block
    // row in [a,b] not yet scanned, clamped to the band [yLo,yHi]. Idempotent:
    // each (column,row) is queried at most once, so no duplicate boxes.
    void ensureRows(int cx, int cz, int a, int b) {
      a = Math.max(a, yLo);
      b = Math.min(b, yHi);
      if (a > b) {
        return;
      }
      ColKey key = new ColKey(cx, cz);
      BitSet bits = scanned.get(key);
      if (bits == null) {
        bits = new BitSet(yHi - yLo + 1);
        scanned.put(key, bits);
        index.put(key, new ArrayList<>());
      }
      List<WorldBox> column = index.get(key);
      for (int y = a; y <= b; y++) {
        int bit = y - yLo;
        if (bits.get(bit)) {
          continue;
        }
        bits.set(bit);
        column.addAll(source.at(cx, y, cz));
      }
    }

    // Dilated, occluder-trimmed tops of a single box (memoized). Exposes the box's
    // occluder shell — the columns exposeBox scans, over the rows that can hold a
    // box intruding into the standing column (T, T+height] above this top — before
    // computing, so the headroom occlusion test sees a complete shell. The upper
    // shell row is extended by floor(yMax+height)+1 (vs the box's own top) so
    // headroom occluders ABOVE the top are scanned, not just the buried ones;
    // height == 0 collapses it to row±1 (today's spans-above shell).
    List<StandableRect> tops(WorldBox box) {
      List<StandableRect> cached = boxSurfaces.get(box);
      if (cached != null) {
        return cached;
      }
      int[] win = occluderColumns(box, halfW);
      int row = (int) Math.floor(box.yMax());
      int rowHi = (int) Math.floor(box.yMax() + height) + 1;
      for (int cx = win[0]; cx <= win[1]; cx++) {
        for (int cz = win[2]; cz <= win[3]; cz++) {
          ensureRows(cx, cz, row - 1, rowHi);
        }
      }
      List<StandableRect> out = new ArrayList<>();
      exposeBox(box, index, halfW, height, out);
      boxSurfaces.put(box, out);
      return out;
    }
  }

  // Tolerance for the double coordinate compares (box edges are multiples of 1/16).
  private static final double EPS = RectMath.EPS;

  // The reached, merged surfaces from the last select (the draw set). Replaced
  // wholesale each select; an immutable snapshot is published by the overlay.
  private List<StandableRect> result = List.of();

  // Upward (occluder) skirt spans for the last select: edge sub-spans of the
  // reached surfaces where a box rises above the surface (a wall) or hangs within
  // the entity's headroom (a ceiling). Computed compute-side (it reads collision
  // boxes), published alongside result. Replaced wholesale each select.
  private List<SkirtSpan> occluders = List.of();

  // Downward drop-skirt spans for the last select: each merged-rect edge minus its
  // equal-height merge seams (openSpans) minus the occluder sub-spans above. Once a
  // per-frame O(n^2) render-side scan; now computed compute-side once per select
  // (behavior-preserving) so emit just draws published spans, and so Milestone 5's
  // hole classification can share this same drop-edge pass. Replaced each select.
  private List<SkirtSpan> downSkirts = List.of();

  // Hole beam spans for the last select: drop sub-spans with no reached surface
  // below (void, or unreached ground). HazardClass.HOLE. Replaced each select.
  private List<BeamSpan> holes = List.of();

  // Hazard perimeter beam spans for the last select (WATER/LAVA rect edges minus
  // same-hazard seams). Replaced each select.
  private List<BeamSpan> hazards = List.of();

  // One-shot geometry dump for /mobwalk dump: when set, the next select() logs
  // reached/merged/occluders/skirts/holes/hazards then clears the flag. Armed by
  // requestDebugDump() only.
  private boolean debugDumpOnce = false;
  // Pre-merge reached tops captured only when debugDumpOnce is set.
  private List<StandableRect> debugReachedPreMerge = List.of();

  /** Arm a one-shot pipeline dump on the next {@link #select}. */
  public void requestDebugDump() {
    debugDumpOnce = true;
  }

  /**
   * Replace the selection with the merged standable surfaces reachable from the
   * click origin at {@code start}, within BFS hop-count {@code radius} of that
   * origin, for the entity's width/reach, across footprint-adjacent height-gated
   * steps. The origin is the clicked block's raw dilated collision footprints
   * (non-emitted); only exposed tops enter the painted set (depth 0 on the seed
   * block's own exposed tops, depth 1 on other tops adjacent to the origin).
   *
   * <p>Runs the output-sensitive {@link LazyFlood} (on-demand column/row
   * exposure), then computes occluders, down-skirts, and holes.
   *
   * <p>{@code computeVisualTop} controls whether the extra visible/outline-top
   * read (for render-taller-than-collide blocks; see {@code WorldGeometry.visibleTop} /
   * {@code exposeBox}) is done. Off, every block's outline top collapses to its
   * collision top, so nothing raises and the neighbour split never fires
   * ({@code visualTopY == collisionTopY} on every rect). It is a per-block cost paid only
   * when the Appearance render toggle wants it, so flipping the toggle re-runs
   * {@code select} (see {@code CollisionSurfaceOverlay}).
   */
  public void select(Level level, BlockPos start, int radius, EntityProfile profile,
      boolean computeVisualTop, boolean swimmableFluids, double fluidEscape) {
    boolean dump = debugDumpOnce;
    debugReachedPreMerge = List.of();
    LazyFlood lazy = new LazyFlood(level, start, radius, profile, computeVisualTop,
      swimmableFluids, fluidEscape);
    result = lazy.run();
    if (dump) {
      debugReachedPreMerge = lazy.preMergeReached();
    }
    List<SkirtSpan> collisionOccluders =
      OccluderSkirts.compute(level, result, profile, swimmableFluids, false);
    List<SkirtSpan> dropEdges = DownSkirts.compute(result, collisionOccluders, false);
    // Dual rim (same as downs): collision for holes; visual for paint when raised.
    boolean raisedVisual = false;
    if (computeVisualTop) {
      for (StandableRect r : result) {
        if (Math.abs(r.visualTopY() - r.collisionTopY()) > EPS) {
          raisedVisual = true;
          break;
        }
      }
    }
    if (raisedVisual) {
      List<SkirtSpan> visualOccluders =
        OccluderSkirts.compute(level, result, profile, swimmableFluids, true);
      downSkirts = DownSkirts.compute(result, visualOccluders, true);
      occluders = visualOccluders;
    } else {
      downSkirts = dropEdges;
      occluders = collisionOccluders;
    }
    holes = HoleBeams.compute(level, result, dropEdges, profile, swimmableFluids);
    hazards = HazardBeams.compute(result);
    if (dump) {
      logFloodDebug(profile, start, radius, computeVisualTop, fluidEscape,
        raisedVisual ? collisionOccluders : null);
      debugDumpOnce = false;
      debugReachedPreMerge = List.of();
    }
  }

  private void logFloodDebug(EntityProfile profile, BlockPos start, int radius,
      boolean computeVisualTop, double fluidEscape, List<SkirtSpan> collisionOccluders) {
    MobWalk.LOGGER.info(
      "[flood-debug] profile={} W={} H={} reach={} fluidEscape={} seed={} radius={} visualTop={}",
      profile.name(), profile.width(), profile.height(), profile.reach(), fluidEscape,
      start, radius, computeVisualTop);
    logFloodDebugRects("reached", debugReachedPreMerge);
    logFloodDebugRects("merged", result);
    if (collisionOccluders != null) {
      logFloodDebugOccluders("occluders-collision", collisionOccluders);
      logFloodDebugOccluders("occluders-paint", occluders);
    } else {
      logFloodDebugOccluders("occluders", occluders);
    }
    MobWalk.LOGGER.info("[flood-debug] downskirts={}", downSkirts.size());
    for (SkirtSpan s : downSkirts) {
      MobWalk.LOGGER.info(
        "[flood-debug]   drop alongX={} maxSide={} line={} [{},{}] baseY={} visualBaseY={} maxExtent={}",
        s.alongX(), s.maxSide(), s.line(), s.lo(), s.hi(),
        s.baseY(), s.visualBaseY(), s.maxExtent());
    }
    MobWalk.LOGGER.info("[flood-debug] holes={}", holes.size());
    for (BeamSpan s : holes) {
      MobWalk.LOGGER.info(
        "[flood-debug]   hole alongX={} line={} [{},{}] visualBaseY={} hazard={}",
        s.alongX(), s.line(), s.lo(), s.hi(), s.visualBaseY(), s.hazard());
    }
    MobWalk.LOGGER.info("[flood-debug] hazards={}", hazards.size());
    for (BeamSpan s : hazards) {
      MobWalk.LOGGER.info(
        "[flood-debug]   hazard alongX={} line={} [{},{}] visualBaseY={} hazard={}",
        s.alongX(), s.line(), s.lo(), s.hi(), s.visualBaseY(), s.hazard());
    }
  }

  private static void logFloodDebugOccluders(String label, List<SkirtSpan> spans) {
    MobWalk.LOGGER.info("[flood-debug] {}={}", label, spans.size());
    for (SkirtSpan s : spans) {
      MobWalk.LOGGER.info(
        "[flood-debug]   occ alongX={} side={} line={} [{},{}] baseY={} visualBaseY={} maxExtent={}",
        s.alongX(), s.maxSide(), s.line(), s.lo(), s.hi(),
        s.baseY(), s.visualBaseY(), s.maxExtent());
    }
  }

  private static void logFloodDebugRects(String label, List<StandableRect> rects) {
    MobWalk.LOGGER.info("[flood-debug] {}={}", label, rects.size());
    for (StandableRect r : rects) {
      MobWalk.LOGGER.info(
        "[flood-debug]   {} [{},{}]x[{},{}] collisionTopY={} visualTopY={} hazard={} depth={} frontier={}",
        label, r.minX(), r.maxX(), r.minZ(), r.maxZ(),
        r.collisionTopY(), r.visualTopY(), r.hazard(), r.depth(), r.frontier());
    }
  }

  public void clear() {
    result = List.of();
    occluders = List.of();
    downSkirts = List.of();
    holes = List.of();
    hazards = List.of();
    debugDumpOnce = false;
    debugReachedPreMerge = List.of();
  }

  /** Immutable snapshot of the reached surfaces (colored at draw). */
  public List<StandableRect> allRects() {
    return result;
  }

  /** Published UP skirts (paint rim when raises are active). */
  public List<SkirtSpan> allOccluders() {
    return occluders;
  }

  /** Immutable snapshot of the downward drop-skirt spans for the reached set. */
  public List<SkirtSpan> allDownSkirts() {
    return downSkirts;
  }

  /** Immutable snapshot of hole beam spans ({@link HazardClass#HOLE}) for the reached set. */
  public List<BeamSpan> allHoles() {
    return holes;
  }

  /** Immutable snapshot of hazard perimeter beam spans for the reached set. */
  public List<BeamSpan> allHazards() {
    return hazards;
  }

  /**
   * Initial BFS wave from non-emitted {@link OriginProbe}s: keep candidates that
   * are footprint-adjacent to a probe and climbable under {@code climb}; assign
   * depth 0 when {@code fromSeedBlock}, else 1; keep the minimum depth on
   * duplicates; drop depths above {@code depthLimit}. Returns depth-0 entries
   * before depth-1 so a FIFO queue preserves shortest-path order.
   * Package-private for unit tests (synthetic probes/candidates, no world).
   */
  static List<SeedWaveEntry> assignOriginWave(List<OriginProbe> probes,
      List<OriginCandidate> candidates, ClimbRule climb, int depthLimit) {
    if (probes.isEmpty() || candidates.isEmpty() || depthLimit < 0) {
      return List.of();
    }
    Map<CellSurface, Integer> best = new HashMap<>();
    for (OriginCandidate c : candidates) {
      StandableRect r = c.rect();
      boolean adjacent = false;
      for (OriginProbe p : probes) {
        StandableRect probeRect = probeAsRect(p);
        if (!climb.climbs(probeRect, r)) {
          continue;
        }
        if (probeFootprintAdjacent(p, r)) {
          adjacent = true;
          break;
        }
      }
      if (!adjacent) {
        continue;
      }
      int depth = c.fromSeedBlock() ? 0 : 1;
      if (depth > depthLimit) {
        continue;
      }
      CellSurface key = new CellSurface(r, c.cx(), c.cz());
      best.merge(key, depth, RectMath::minDepth);
    }
    List<SeedWaveEntry> depth0 = new ArrayList<>();
    List<SeedWaveEntry> depth1 = new ArrayList<>();
    for (Map.Entry<CellSurface, Integer> e : best.entrySet()) {
      CellSurface s = e.getKey();
      SeedWaveEntry entry = new SeedWaveEntry(s.rect(), s.cx(), s.cz(), e.getValue());
      if (e.getValue() == 0) {
        depth0.add(entry);
      } else {
        depth1.add(entry);
      }
    }
    List<SeedWaveEntry> out = new ArrayList<>(depth0.size() + depth1.size());
    out.addAll(depth0);
    out.addAll(depth1);
    return out;
  }

  // footprintAdjacent for a raw dilated probe vs an exposed standable top.
  private static boolean probeFootprintAdjacent(OriginProbe p, StandableRect r) {
    return RectMath.footprintAdjacent(probeAsRect(p), r);
  }

  private static StandableRect probeAsRect(OriginProbe p) {
    return new StandableRect(p.minX(), p.minZ(), p.maxX(), p.maxZ(), p.collisionTopY(),
      p.collisionTopY(), p.hazard());
  }

  /**
   * The standable area contributed by one collision box's top, dilated by the
   * entity half-width and clipped to where it is not buried, appended to
   * {@code out}.
   *
   * <p>The candidate footprint is {@code target} grown by {@code halfW} on every
   * side. It is then cut by every dilated box that <em>rises above</em> the top
   * ({@code yMax > T}, {@code T = target.maxY}) <b>and</b> either is <b>buried</b>
   * over it (reaches down to/below the surface, {@code yMin <= T} — a box resting
   * directly on the top has {@code yMin == T}, a box straddling it has
   * {@code yMin < T}) <b>or</b> intrudes into the entity's <b>standing column</b>
   * {@code (T, T+height)} as a headroom ceiling ({@code yMin < T+height}). This is
   * the headroom generalization of the spans-above/buried test: the buried term is
   * the {@code height == 0} (Point) base case — exactly the old
   * {@code minY <= T < maxY} test, so a box directly on the surface still buries the
   * top and embedded/stacked tops are removed; the headroom term additionally lets a
   * ceiling rob headroom from the floor below it. The {@code yMax > T} lower bound is
   * strict so the box being stood on ({@code yMax == T}) never self-occludes, and a
   * ceiling bottom exactly at {@code T+height} ({@code height > 0}) is just-enough
   * clearance (neither term fires). The single occlusion rule both buries a lower
   * top and (via the covering box's own call) supplies the higher surface. Occluder
   * search is bounded to the columns the dilated footprint can reach via the
   * per-column {@code index}. {@code halfW == 0} leaves neighbor footprints merely
   * abutting (zero overlap), so Point matches the undilated per-block result.
   */
  static void exposeBox(WorldBox target, Map<ColKey, List<WorldBox>> index, double halfW,
      double height, List<StandableRect> out) {
    double collisionTopY = target.yMax();
    // Visible-face raise: topmost collision surface with a taller outline (soul
    // sand, mud) exposes that outline; stair treads / bottom slabs / fences stay
    // at collisionTopY. Walkability stays on collisionTopY.
    double visualTopY = (Math.abs(collisionTopY - target.blockCollisionTop()) <= EPS
        && target.blockOutlineTop() > collisionTopY + EPS)
      ? target.blockOutlineTop()
      : collisionTopY;
    RectMath.Rect base = new RectMath.Rect(
      target.minX() - halfW, target.minZ() - halfW,
      target.maxX() + halfW, target.maxZ() + halfW);

    List<RectMath.Rect> clipRects = new ArrayList<>();
    // Neighbours that render taller than they collide (soul sand, mud, …) and
    // taller than this top: a dilated footprint that sits over their undilated
    // column would paint buried under their full-cube mesh, so those cores raise
    // visualTopY on the overlap only (collisionTopY stays). Collected in the
    // same column window as clipRects — they are often not occluders (their
    // collision top is below T, e.g. path 15/16 over soul sand 14/16).
    List<RectMath.Rect> raiseCores = new ArrayList<>();
    List<Double> raiseOutlines = new ArrayList<>();
    int[] win = occluderColumns(target, halfW);
    for (int cx = win[0]; cx <= win[1]; cx++) {
      for (int cz = win[2]; cz <= win[3]; cz++) {
        List<WorldBox> column = index.get(new ColKey(cx, cz));
        if (column == null) {
          continue;
        }
        for (WorldBox other : column) {
          if (other == target) {
            continue;
          }
          // Non-occluding support surfaces (fluid surfaces) supply a standable
          // top and no collision volume, so they contribute no clip rect. Occlusion
          // (burial and headroom) is a property of volume only — a zero-thickness
          // box still headroom-occludes; this bit skips the clip path entirely.
          if (other.occludes()) {
            // Occluder iff it rises above T AND either reaches down to/below
            // the surface (buried: a box resting on top has yMin == T, a box
            // straddling T has yMin < T) OR floats within the standing column
            // (T, T+H) (a headroom ceiling). The buried term is the H == 0 base
            // case (Point) — without it a box sitting directly on the surface
            // would NOT occlude and every embedded/stacked top would leak.
            boolean buried = other.yMin() <= collisionTopY + EPS;
            boolean headroomCeiling = other.yMin() < collisionTopY + height - EPS;
            if (other.yMax() > collisionTopY + EPS && (buried || headroomCeiling)) {
              clipRects.add(new RectMath.Rect(
                other.minX() - halfW, other.minZ() - halfW,
                other.maxX() + halfW, other.maxZ() + halfW));
            }
          }
          if (other.blockOutlineTop() > other.blockCollisionTop() + EPS
              && other.blockOutlineTop() > collisionTopY + EPS) {
            raiseCores.add(new RectMath.Rect(
              other.minX(), other.minZ(), other.maxX(), other.maxZ()));
            raiseOutlines.add(other.blockOutlineTop());
          }
        }
      }
    }

    for (RectMath.Rect exposed : RectMath.subtractRects(base, clipRects)) {
      emitWithNeighborVisualRaise(exposed, collisionTopY, visualTopY, target.hazard(),
        raiseCores, raiseOutlines, out);
    }
  }

  // Split an exposed standable rect so the parts that sit over a raised-outline
  // neighbour's undilated footprint draw at that neighbour's outline top (paint on
  // the cube), while the rest keep {@code visualTopY}. {@code collisionTopY} is
  // unchanged on every piece; {@code hazard} stays the source box's identity.
  // When several neighbours cover a region, the highest outline wins (claimed
  // high→low so lower cores do not re-cover).
  private static void emitWithNeighborVisualRaise(RectMath.Rect exposed, double collisionTopY,
      double visualTopY, HazardClass hazard,
      List<RectMath.Rect> raiseCores, List<Double> raiseOutlines, List<StandableRect> out) {
    if (raiseCores.isEmpty()) {
      out.add(new StandableRect(exposed.minX(), exposed.minZ(), exposed.maxX(), exposed.maxZ(),
        collisionTopY, visualTopY, hazard));
      return;
    }
    List<Integer> hit = new ArrayList<>();
    List<RectMath.Rect> coresHere = new ArrayList<>();
    for (int i = 0; i < raiseCores.size(); i++) {
      RectMath.Rect core = raiseCores.get(i);
      if (RectMath.intersectRect(exposed, core) != null) {
        hit.add(i);
        coresHere.add(core);
      }
    }
    if (hit.isEmpty()) {
      out.add(new StandableRect(exposed.minX(), exposed.minZ(), exposed.maxX(), exposed.maxZ(),
        collisionTopY, visualTopY, hazard));
      return;
    }
    for (RectMath.Rect remnant : RectMath.subtractRects(exposed, coresHere)) {
      out.add(new StandableRect(remnant.minX(), remnant.minZ(), remnant.maxX(), remnant.maxZ(),
        collisionTopY, visualTopY, hazard));
    }
    hit.sort((a, b) -> Double.compare(raiseOutlines.get(b), raiseOutlines.get(a)));
    List<RectMath.Rect> claimed = new ArrayList<>();
    for (int i : hit) {
      RectMath.Rect inter = RectMath.intersectRect(exposed, raiseCores.get(i));
      if (inter == null) {
        continue;
      }
      double outline = raiseOutlines.get(i);
      for (RectMath.Rect piece : RectMath.subtractRects(inter, claimed)) {
        out.add(new StandableRect(piece.minX(), piece.minZ(), piece.maxX(), piece.maxZ(),
          collisionTopY, outline, hazard));
      }
      claimed.add(inter);
    }
  }

  // {cxLo, cxHi, czLo, czHi}: the columns an occluder box must lie in to possibly
  // overlap {@code target}'s dilated footprint. A box in column (cx,*) spans
  // [cx,cx+1], dilated to [cx-halfW, cx+1+halfW], which overlaps the dilated base
  // [minX-halfW, maxX+halfW] iff floor(min - W) <= cx <= ceil(max + W) - 1, with
  // W = 2*halfW. Tight (Point's W=0 -> the box's own column only) yet conservative
  // (full-block span); the per-box spans-above + subtraction still does the real
  // geometry, so shrinking the window is purely efficiency and result-preserving.
  // Shared by exposeBox (used by both flood paths) and LazyFlood.ensureOccluders
  // so the occluder index and the scan stay in lock-step.
  private static int[] occluderColumns(WorldBox box, double halfW) {
    double w = 2.0 * halfW;
    return new int[] {
      (int) Math.floor(box.minX() - w),
      (int) Math.ceil(box.maxX() + w) - 1,
      (int) Math.floor(box.minZ() - w),
      (int) Math.ceil(box.maxZ() + w) - 1,
    };
  }

  /**
   * The lazy, output-sensitive flood. Instead of enumerating the whole
   * window+margin cube up front, it does a surface BFS that exposes geometry
   * <b>on demand</b> as it reaches it, so the cost tracks the reachable set
   * (and its immediate occluder shells) rather than the window volume — a big
   * win in narrow caves / against walls at high radius.
   *
   * <p><b>Lazy in Y too.</b> Columns are scanned only over the narrow block-row
   * windows the flood needs near its current height (tops within one {@code
   * reach} step, plus each box's occluder shell at the rows around its own top),
   * never the full {@code [yLo,yHi]} band. {@link #ensureRows} tracks scanned
   * rows per column (a {@link BitSet}) so each {@code (column,row)} is queried at
   * most once. The flood boots from the clicked block's raw dilated collision
   * footprints (non-emitted origin probes); only exposed tops enter the reached
   * set. This drops the per-column vertical factor from
   * {@code O(radius)} to {@code O(heights the flood actually traverses there)},
   * so open ground goes from {@code ~radius^3} toward {@code ~radius^2}. It is
   * orthogonal to (and composes with) the horizontal {@code occluderColumns}
   * tightening: total cost ~ columns x rows-per-column, and the two trim those
   * factors independently.
   *
   * <p>Nodes are <b>raw</b> dilated surfaces ({@link #exposeBox} output,
   * pre-merge), each tagged with its source cell. From a popped surface in cell
   * {@code c}, neighbours are sought in cells within Chebyshev {@code R} of
   * {@code c} ({@code R = floor(W)+1}: a bridged/abutting pair sits
   * {@code floor(W)+1} cells apart — {@code 1} for Point/Player, {@code 2} for
   * Ravager; <b>not</b> {@code ceil(W)}, which is {@code 0} for Point and would
   * never connect adjacent floor tiles). A neighbour cell's surfaces are computed
   * on demand (with each box's occluder shell — the columns {@link #exposeBox}
   * scans — exposed first, so the spans-above test sees a complete shell) and a
   * surface is enqueued iff it is unvisited,
   * {@link RectMath#footprintAdjacent}, and {@link ClimbRule#climbs} (the
   * collect height window {@code [h-reach, h+reach]} stays a valid superset).
   *
   * <p><b>Depth-bounded</b> (debug mode): the flood stops when BFS hop-count
   * exceeds {@code depthLimit} (Flood Radius in settings). There is no spatial
   * X/Z bound on which columns the flood can reach; the depth limit itself
   * provides termination. A generous Y band
   * ({@code oy ± depthLimit + 2}) still constrains vertical scanning since each step
   * changes height by at most {@code reach}. The merge/union runs <b>after</b>
   * the flood on the reached set only (area-preserving, so connectivity is
   * unchanged — {@code RectMath.footprintAdjacent} already treats overlap as connected).
   */
  private static final class LazyFlood {
    private final int ox;
    private final int oy;
    private final int oz;
    private final int depthLimit;
    private final double halfW;
    private final double reach;
    private final ClimbRule climb;
    // Chebyshev neighbour-search radius in cells = floor(W)+1 (see class doc).
    private final int neighbour;
    // The shared lazy world-surface index (per-column boxes + scanned rows + memoized
    // per-box tops). Lazy in Y: a column is scanned only over the narrow row windows
    // the flood needs near its current height. The ledge gather uses the same type so
    // the occluder shell is defined once (see WorldSurfaceIndex).
    private final WorldSurfaceIndex surfaces;
    // Pre-merge BFS reached set (for /mobwalk dump); empty until run() finishes.
    private List<StandableRect> preMergeReached = List.of();

    LazyFlood(Level level, BlockPos start, int depthLimit, EntityProfile profile,
        boolean computeVisualTop, boolean swimmableFluids, double fluidEscape) {
      BlockPos origin = start.immutable();
      this.ox = origin.getX();
      this.oy = origin.getY();
      this.oz = origin.getZ();
      this.depthLimit = depthLimit;
      this.halfW = profile.width() / 2.0;
      double height = profile.height();
      this.reach = profile.reach();
      this.climb = new ClimbRule(reach, fluidEscape);
      this.neighbour = (int) Math.floor(profile.width()) + 1;
      int bandLo = Math.max(oy - depthLimit - 1, level.getMinY());
      int bandHi = Math.min(oy + depthLimit + 1, level.getMaxY());
      this.surfaces = new WorldSurfaceIndex(
        WorldGeometry.levelColumnBoxes(level, computeVisualTop, swimmableFluids), halfW, height,
        bandLo, bandHi);
    }

    List<StandableRect> preMergeReached() {
      return preMergeReached;
    }

    List<StandableRect> run() {
      // Click origin: raw pre-occlusion dilated footprints of the clicked block
      // (never painted). First expansion enters the exposed-top graph at depth 0
      // (seed-block tops) or depth 1 (other abutting tops).
      List<OriginProbe> probes = buildClickProbes();
      if (probes.isEmpty()) {
        preMergeReached = List.of();
        return List.of();
      }
      List<OriginCandidate> candidates = new ArrayList<>();
      for (OriginProbe probe : probes) {
        double h = probe.collisionTopY();
        for (int cx = ox - neighbour; cx <= ox + neighbour; cx++) {
          for (int cz = oz - neighbour; cz <= oz + neighbour; cz++) {
            collectCandidates(cx, cz, h - reach, h + reach, candidates);
          }
        }
      }
      List<SeedWaveEntry> initial = assignOriginWave(probes, candidates, climb, depthLimit);
      if (initial.isEmpty()) {
        preMergeReached = List.of();
        return List.of();
      }
      // hopCount doubles as the visited set: a key is present iff visited, and its
      // value is the BFS hop-count from the click origin (0 = seed-block top).
      // FIFO + depth-0-before-depth-1 enqueue makes this the shortest distance.
      Map<CellSurface, Integer> hopCount = new HashMap<>();
      Deque<CellSurface> queue = new ArrayDeque<>();
      for (SeedWaveEntry entry : initial) {
        CellSurface s = new CellSurface(entry.rect(), entry.cx(), entry.cz());
        if (hopCount.putIfAbsent(s, entry.depth()) == null) {
          queue.addLast(s);
        }
      }
      // The reached raw (pre-merge) nodes and their depths, in lock-step; the
      // merged output's per-rect depth is aggregated (min) from these.
      List<StandableRect> reached = new ArrayList<>();
      List<Integer> reachedDepths = new ArrayList<>();
      while (!queue.isEmpty()) {
        CellSurface s = queue.pollFirst();
        int d = hopCount.get(s);
        reached.add(s.rect());
        reachedDepths.add(d);
        // At the depth limit: emit this node but don't explore its neighbors.
        if (d >= depthLimit) {
          continue;
        }
        double h = s.rect().collisionTopY();
        for (int cx = s.cx() - neighbour; cx <= s.cx() + neighbour; cx++) {
          for (int cz = s.cz() - neighbour; cz <= s.cz() + neighbour; cz++) {
            // A pair connects iff the LOWER of the two can climb to the higher, so
            // the window spans one climb in each role: up to h+reach for a candidate
            // this surface climbs to, down to h-reach for one that climbs to this.
            // Hence a drop deeper than one climb yields no edge (see geometry.md
            // "Reachability model") — which is what makes reached imply escapable.
            for (CellSurface t : collect(cx, cz, h - reach, h + reach)) {
              if (hopCount.containsKey(t)) {
                continue;
              }
              if (RectMath.footprintAdjacent(s.rect(), t.rect())
                  && climb.climbs(s.rect(), t.rect())) {
                hopCount.put(t, d + 1);
                queue.addLast(t);
              }
            }
          }
        }
      }
      preMergeReached = List.copyOf(reached);
      // Composite-priority merge: INNER surface classes then FRONTIER surface
      // classes in one priority partition (inner owns dilation overlap), so
      // the frontier ring stays a separate depth band.
      int[] rawDepths = new int[reachedDepths.size()];
      for (int i = 0; i < rawDepths.length; i++) {
        rawDepths[i] = reachedDepths.get(i);
      }
      return RectMath.mergeCoplanarSplitFrontier(reached, rawDepths, depthLimit);
    }

    // Raw pre-occlusion dilated footprints of collision boxes owned by the
    // clicked block (source block Y == oy). Non-emitted flood origin.
    private List<OriginProbe> buildClickProbes() {
      surfaces.ensureRows(ox, oz, oy, oy);
      List<WorldBox> column = surfaces.column(ox, oz);
      if (column == null) {
        return List.of();
      }
      List<OriginProbe> probes = new ArrayList<>();
      int count = column.size();
      for (int i = 0; i < count; i++) {
        WorldBox box = column.get(i);
        if (box.by() != oy) {
          continue;
        }
        probes.add(new OriginProbe(
          box.minX() - halfW, box.minZ() - halfW,
          box.maxX() + halfW, box.maxZ() + halfW,
          box.yMax(), box.hazard()));
      }
      return probes;
    }

    // Exposed tops of cell (cx,cz) in [topLo,topHi] with seed-block provenance,
    // appended to {@code out} (duplicates across overlapping probe windows are
    // fine — assignOriginWave keeps min depth).
    private void collectCandidates(int cx, int cz, double topLo, double topHi,
        List<OriginCandidate> out) {
      surfaces.ensureRows(cx, cz, (int) Math.floor(topLo) - 1, (int) Math.floor(topHi) + 1);
      List<WorldBox> column = surfaces.column(cx, cz);
      if (column == null) {
        return;
      }
      int count = column.size();
      for (int i = 0; i < count; i++) {
        WorldBox box = column.get(i);
        if (Math.abs(box.by() - oy) > depthLimit) {
          continue;
        }
        if (box.yMax() < topLo - EPS || box.yMax() > topHi + EPS) {
          continue;
        }
        boolean fromSeed = box.bx() == ox && box.by() == oy && box.bz() == oz;
        for (StandableRect r : surfaces.tops(box)) {
          out.add(new OriginCandidate(r, cx, cz, fromSeed));
        }
      }
    }

    // Node surfaces of cell (cx,cz) whose top lies in [topLo,topHi] and whose
    // source block is within the node cube. Exposes only the rows that can hold
    // such tops (plus each box's occluder shell), so vertical work tracks the
    // flood front, not the band. exposeBox is memoized per box.
    private List<CellSurface> collect(int cx, int cz, double topLo, double topHi) {
      surfaces.ensureRows(cx, cz, (int) Math.floor(topLo) - 1, (int) Math.floor(topHi) + 1);
      List<WorldBox> column = surfaces.column(cx, cz);
      if (column == null) {
        return List.of();
      }
      List<CellSurface> result = new ArrayList<>();
      // Index over an explicit count: tops() may append to this same column
      // (occluder rows in the box's own cell), and we must not revisit those.
      int count = column.size();
      for (int i = 0; i < count; i++) {
        WorldBox box = column.get(i);
        // Outside the cube's vertical band -> occluder only, never a node.
        if (Math.abs(box.by() - oy) > depthLimit) {
          continue;
        }
        if (box.yMax() < topLo - EPS || box.yMax() > topHi + EPS) {
          continue;
        }
        for (StandableRect r : surfaces.tops(box)) {
          result.add(new CellSurface(r, cx, cz));
        }
      }
      return result;
    }

  }
}
