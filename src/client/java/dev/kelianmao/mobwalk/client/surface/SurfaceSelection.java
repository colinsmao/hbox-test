package dev.kelianmao.mobwalk.client.surface;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

import dev.kelianmao.mobwalk.MobWalk;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

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
 *     with positive overlap ({@link RectMath#footprintAdjacent}) and their heights are
 *     within the active {@link EntityProfile}'s {@code reach}. Seed-block exposed
 *     tops start at depth 0; other tops adjacent to the origin start at depth 1.
 *     This one test subsumes the old same-block / own-column / 4-neighbor-column
 *     cases: a glass pane on a block connects to that block's exposed ring because
 *     their footprints abut at the hole edges, no special case needed. A dilated
 *     perch over the void floods directly because adjacency is geometric (no column).
 * </ol>
 *
 * <p>For a gap of width {@code g} flanked by support, each side grows {@code W/2},
 * leaving {@code g - W} uncovered: {@code g <= W} bridges, {@code g > W} leaves a
 * hole — "can't fall into a hole smaller than yourself". (This stage shows only
 * the <em>geometry</em>; explicit hole detection is a later milestone.)
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
  /**
   * Which fluid a swim plate came from. {@link #NONE} is ordinary geometry.
   */
  public enum FluidKind {
    NONE,
    WATER,
    LAVA
  }

  /**
   * Vanilla {@code LivingEntity.getFluidJumpThreshold()} — at or below this fluid
   * height the plate sits at the cell floor ({@code 0}) so it stays coplanar with
   * the solid underfoot; above it the plate sits at {@code getHeight}.
   */
  static final double FLUID_JUMP_THRESHOLD = 0.4;

  // One collision sub-box in absolute world coords: its (undilated) XZ footprint
  // plus its vertical extent. The arrangement dilates the footprint by W/2 on
  // demand; yMin/yMax drive the spans-above occlusion test. bx/by/bz are the
  // source block (LazyFlood's depth-band and seed-block tests run on these).
  // blockCollisionTop / blockOutlineTop are the SOURCE BLOCK's whole-shape tops
  // (collision vs visible/outline, world Y), carried so exposeBox can raise a
  // standable top to the visible face for render-taller-than-collide blocks (soul
  // sand, mud) without touching any walkability math (see exposeBox / StandableRect).
  // fluid: swim-plane identity (NONE on ordinary solids). occludes: participates in
  // burial/headroom clip — solids true; non-occluding support surfaces (fluid plates)
  // false. Zero-thickness geometry alone still headroom-occludes; this bit skips clip.
  // Package-private for unit tests (synthetic boxes feed the classifier/headroom).
  record WorldBox(int bx, int by, int bz,
      double minX, double minZ, double maxX, double maxZ, double yMin, double yMax,
      double blockCollisionTop, double blockOutlineTop,
      FluidKind fluid, boolean occludes) {
    // Boxes gathered as occluders/ledges only never become a drawn top, so they
    // default both block tops to yMax (visualTopY then never raises off yMax).
    WorldBox(int bx, int by, int bz,
        double minX, double minZ, double maxX, double maxZ, double yMin, double yMax) {
      this(bx, by, bz, minX, minZ, maxX, maxZ, yMin, yMax, yMax, yMax, FluidKind.NONE, true);
    }

    WorldBox(int bx, int by, int bz,
        double minX, double minZ, double maxX, double maxZ, double yMin, double yMax,
        double blockCollisionTop, double blockOutlineTop) {
      this(bx, by, bz, minX, minZ, maxX, maxZ, yMin, yMax, blockCollisionTop, blockOutlineTop,
        FluidKind.NONE, true);
    }
  }

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
      double collisionTopY) {
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

  // World-read port of the shared WorldSurfaceIndex: the collision boxes of the
  // block at (x,y,z) in absolute WorldBox coords (empty if none). Production wraps
  // the Level read (the flood's producer also fills the visible/outline top);
  // tests inject a synthetic world so the lazy scan window that builds the occluder
  // index (not just exposeBox) is exercised directly.
  @FunctionalInterface
  interface ColumnBoxes {
    List<WorldBox> at(int x, int y, int z);
  }

  // Shared lazy world-surface index: the per-column collision boxes found so far,
  // which block rows have been queried, and the memoized per-box exposure (tops).
  // Both the flood (LazyFlood) and the ledge gather (gatherLedgesFrom) sit on this,
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

  // Per-BlockState memo of the block's outline (visible) top, relative to the block
  // origin (i.e. state.getShape().max(Y)); NaN means "no separate outline, don't
  // raise". Populated lazily the first time each distinct state is seen, so the
  // visible-top read (getShape) is paid at most once per block STATE rather than per
  // block instance — no full-block heuristic, so a modded/future block that renders
  // taller than it collides is caught automatically the first time it appears. The
  // property is treated as position-independent (keyed by state only); the handful of
  // context-dependent blocks never have a neighbour-varying TOP raise, so caching the
  // first-seen value is safe in practice. Static so the memo survives across selects;
  // only ever touched on the client thread, ConcurrentHashMap purely for safety.
  private static final Map<BlockState, Double> OUTLINE_TOP_REL = new ConcurrentHashMap<>();

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

  // Hole spans for the last select: drop sub-spans with no reached surface below
  // (void, or unreached ground). Marked by a through-walls beam at the rim.
  // Replaced wholesale each select.
  private List<HoleSpan> holes = List.of();

  // One-shot geometry dump for /mobwalk dump: when set, the next select() logs
  // reached/merged/occluders/skirts/holes then clears the flag. Armed by
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
   * read (for render-taller-than-collide blocks; see {@code visibleTop} /
   * {@code exposeBox}) is done. Off, every block's outline top collapses to its
   * collision top, so nothing raises and the neighbour split never fires
   * ({@code visualTopY == collisionTopY} on every rect). It is a per-block cost paid only
   * when the Appearance render toggle wants it, so flipping the toggle re-runs
   * {@code select} (see {@code CollisionSurfaceOverlay}).
   */
  public void select(Level level, BlockPos start, int radius, EntityProfile profile,
      boolean computeVisualTop, boolean swimmableFluids) {
    boolean dump = debugDumpOnce;
    debugReachedPreMerge = List.of();
    LazyFlood lazy = new LazyFlood(level, start, radius, profile, computeVisualTop, swimmableFluids);
    result = lazy.run();
    if (dump) {
      debugReachedPreMerge = lazy.preMergeReached();
    }
    occluders = computeOccluders(level, result, profile, swimmableFluids);
    List<SkirtSpan> dropEdges = computeDownSkirts(result, occluders, false);
    // Visual-keyed down-skirts when any rect draws above its collision top;
    // otherwise dropEdges already match the render heights.
    boolean raisedVisual = false;
    if (computeVisualTop) {
      for (StandableRect r : result) {
        if (Math.abs(r.visualTopY() - r.collisionTopY()) > EPS) {
          raisedVisual = true;
          break;
        }
      }
    }
    downSkirts = raisedVisual
      ? computeDownSkirts(result, occluders, true)
      : dropEdges;
    holes = computeHoles(level, result, dropEdges, profile, swimmableFluids);
    if (dump) {
      logFloodDebug(profile, start, radius, computeVisualTop);
      debugDumpOnce = false;
      debugReachedPreMerge = List.of();
    }
  }

  private void logFloodDebug(EntityProfile profile, BlockPos start, int radius,
      boolean computeVisualTop) {
    MobWalk.LOGGER.info(
      "[flood-debug] profile={} W={} H={} reach={} seed={} radius={} visualTop={}",
      profile.name(), profile.width(), profile.height(), profile.reach(),
      start, radius, computeVisualTop);
    logFloodDebugRects("reached", debugReachedPreMerge);
    logFloodDebugRects("merged", result);
    MobWalk.LOGGER.info("[flood-debug] occluders={}", occluders.size());
    for (SkirtSpan s : occluders) {
      MobWalk.LOGGER.info(
        "[flood-debug]   occ alongX={} side={} line={} [{},{}] baseY={} visualBaseY={} maxExtent={}",
        s.alongX(), s.maxSide(), s.line(), s.lo(), s.hi(),
        s.baseY(), s.visualBaseY(), s.maxExtent());
    }
    MobWalk.LOGGER.info("[flood-debug] downskirts={}", downSkirts.size());
    for (SkirtSpan s : downSkirts) {
      MobWalk.LOGGER.info(
        "[flood-debug]   drop alongX={} maxSide={} line={} [{},{}] baseY={} visualBaseY={} maxExtent={}",
        s.alongX(), s.maxSide(), s.line(), s.lo(), s.hi(),
        s.baseY(), s.visualBaseY(), s.maxExtent());
    }
    MobWalk.LOGGER.info("[flood-debug] holes={}", holes.size());
    for (HoleSpan s : holes) {
      MobWalk.LOGGER.info(
        "[flood-debug]   hole alongX={} maxSide={} line={} [{},{}] baseY={} visualBaseY={} fall={}",
        s.alongX(), s.maxSide(), s.line(), s.lo(), s.hi(),
        s.baseY(), s.visualBaseY(), s.fallDistance());
    }
  }

  private static void logFloodDebugRects(String label, List<StandableRect> rects) {
    MobWalk.LOGGER.info("[flood-debug] {}={}", label, rects.size());
    for (StandableRect r : rects) {
      MobWalk.LOGGER.info(
        "[flood-debug]   {} [{},{}]x[{},{}] collisionTopY={} visualTopY={} depth={} frontier={}",
        label, r.minX(), r.maxX(), r.minZ(), r.maxZ(),
        r.collisionTopY(), r.visualTopY(), r.depth(), r.frontier());
    }
  }

  public void clear() {
    result = List.of();
    occluders = List.of();
    downSkirts = List.of();
    holes = List.of();
    debugDumpOnce = false;
    debugReachedPreMerge = List.of();
  }

  /** Immutable snapshot of the reached surfaces (colored at draw). */
  public List<StandableRect> allRects() {
    return result;
  }

  /** Immutable snapshot of the upward (occluder) skirt spans for the reached set. */
  public List<SkirtSpan> allOccluders() {
    return occluders;
  }

  /** Immutable snapshot of the downward drop-skirt spans for the reached set. */
  public List<SkirtSpan> allDownSkirts() {
    return downSkirts;
  }

  /** Immutable snapshot of the hole spans (through-walls beam markers) for the reached set. */
  public List<HoleSpan> allHoles() {
    return holes;
  }

  /**
   * Initial BFS wave from non-emitted {@link OriginProbe}s: keep candidates that
   * are footprint-adjacent to a probe within {@code reach} of that probe's
   * height; assign depth 0 when {@code fromSeedBlock}, else 1; keep the minimum
   * depth on duplicates; drop depths above {@code depthLimit}. Returns depth-0
   * entries before depth-1 so a FIFO queue preserves shortest-path order.
   * Package-private for unit tests (synthetic probes/candidates, no world).
   */
  static List<SeedWaveEntry> assignOriginWave(List<OriginProbe> probes,
      List<OriginCandidate> candidates, double reach, int depthLimit) {
    if (probes.isEmpty() || candidates.isEmpty() || depthLimit < 0) {
      return List.of();
    }
    Map<CellSurface, Integer> best = new HashMap<>();
    for (OriginCandidate c : candidates) {
      StandableRect r = c.rect();
      boolean adjacent = false;
      for (OriginProbe p : probes) {
        if (Math.abs(r.collisionTopY() - p.collisionTopY()) > reach + EPS) {
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
    return RectMath.footprintAdjacent(
      new StandableRect(p.minX(), p.minZ(), p.maxX(), p.maxZ(), p.collisionTopY()),
      r);
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
    // Draw-only raise (Milestone 6): if this box IS the source block's topmost
    // collision surface and the block renders taller than it collides (soul sand,
    // mud), expose the visible/outline top so the marker is drawn on the face you
    // see rather than buried. Gating on "topmost collision surface" leaves stair
    // treads / bottom slabs / fence tops untouched; everything else keeps
    // visualTopY == collisionTopY. Nothing but rendering reads visualTopY.
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
          // Non-occluding support surfaces (fluid swim plates) supply a standable
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
      emitWithNeighborVisualRaise(exposed, collisionTopY, visualTopY, raiseCores, raiseOutlines, out);
    }
  }

  // Split an exposed standable rect so the parts that sit over a raised-outline
  // neighbour's undilated footprint draw at that neighbour's outline top (paint on
  // the cube), while the rest keep {@code visualTopY}. {@code collisionTopY} is
  // unchanged on every piece. When several neighbours cover a region, the highest
  // outline wins (claimed high→low so lower cores do not re-cover).
  private static void emitWithNeighborVisualRaise(RectMath.Rect exposed, double collisionTopY, double visualTopY,
      List<RectMath.Rect> raiseCores, List<Double> raiseOutlines, List<StandableRect> out) {
    if (raiseCores.isEmpty()) {
      out.add(new StandableRect(exposed.minX(), exposed.minZ(), exposed.maxX(), exposed.maxZ(),
        collisionTopY, visualTopY));
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
        collisionTopY, visualTopY));
      return;
    }
    for (RectMath.Rect remnant : RectMath.subtractRects(exposed, coresHere)) {
      out.add(new StandableRect(remnant.minX(), remnant.minZ(), remnant.maxX(), remnant.maxZ(),
        collisionTopY, visualTopY));
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
          collisionTopY, outline));
      }
      claimed.add(inter);
    }
  }

  // The source block's visible top (world Y), used to raise a standable surface to
  // the face you actually see for render-taller-than-collide blocks (soul sand, mud,
  // cactus, honey, and any modded/future block with the same property). No heuristic:
  // EVERY block state is checked, but the outline shape (getShape) is read at most
  // once per distinct BlockState and memoized in OUTLINE_TOP_REL, so the per-block
  // cost is a map lookup. Returns the collision top when the state has no separate
  // outline (NaN memo). The exposeBox raise rule then decides whether to actually
  // lift (only its block's topmost collision surface, and only when the outline is
  // strictly higher), so a fence (outline 1.0 < collision 1.5) is returned here but
  // not raised there. Gated on computeVisualTop (the Appearance render toggle): off,
  // it returns the collision top without the outline read, so no rect raises and the
  // neighbour split never fires.
  private static double visibleTop(Level level, BlockPos pos, BlockState state,
      double blockCollisionTop, boolean computeVisualTop) {
    if (!computeVisualTop) {
      return blockCollisionTop;
    }
    Double outlineRel = OUTLINE_TOP_REL.get(state);
    if (outlineRel == null) {
      VoxelShape outline = state.getShape(level, pos, CollisionContext.empty());
      outlineRel = outline.isEmpty() ? Double.NaN : outline.max(Direction.Axis.Y);
      OUTLINE_TOP_REL.put(state, outlineRel);
    }
    if (Double.isNaN(outlineRel)) {
      return blockCollisionTop;
    }
    return pos.getY() + outlineRel;
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

  // Grouping key for the occluder-span merge: orientation + side + line + base
  // height, each double quantized to 1/1024 so collinear spans on the same edge at
  // one height hash together (opposite sides at one coordinate stay distinct).
  private record SpanGroupKey(boolean alongX, boolean maxSide, long line, long baseY) {
  }

  // True iff box is an occluder/wall for a surface at height T given the entity
  // headroom: it participates in volume occlusion ({@code occludes}), its top rises
  // strictly above T, AND its base sits at or below the top of the standing column
  // T+height. Non-occluding support surfaces (fluid swim plates) supply a standable
  // top and no volume, so they never mark up-skirts — same rule as exposeBox.
  // height == 0 (Part A / Point) is the pure wall test (a box rising above T whose
  // base is at or below T); height > 0 also admits ceilings/overhangs hanging within
  // the standing column (Part B headroom). The {@code <=} on the lower bound (vs the
  // strict {@code <} the exposeBox cut uses) is deliberate: occluder spans are only
  // emitted where a dilated occluder ABUTS a surface edge (occluderSpansForRect),
  // and that abutment gate — not the predicate — rejects the boundary/own-floor
  // cases, while keeping Point's at-floor walls (yMin == T) marked.
  static boolean wallOccluder(WorldBox b, double collisionTopY, double height) {
    return b.occludes()
      && b.yMax() > collisionTopY + EPS
      && b.yMin() <= collisionTopY + height + EPS;
  }

  // Append the upward (occluder) skirt spans for one surface rect: for every
  // candidate box that is a {@link #wallOccluder} of this surface, dilate its
  // footprint by halfW and, where the dilated footprint ABUTS one of the rect's
  // four edges (sharing the edge line with positive overlap along it), emit a span
  // over the overlap — the wall/ceiling face sits at the dilated (set-back) edge,
  // not the real block face. Pure: no world access (candidates are pre-gathered).
  static void occluderSpansForRect(StandableRect r, List<WorldBox> candidates,
      double halfW, double height, List<SkirtSpan> out) {
    double collisionTopY = r.collisionTopY();
    double visualTopY = r.visualTopY();
    // The occluder skirt inherits its surface's flood-depth and frontier flag so
    // draw shares the surface's color / cutoff band (see StandableRect).
    int depth = r.depth();
    boolean frontier = r.frontier();
    for (WorldBox b : candidates) {
      if (!wallOccluder(b, collisionTopY, height)) {
        continue;
      }
      double oMinX = b.minX() - halfW;
      double oMinZ = b.minZ() - halfW;
      double oMaxX = b.maxX() + halfW;
      double oMaxZ = b.maxZ() + halfW;
      double top = b.yMax();

      double zLo = Math.max(oMinZ, r.minZ());
      double zHi = Math.min(oMaxZ, r.maxZ());
      if (zHi - zLo > EPS) {
        if (Math.abs(oMinX - r.maxX()) < EPS) {
          out.add(new SkirtSpan(false, true, r.maxX(), zLo, zHi, collisionTopY, visualTopY,
            SkirtSpan.Direction.UP, top - visualTopY, depth, frontier));
        }
        if (Math.abs(oMaxX - r.minX()) < EPS) {
          out.add(new SkirtSpan(false, false, r.minX(), zLo, zHi, collisionTopY, visualTopY,
            SkirtSpan.Direction.UP, top - visualTopY, depth, frontier));
        }
      }
      double xLo = Math.max(oMinX, r.minX());
      double xHi = Math.min(oMaxX, r.maxX());
      if (xHi - xLo > EPS) {
        if (Math.abs(oMinZ - r.maxZ()) < EPS) {
          out.add(new SkirtSpan(true, true, r.maxZ(), xLo, xHi, collisionTopY, visualTopY,
            SkirtSpan.Direction.UP, top - visualTopY, depth, frontier));
        }
        if (Math.abs(oMaxZ - r.minZ()) < EPS) {
          out.add(new SkirtSpan(true, false, r.minZ(), xLo, xHi, collisionTopY, visualTopY,
            SkirtSpan.Direction.UP, top - visualTopY, depth, frontier));
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
        Math.round(s.line() * 1024.0), Math.round(s.baseY() * 1024.0));
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
    }
    List<SkirtSpan> out = new ArrayList<>();
    for (List<SkirtSpan> group : groups.values()) {
      group.sort(Comparator.comparingDouble(SkirtSpan::lo));
      SkirtSpan head = group.get(0);
      double lo = head.lo();
      double hi = head.hi();
      // Absolute wall stop (former collisionTopY); coalesce takes the max.
      double stop = head.visualBaseY() + head.maxExtent();
      // baseY is fixed per group (grouped on it); the visible base can differ
      // when a raised block abuts a flush one, so take the max like stop.
      double visualBase = head.visualBaseY();
      // Coalesced spans can come from different surfaces (same edge line/height,
      // different depth); take the min so the merged marker reads as the nearest
      // surface's band (mirrors the max-stop handling above). Frontier only when
      // every piece is frontier — an inner piece keeps the span drawable.
      int spanDepth = head.depth();
      boolean spanFrontier = head.frontier();
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
            SkirtSpan.Direction.UP, stop - visualBase, spanDepth, spanFrontier));
          lo = s.lo();
          hi = s.hi();
          stop = s.visualBaseY() + s.maxExtent();
          visualBase = s.visualBaseY();
          spanDepth = s.depth();
          spanFrontier = s.frontier();
        }
      }
      out.add(new SkirtSpan(head.alongX(), head.maxSide(),
        head.line(), lo, hi, head.baseY(), visualBase,
        SkirtSpan.Direction.UP, stop - visualBase, spanDepth, spanFrontier));
    }
    return out;
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

  // Compute the downward drop-skirt spans of the whole reached set, once per
  // select. For each merged rect edge: the edge minus the parts covered by an
  // equal-height neighbour abutting across it (a merge seam, not a drop) minus the
  // occluder (wall/ceiling) sub-spans on that edge (they get an upward skirt), the
  // leftover being the genuine drop sub-spans. This replaces the old per-frame
  // render-side scan (openSpans/upIntervalsOnEdge, O(n^2) every frame) with one
  // compute-side pass; the result must be pixel-identical. Package-private for unit
  // tests (synthetic rects, no world).
  // visual=false keys the seam/occluder-coverage tests on collisionTopY — the
  // genuine collision drop edges, which are the hole-classifier substrate. visual=true
  // keys them on visualTopY (the render height), so a visible step between two rects at
  // the same collisionTopY but different visualTopY (a path lip drawn on a soul-sand
  // cube top) gets its skirt, while abutting rects at the same visualTopY do not. Two
  // passes over one merged set: skirts are a rendering pass, holes a geometry pass (see
  // docs/geometry.md "Visible-face top vs collision top").
  static List<SkirtSpan> computeDownSkirts(List<StandableRect> rects,
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

  // Append the drop sub-spans of one rect edge (see computeDownSkirts). alongX: the
  // edge runs along X at a fixed Z; maxSide: the +axis edge (line = the rect's max
  // coordinate on the perpendicular axis). Coverage from equal-or-higher neighbours
  // and from occluder spans on this edge is subtracted; abutting *lower* neighbours
  // set maxExtent on leftover intervals (rimKey − neighbourKey) so the curtain stops
  // at that surface. Open leftovers stay UNLIMITED.
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
          SkirtSpan.Direction.DOWN, runExtent, r.depth(), r.frontier()));
      }
      runLo = a;
      runHi = b;
      runExtent = maxExtent;
    }
    if (!Double.isNaN(runLo)) {
      out.add(new SkirtSpan(alongX, maxSide, line, runLo, runHi, r.collisionTopY(), r.visualTopY(),
        SkirtSpan.Direction.DOWN, runExtent, r.depth(), r.frontier()));
    }
  }

  private static boolean sameExtent(double a, double b) {
    if (Double.isInfinite(a) || Double.isInfinite(b)) {
      return a == b;
    }
    return Math.abs(a - b) <= EPS;
  }

  // Classify each drop span and return the hole sub-spans (through-walls beam
  // candidates). For each drop span: (1) check if a reached surface exists below
  // across the fall column, (2) if yes, scan the world between collisionTopY and landY for
  // intermediate standable surfaces (ledges) via exposeBox — if any cross the
  // column, the entity gets trapped on the ledge -> HOLE. Because one edge can
  // span several verdicts, the span is SUBDIVIDED at reached-rect boundaries into
  // homogeneous sub-spans. Runs once per select (not per frame).
  private List<HoleSpan> computeHoles(Level level, List<StandableRect> rects,
      List<SkirtSpan> drops, EntityProfile profile, boolean swimmableFluids) {
    if (drops.isEmpty()) {
      return List.of();
    }
    double halfW = profile.width() / 2.0;
    double height = profile.height();
    List<HoleSpan> out = new ArrayList<>();
    List<StandableRect> ledges = new ArrayList<>();
    for (SkirtSpan sp : drops) {
      if (sp.frontier()) {
        continue;
      }
      FallColumn fall = FallColumn.of(sp);
      ledges.clear();
      gatherLedges(level, fall, sp.baseY(), rects, halfW, height, ledges, swimmableFluids,
        profile.reach());
      holeSubSpans(sp, rects, ledges, out);
    }
    return out;
  }

  // Pure: subdivide one drop span into homogeneous sub-spans (at reached-rect
  // boundaries), classify each via classifyDrop (with ledge check), and append the
  // contiguous HOLE pieces (coalesced) as HoleSpans. A single edge can span reached
  // and unreached ground, so classifying the whole edge at once mislabels it.
  // Package-private for unit tests (synthetic reached rects / ledges, no world).
  static void holeSubSpans(SkirtSpan sp, List<StandableRect> reached,
      List<StandableRect> ledges, List<HoleSpan> out) {
    double collisionTopY = sp.baseY();
    FallColumn fall = FallColumn.of(sp);
    double[] cuts = spanBreakpoints(sp, reached);
    double holeLo = Double.NaN;
    double holeHi = 0.0;
    double holeFall = 0.0;
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
        holeFall = c.fallDistance();
      } else if (a <= holeHi + EPS) {
        holeHi = b;
        holeFall = Math.max(holeFall, c.fallDistance());
      } else {
        out.add(new HoleSpan(sp.alongX(), sp.maxSide(), sp.line(), holeLo, holeHi, collisionTopY, holeFall, sp.visualBaseY()));
        holeLo = a;
        holeHi = b;
        holeFall = c.fallDistance();
      }
    }
    if (!Double.isNaN(holeLo)) {
      out.add(new HoleSpan(sp.alongX(), sp.maxSide(), sp.line(), holeLo, holeHi, collisionTopY, holeFall, sp.visualBaseY()));
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

  // Level-backed ColumnBoxes producer: the collision boxes of block (x,y,z) as
  // absolute WorldBoxes carrying the block's collision/outline tops (the outline
  // read is paid only when computeVisualTop is on), plus an optional non-occluding
  // fluid swim plate (FluidKind on the plate only). Shared by the flood and the
  // ledge gather so there is one world-read implementation behind WorldSurfaceIndex.
  private static ColumnBoxes levelColumnBoxes(Level level, boolean computeVisualTop,
      boolean swimmableFluids, double reach) {
    BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();
    return (x, y, z) -> {
      scan.set(x, y, z);
      BlockState state = level.getBlockState(scan);
      FluidState fluidState = state.getFluidState();
      FluidKind kind = fluidKind(fluidState, swimmableFluids);
      double fluidHeight = fluidState.isEmpty() ? 0.0 : fluidState.getHeight(level, scan);
      OptionalDouble plate = plateHeight(kind, fluidHeight, reach);

      VoxelShape shape = state.getCollisionShape(level, scan, CollisionContext.empty());
      List<WorldBox> boxes = new ArrayList<>();
      if (plate.isPresent()) {
        double top = y + plate.getAsDouble();
        boxes.add(new WorldBox(x, y, z,
          x, z, x + 1, z + 1,
          y, top, top, top, kind, false));
      }
      if (shape.isEmpty()) {
        return boxes;
      }
      double blockCollisionTop = y + shape.max(Direction.Axis.Y);
      double blockOutlineTop = visibleTop(level, scan, state, blockCollisionTop, computeVisualTop);
      for (AABB box : shape.toAabbs()) {
        boxes.add(new WorldBox(x, y, z,
          x + box.minX, z + box.minZ, x + box.maxX, z + box.maxZ,
          y + box.minY, y + box.maxY,
          blockCollisionTop, blockOutlineTop, FluidKind.NONE, true));
      }
      return boxes;
    };
  }

  /**
   * Whether a cell emits a swim plate, and at what height above the block floor.
   * Enabled fluid always emits: at {@code getHeight} when above
   * {@link #FLUID_JUMP_THRESHOLD}, otherwise at {@code 0} (coplanar with the solid
   * underfoot). Seating at the effective standing height is Step 3 ({@code reach}
   * is reserved for that seating).
   */
  static OptionalDouble plateHeight(FluidKind kind, double fluidHeight, double reach) {
    if (kind == FluidKind.NONE) {
      return OptionalDouble.empty();
    }
    // reach reserved for Step 3 effective-plane seating
    if (fluidHeight <= FLUID_JUMP_THRESHOLD + EPS) {
      return OptionalDouble.of(0.0);
    }
    return OptionalDouble.of(fluidHeight);
  }

  private static FluidKind fluidKind(FluidState fluid, boolean swimmableFluids) {
    if (!swimmableFluids || fluid.isEmpty()) {
      return FluidKind.NONE;
    }
    if (fluid.is(FluidTags.WATER)) {
      return FluidKind.WATER;
    }
    if (fluid.is(FluidTags.LAVA)) {
      return FluidKind.LAVA;
    }
    return FluidKind.NONE;
  }

  // Scan the world for standable surfaces (via exposeBox) between landY and collisionTopY
  // that cross the fall column — intermediate ledges that would trap the entity. Only
  // called when a reached floor exists below (landY is known). The occluder shell is
  // supplied by WorldSurfaceIndex.tops (the same primitive the flood uses), so the
  // ledge gather cannot re-expose a fragment the flood buried.
  private static void gatherLedges(Level level, FallColumn fall, double collisionTopY,
      List<StandableRect> reached, double halfW, double height, List<StandableRect> out,
      boolean swimmableFluids, double reach) {
    gatherLedgesFrom(levelColumnBoxes(level, false, swimmableFluids, reach), fall, collisionTopY,
      reached, halfW, height, out);
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
    WorldSurfaceIndex surfaces = new WorldSurfaceIndex(source, halfW, height, bandLo, bandHi);
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

  // World-reading wrapper (client thread): for each reached surface, gather the
  // collision boxes near its dilated edges (and, with headroom, above its interior)
  // and classify the upward (occluder) skirt spans via the pure
  // {@link #occluderSpansForRect}. Runs once per stick action, not per frame, so the
  // small per-rect window scan is cheap. height comes from the profile: 0 (Point)
  // marks only walls (boxes rising above T whose base is at/below T); height > 0
  // also marks overhangs/ceilings within the standing column (T, T+height], the same
  // occluders exposeBox tests, so the skirts visualize the headroom being applied.
  // Reads the world only through ColumnBoxes (same port as the flood / ledge gather),
  // so fluid plates emitted by levelColumnBoxes cannot mark false up-skirts.
  private List<SkirtSpan> computeOccluders(Level level, List<StandableRect> rects,
      EntityProfile profile, boolean swimmableFluids) {
    return computeOccludersFrom(
      levelColumnBoxes(level, false, swimmableFluids, profile.reach()),
      level.getMinY(), level.getMaxY(), rects, profile);
  }

  // Pure kernel of computeOccluders: reads the world only through the ColumnBoxes
  // port. Keeps this pass's own XZ window and per-rect Y window (clamped to
  // worldMinY/worldMaxY) — not WorldSurfaceIndex / occluderColumns, whose flood
  // band and undilated-box window would silently drop occluders here.
  static List<SkirtSpan> computeOccludersFrom(ColumnBoxes source, int worldMinY, int worldMaxY,
      List<StandableRect> rects, EntityProfile profile) {
    if (rects.isEmpty()) {
      return List.of();
    }
    double halfW = profile.width() / 2.0;
    double height = profile.height();
    List<SkirtSpan> out = new ArrayList<>();
    List<WorldBox> candidates = new ArrayList<>();
    for (StandableRect r : rects) {
      double collisionTopY = r.collisionTopY();
      int xLo = (int) Math.floor(r.minX() - halfW) - 1;
      int xHi = (int) Math.ceil(r.maxX() + halfW);
      int zLo = (int) Math.floor(r.minZ() - halfW) - 1;
      int zHi = (int) Math.ceil(r.maxZ() + halfW);
      int yLo = Math.max((int) Math.floor(collisionTopY) - 1, worldMinY);
      int yHi = Math.min((int) Math.floor(collisionTopY + height) + 1, worldMaxY);
      candidates.clear();
      for (int x = xLo; x <= xHi; x++) {
        for (int z = zLo; z <= zHi; z++) {
          for (int y = yLo; y <= yHi; y++) {
            candidates.addAll(source.at(x, y, z));
          }
        }
      }
      occluderSpansForRect(r, candidates, halfW, height, out);
    }
    return mergeOccluderSpans(out);
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
   * {@link RectMath#footprintAdjacent}, and within {@code reach}.
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
        boolean computeVisualTop, boolean swimmableFluids) {
      BlockPos origin = start.immutable();
      this.ox = origin.getX();
      this.oy = origin.getY();
      this.oz = origin.getZ();
      this.depthLimit = depthLimit;
      this.halfW = profile.width() / 2.0;
      double height = profile.height();
      this.reach = profile.reach();
      this.neighbour = (int) Math.floor(profile.width()) + 1;
      int bandLo = Math.max(oy - depthLimit - 1, level.getMinY());
      int bandHi = Math.min(oy + depthLimit + 1, level.getMaxY());
      this.surfaces = new WorldSurfaceIndex(
        levelColumnBoxes(level, computeVisualTop, swimmableFluids, reach), halfW, height, bandLo,
        bandHi);
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
      List<SeedWaveEntry> initial = assignOriginWave(probes, candidates, reach, depthLimit);
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
            // Only tops within a single step of h can connect, so scan
            // just that height window of the neighbour column.
            for (CellSurface t : collect(cx, cz, h - reach, h + reach)) {
              if (hopCount.containsKey(t)) {
                continue;
              }
              if (RectMath.footprintAdjacent(s.rect(), t.rect())) {
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
          box.yMax()));
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
