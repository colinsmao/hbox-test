package dev.kelianmao.mobwalk.client.surface;

import java.util.ArrayList;
import java.util.BitSet;
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
 * adjacency).</b> A flood works in three phases:
 * <ol>
 * <li><b>Dilated arrangement</b> ({@link #exposeBox}): gather every collision box
 *     in a window of half-extent {@code radius} blocks (plus a {@code floor(W)+1}
 *     occluder margin so every box that can trim an edge candidate is captured —
 *     candidate and occluder each grow by {@code W/2}, so two cells up to
 *     {@code floor(W)+1} apart still interact — the same reach
 *     {@link Bfs} uses for neighbour search), grow each box's
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
 * ({@code select}/{@code advance}/{@code clear}); the render thread reads only the
 * immutable {@link #snapshot()} the overlay publishes into a {@code volatile} field.
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
  // The flood (Bfs) owns this index; HoleBeams.gatherLedgesFrom also uses it
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

  // Everything the last flood produced (draw set + the edge passes derived from
  // it), as one immutable object; see SelectionSnapshot for what each list holds.
  // Replaced wholesale each flood — never mutated in place — so the overlay can
  // publish it to the render thread with a single reference write.
  private SelectionSnapshot snapshot = SelectionSnapshot.EMPTY;

  // The flood in flight, or null when idle. It owns every stage of the compute, so
  // replacing or nulling this one reference cancels the flood outright — there is
  // no second field a reset could leave behind still running.
  private FloodJob flood;
  // What that flood (or the last completed one) was asked for. Retained past the
  // job so /mobwalk dump can report the parameters the flood actually ran with.
  private FloodParams params;

  // Retained from the last completed flood so /mobwalk dump is a pure read of
  // persisted state: the pre-merge reached tops, plus the collision-rim occluders
  // whenever the paint rim diverges from them (the snapshot carries the paint rim).
  private List<StandableRect> lastReached = List.of();
  private List<SkirtSpan> lastCollisionOccluders;
  // Compute time and frame count of that flood, so a dump reports what a flood of
  // this size costs on this machine — the measurement the budget is sized against.
  private long elapsedNanos;
  private int frames;
  // That total split per phase, which is what says whether the expansion or one of
  // the passes dominates on the terrain being measured.
  private String lastPhaseTimings = "";

  // Every input of one flood, captured when it is armed.
  private record FloodParams(Level level, BlockPos start, int radius, EntityProfile profile,
      boolean computeVisualTop, boolean swimmableFluids, double fluidEscape) {
  }

  /**
   * Arm a flood of the standable surfaces reachable from the click origin at
   * {@code start}, within BFS hop-count {@code radius} of that origin, for the
   * entity's width/reach, across footprint-adjacent height-gated steps. The origin
   * is the clicked block's raw dilated collision footprints (non-emitted); only
   * exposed tops enter the painted set (depth 0 on the seed block's own exposed
   * tops, depth 1 on other tops adjacent to the origin).
   *
   * <p><b>Arming returns immediately</b>: it builds a {@link FloodJob} around the
   * output-sensitive {@link Bfs} (on-demand column/row exposure), and
   * {@link #advance} works through it a step at a time — a depth ring while
   * expanding, then one finalize pass each for the occluders, down-skirts, holes
   * and hazards — replacing {@link #snapshot()} on the step that finishes the last
   * pass. The previous selection therefore stays published for the whole compute.
   * Any flood already in flight is cancelled here — latest arm wins.
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
    params = new FloodParams(level, start, radius, profile, computeVisualTop, swimmableFluids,
      fluidEscape);
    flood = new FloodJob(params);
    elapsedNanos = 0;
    frames = 0;
  }

  /**
   * Spend up to {@code budgetNanos} of wall time on the flood in flight, always
   * finishing the step in progress. Returns whether this call completed the flood
   * and so made a new {@link #snapshot()}.
   */
  public boolean advance(long budgetNanos) {
    if (flood == null) {
      return false;
    }
    frames++;
    long began = System.nanoTime();
    boolean done = flood.advance(budgetNanos);
    elapsedNanos += System.nanoTime() - began;
    if (done) {
      snapshot = flood.snapshot();
      lastReached = flood.preMergeReached();
      // Only worth keeping when the two rims diverge; otherwise the snapshot's
      // paint rim is the collision rim and the dump prints one list.
      lastCollisionOccluders = flood.raisedVisual() ? flood.collisionOccluders() : null;
      lastPhaseTimings = flood.phaseTimings();
      flood = null;
    }
    return done;
  }

  /**
   * How far the flood in flight has got, or {@code null} with nothing armed —
   * the idle signal a progress cue gates on, since the driver asks every frame.
   * The two fractions stay apart so the HUD can weigh expansion against the
   * finalize passes.
   */
  public record FloodProgress(float expansion, float passes) {
  }

  /** The flood in flight's progress, or {@code null} with nothing armed. */
  public FloodProgress progress() {
    return flood == null ? null : new FloodProgress(flood.expansion(), flood.passes());
  }

  /** Whether a flood is armed (cheaper than {@link #progress()}, which allocates). */
  public boolean isFlooding() {
    return flood != null;
  }

  /**
   * Log the last completed flood — its parameters and cost, the pre-merge reached
   * tops, the merged rects, and every derived span — for {@code /mobwalk dump}.
   * Reads persisted state, so it reports the selection as it was computed.
   */
  public void dumpLastSelection() {
    if (params == null) {
      return;
    }
    logFloodDebug();
  }

  // Nanoseconds as milliseconds to two decimals, the unit the dump reports in.
  private static double millis(long nanos) {
    return Math.round(nanos / 10_000.0) / 100.0;
  }

  private void logFloodDebug() {
    EntityProfile profile = params.profile();
    MobWalk.LOGGER.info(
      "[flood-debug] profile={} W={} H={} reach={} fluidEscape={} seed={} radius={} visualTop={}"
        + " elapsedMs={} frames={}",
      profile.name(), profile.width(), profile.height(), profile.reach(), params.fluidEscape(),
      params.start(), params.radius(), params.computeVisualTop(),
      millis(elapsedNanos), frames);
    if (!lastPhaseTimings.isEmpty()) {
      MobWalk.LOGGER.info("[flood-debug] phases {}", lastPhaseTimings);
    }
    logFloodDebugRects("reached", lastReached);
    logFloodDebugRects("merged", snapshot.rects());
    if (lastCollisionOccluders != null) {
      logFloodDebugOccluders("occluders-collision", lastCollisionOccluders);
      logFloodDebugOccluders("occluders-paint", snapshot.occluders());
    } else {
      logFloodDebugOccluders("occluders", snapshot.occluders());
    }
    MobWalk.LOGGER.info("[flood-debug] downskirts={}", snapshot.downSkirts().size());
    for (SkirtSpan s : snapshot.downSkirts()) {
      MobWalk.LOGGER.info(
        "[flood-debug]   drop alongX={} maxSide={} line={} [{},{}] baseY={} visualBaseY={} maxExtent={}",
        s.alongX(), s.maxSide(), s.line(), s.lo(), s.hi(),
        s.baseY(), s.visualBaseY(), s.maxExtent());
    }
    MobWalk.LOGGER.info("[flood-debug] holes={}", snapshot.holes().size());
    for (BeamSpan s : snapshot.holes()) {
      MobWalk.LOGGER.info(
        "[flood-debug]   hole alongX={} line={} [{},{}] visualBaseY={} hazard={}",
        s.alongX(), s.line(), s.lo(), s.hi(), s.visualBaseY(), s.hazard());
    }
    MobWalk.LOGGER.info("[flood-debug] hazards={}", snapshot.hazards().size());
    for (BeamSpan s : snapshot.hazards()) {
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

  /** Empty the selection and cancel any flood in flight, releasing its state. */
  public void clear() {
    snapshot = SelectionSnapshot.EMPTY;
    flood = null;
    params = null;
    lastReached = List.of();
    lastCollisionOccluders = null;
    lastPhaseTimings = "";
  }

  /** The last flood's output: reached surfaces plus their skirt and beam spans. */
  public SelectionSnapshot snapshot() {
    return snapshot;
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
   * Solid hazards then coplanar-rival punch (face midplane + conservative
   * corner squares; see {@link #addCoplanarRivalPunches}); fluids stay fully
   * dilated.
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
    // Coplanar rivals for solid-hazard punch (gathered here; applied per
    // post-occlusion piece so punches stay inside that piece).
    List<WorldBox> punchRivals = new ArrayList<>();
    boolean solidHazard = target.hazard().isSolidHazard();
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
          if (solidHazard
              && Math.abs(other.yMax() - collisionTopY) <= EPS
              && other.hazard() != target.hazard()) {
            punchRivals.add(other);
          }
        }
      }
    }

    for (RectMath.Rect exposed : RectMath.subtractRects(base, clipRects)) {
      if (punchRivals.isEmpty()) {
        emitWithNeighborVisualRaise(exposed, collisionTopY, visualTopY, target.hazard(),
          raiseCores, raiseOutlines, out);
        continue;
      }
      List<RectMath.Rect> punches = new ArrayList<>();
      for (WorldBox rival : punchRivals) {
        addCoplanarRivalPunches(exposed, target, rival, halfW, punches);
      }
      if (punches.isEmpty()) {
        emitWithNeighborVisualRaise(exposed, collisionTopY, visualTopY, target.hazard(),
          raiseCores, raiseOutlines, out);
        continue;
      }
      for (RectMath.Rect remnant : RectMath.subtractRects(exposed, punches)) {
        emitWithNeighborVisualRaise(remnant, collisionTopY, visualTopY, target.hazard(),
          raiseCores, raiseOutlines, out);
      }
    }
  }

  // Rect-native coplanar attribution vs one rival:
  // - Face: equidistant midplane in E ∩ B+ ∩ faceBand (box-sep gaps).
  // - Corners: conservative squares from rival corners (orthant gaps; see
  //   punchCornerSquare). Face magmas must punch these too so they cannot refill.
  // - Else: E ∩ undilated(B). Never an infinite half-space.
  private static final double CORNER_SQUARE_FACTOR = Math.sqrt(2.0) - 1.0;

  private static void addCoplanarRivalPunches(RectMath.Rect exposed, WorldBox target,
      WorldBox other, double halfW, List<RectMath.Rect> punches) {
    RectMath.Rect otherDilated = new RectMath.Rect(
      other.minX() - halfW, other.minZ() - halfW,
      other.maxX() + halfW, other.maxZ() + halfW);
    boolean sepWest = other.maxX() <= target.minX() + EPS;
    boolean sepEast = other.minX() >= target.maxX() - EPS;
    boolean sepSouth = other.maxZ() <= target.minZ() + EPS;
    boolean sepNorth = other.minZ() >= target.maxZ() - EPS;
    boolean sepX = sepWest || sepEast;
    boolean sepZ = sepSouth || sepNorth;
    // Box-separation gaps for face classification (not the same as corner-orthant gaps).
    double gapX = 0.0;
    double gapZ = 0.0;
    if (sepEast) {
      gapX = other.minX() - target.maxX();
    } else if (sepWest) {
      gapX = target.minX() - other.maxX();
    }
    if (sepNorth) {
      gapZ = other.minZ() - target.maxZ();
    } else if (sepSouth) {
      gapZ = target.minZ() - other.maxZ();
    }
    double ovLoZ = Math.max(other.minZ(), target.minZ());
    double ovHiZ = Math.min(other.maxZ(), target.maxZ());
    double ovLoX = Math.max(other.minX(), target.minX());
    double ovHiX = Math.min(other.maxX(), target.maxX());
    boolean zOverlap = ovHiZ - ovLoZ > EPS;
    boolean xOverlap = ovHiX - ovLoX > EPS;
    // Classic face, or flush on the other axis with a positive gap on this one.
    boolean faceEW = sepX
      && ((!sepZ && zOverlap) || (sepZ && gapZ <= EPS && gapX > EPS));
    boolean faceNS = sepZ
      && ((!sepX && xOverlap) || (sepX && gapX <= EPS && gapZ > EPS));

    int punchesBefore = punches.size();
    if (faceEW) {
      // Flush-Z uses rival undilated Z; classic face uses overlap ± halfW.
      double bandLoZ = zOverlap ? ovLoZ - halfW : other.minZ();
      double bandHiZ = zOverlap ? ovHiZ + halfW : other.maxZ();
      RectMath.Rect band = new RectMath.Rect(
        Math.min(exposed.minX(), otherDilated.minX()), bandLoZ,
        Math.max(exposed.maxX(), otherDilated.maxX()), bandHiZ);
      RectMath.Rect subspan = intersect3(exposed, otherDilated, band);
      if (subspan != null) {
        double mid = sepWest
          ? (other.maxX() + target.minX()) / 2.0
          : (target.maxX() + other.minX()) / 2.0;
        punchRivalSideOfMidX(subspan, sepWest, mid, punches);
      }
    }
    if (faceNS) {
      double bandLoX = xOverlap ? ovLoX - halfW : other.minX();
      double bandHiX = xOverlap ? ovHiX + halfW : other.maxX();
      RectMath.Rect band = new RectMath.Rect(
        bandLoX, Math.min(exposed.minZ(), otherDilated.minZ()),
        bandHiX, Math.max(exposed.maxZ(), otherDilated.maxZ()));
      RectMath.Rect subspan = intersect3(exposed, otherDilated, band);
      if (subspan != null) {
        double mid = sepSouth
          ? (other.maxZ() + target.minZ()) / 2.0
          : (target.maxZ() + other.minZ()) / 2.0;
        punchRivalSideOfMidZ(subspan, sepSouth, mid, punches);
      }
    }
    if (sepX || sepZ) {
      addConservativeCornerSquares(exposed, target, other, punches);
    }

    if (punches.size() == punchesBefore) {
      RectMath.Rect aabb = RectMath.intersectRect(exposed, new RectMath.Rect(
        other.minX(), other.minZ(), other.maxX(), other.maxZ()));
      if (aabb != null) {
        punches.add(aabb);
      }
    }
  }

  /** Punch the rival's half of a vertical midplane (split on X) inside subspan. */
  private static void punchRivalSideOfMidX(RectMath.Rect subspan, boolean rivalIsWest,
      double mid, List<RectMath.Rect> punches) {
    if (rivalIsWest) {
      double hiX = Math.min(subspan.maxX(), mid);
      if (hiX - subspan.minX() > EPS) {
        punches.add(new RectMath.Rect(
          subspan.minX(), subspan.minZ(), hiX, subspan.maxZ()));
      }
    } else {
      double loX = Math.max(subspan.minX(), mid);
      if (subspan.maxX() - loX > EPS) {
        punches.add(new RectMath.Rect(
          loX, subspan.minZ(), subspan.maxX(), subspan.maxZ()));
      }
    }
  }

  /** Punch the rival's half of a horizontal midplane (split on Z) inside subspan. */
  private static void punchRivalSideOfMidZ(RectMath.Rect subspan, boolean rivalIsSouth,
      double mid, List<RectMath.Rect> punches) {
    if (rivalIsSouth) {
      double hiZ = Math.min(subspan.maxZ(), mid);
      if (hiZ - subspan.minZ() > EPS) {
        punches.add(new RectMath.Rect(
          subspan.minX(), subspan.minZ(), subspan.maxX(), hiZ));
      }
    } else {
      double loZ = Math.max(subspan.minZ(), mid);
      if (subspan.maxZ() - loZ > EPS) {
        punches.add(new RectMath.Rect(
          subspan.minX(), loZ, subspan.maxX(), subspan.maxZ()));
      }
    }
  }

  // Punch conservative squares on each rival corner whose outward orthant reaches
  // the target. g = min of strictly positive gaps; a zero gap on one axis still
  // allows a square sized by the other (flush + gap).
  private static void addConservativeCornerSquares(RectMath.Rect exposed, WorldBox target,
      WorldBox other, List<RectMath.Rect> punches) {
    punchCornerSquare(exposed, target, other.maxX(), other.maxZ(), 1, 1, punches);
    punchCornerSquare(exposed, target, other.minX(), other.maxZ(), -1, 1, punches);
    punchCornerSquare(exposed, target, other.maxX(), other.minZ(), 1, -1, punches);
    punchCornerSquare(exposed, target, other.minX(), other.minZ(), -1, -1, punches);
  }

  private static void punchCornerSquare(RectMath.Rect exposed, WorldBox target,
      double cx, double cz, int dirX, int dirZ, List<RectMath.Rect> punches) {
    double gx = dirX > 0 ? target.minX() - cx : cx - target.maxX();
    double gz = dirZ > 0 ? target.minZ() - cz : cz - target.maxZ();
    if (gx < -EPS || gz < -EPS) {
      return;
    }
    double g = Double.POSITIVE_INFINITY;
    if (gx > EPS) {
      g = Math.min(g, gx);
    }
    if (gz > EPS) {
      g = Math.min(g, gz);
    }
    if (!(g < Double.POSITIVE_INFINITY)) {
      return;
    }
    double s = g * CORNER_SQUARE_FACTOR;
    if (s <= EPS) {
      return;
    }
    double x0 = dirX > 0 ? cx : cx - s;
    double x1 = dirX > 0 ? cx + s : cx;
    double z0 = dirZ > 0 ? cz : cz - s;
    double z1 = dirZ > 0 ? cz + s : cz;
    RectMath.Rect square = RectMath.intersectRect(exposed,
      new RectMath.Rect(x0, z0, x1, z1));
    if (square != null) {
      punches.add(square);
    }
  }

  private static RectMath.Rect intersect3(RectMath.Rect a, RectMath.Rect b, RectMath.Rect c) {
    RectMath.Rect ab = RectMath.intersectRect(a, b);
    if (ab == null) {
      return null;
    }
    return RectMath.intersectRect(ab, c);
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
  // Shared by exposeBox (used by both flood paths) and Bfs.ensureOccluders
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
   * One whole flood: the {@link Bfs} expansion and then the finalize passes that
   * turn its reached set into a {@link SelectionSnapshot}. It advances a step at a
   * time — a depth ring while expanding, one whole pass afterwards — so no single
   * frame pays for all of it, and every yield lands between units of work rather
   * than inside one. That matters both ways round: the passes read the complete
   * reached set, and each pass feeds the next.
   *
   * <p><b>Both stages live behind one reference</b>, which is what makes cancelling
   * a flood total. {@link SurfaceSelection} replaces or nulls its single
   * {@code flood} field, so a re-arm or a clear can never strand a finalize that
   * would go on to publish the previous flood's selection over the current one.
   */
  private static final class FloodJob {
    // The passes in dependency order: each reads what the ones before it produced.
    // BFS repeats until the expansion completes; every other phase runs once, and
    // the two paint phases are reached only when a rect renders taller than it
    // collides.
    private enum Phase {
      BFS("bfs"),
      MERGE("merge"),
      COLLISION_OCCLUDERS("occluders-collision"),
      DROP_EDGES("downskirts-collision"),
      PAINT_OCCLUDERS("occluders-paint"),
      PAINT_DOWN_SKIRTS("downskirts-paint"),
      HOLES("holes"),
      HAZARDS("hazards"),
      DONE("");

      // How the phase is named in the /mobwalk dump timing line, matching the
      // labels the dump already prints for the spans each pass produces.
      private final String label;

      Phase(String label) {
        this.label = label;
      }
    }

    private final FloodParams params;
    // Released at the end of MERGE, the last phase that reads it: its
    // WorldSurfaceIndex is the largest thing a flood holds, so the finalize passes
    // run without it.
    private Bfs bfs;
    private Phase phase = Phase.BFS;

    private List<StandableRect> reached = List.of();
    private List<StandableRect> rects = List.of();
    private List<SkirtSpan> collisionOccluders = List.of();
    private List<SkirtSpan> dropEdges = List.of();
    private List<SkirtSpan> paintOccluders = List.of();
    private List<SkirtSpan> paintDownSkirts = List.of();
    private List<BeamSpan> holes = List.of();
    private boolean raisedVisual;
    private SelectionSnapshot snapshot = SelectionSnapshot.EMPTY;
    // Wall time each phase has taken, summed over however many steps and frames it
    // spanned, so a dump can say which pass dominates on this terrain.
    private final long[] phaseNanos = new long[Phase.values().length];

    FloodJob(FloodParams params) {
      this.params = params;
      this.bfs = new Bfs(params.level(), params.start(), params.radius(), params.profile(),
        params.computeVisualTop(), params.swimmableFluids(), params.fluidEscape());
      this.bfs.seed();
    }

    /**
     * Spend up to {@code budgetNanos} of wall time on the flood, always finishing
     * the step in progress, and return whether it is now complete. The clock is
     * read once per step, so the budget bounds how many steps a call takes rather
     * than the cost of the one running — and the smallest budget still buys a
     * whole ring or a whole pass.
     */
    boolean advance(long budgetNanos) {
      long began = System.nanoTime();
      long mark = began;
      while (true) {
        Phase running = phase;
        boolean done = step();
        // The one clock read per step serves both the budget and the per-phase
        // tally, so measuring the phases costs nothing extra.
        long now = System.nanoTime();
        phaseNanos[running.ordinal()] += now - mark;
        mark = now;
        if (done) {
          return true;
        }
        if (now - began >= budgetNanos) {
          return false;
        }
      }
    }

    // One unit of work, returning whether it completed the flood.
    private boolean step() {
      switch (phase) {
        case BFS -> {
          if (bfs.stepRing()) {
            phase = Phase.MERGE;
          }
        }
        case MERGE -> {
          rects = bfs.merged();
          reached = bfs.preMergeReached();
          // Dual rim (same as downs): collision for holes; visual for paint when
          // raised. Settling it here is what lets a flat selection skip both paint
          // phases and reuse the collision rim for paint.
          raisedVisual = params.computeVisualTop() && anyRaised(rects);
          bfs = null;
          phase = Phase.COLLISION_OCCLUDERS;
        }
        case COLLISION_OCCLUDERS -> {
          collisionOccluders = OccluderSkirts.compute(params.level(), rects, params.profile(),
            params.swimmableFluids(), false);
          phase = Phase.DROP_EDGES;
        }
        case DROP_EDGES -> {
          dropEdges = DownSkirts.compute(rects, collisionOccluders, false);
          if (raisedVisual) {
            phase = Phase.PAINT_OCCLUDERS;
          } else {
            paintOccluders = collisionOccluders;
            paintDownSkirts = dropEdges;
            phase = Phase.HOLES;
          }
        }
        case PAINT_OCCLUDERS -> {
          paintOccluders = OccluderSkirts.compute(params.level(), rects, params.profile(),
            params.swimmableFluids(), true);
          phase = Phase.PAINT_DOWN_SKIRTS;
        }
        case PAINT_DOWN_SKIRTS -> {
          paintDownSkirts = DownSkirts.compute(rects, paintOccluders, true);
          phase = Phase.HOLES;
        }
        case HOLES -> {
          holes = HoleBeams.compute(params.level(), rects, dropEdges, params.profile(),
            params.swimmableFluids());
          phase = Phase.HAZARDS;
        }
        case HAZARDS -> {
          snapshot = new SelectionSnapshot(rects, paintOccluders, paintDownSkirts, holes,
            HazardBeams.compute(rects));
          phase = Phase.DONE;
        }
        case DONE -> {
          // Idempotent: a completed flood is dropped by its caller.
        }
      }
      return phase == Phase.DONE;
    }

    private static boolean anyRaised(List<StandableRect> rects) {
      for (StandableRect r : rects) {
        if (Math.abs(r.visualTopY() - r.collisionTopY()) > EPS) {
          return true;
        }
      }
      return false;
    }

    /**
     * How much of the flood disk has been reached, as {@code 0f..1f}: the area
     * grows as the square of the ring, so the ring index squared estimates it.
     * An estimate — a flood that closes early jumps to {@code 1}, which is also
     * what the finalize passes report, having nothing left to expand.
     */
    float expansion() {
      if (phase != Phase.BFS) {
        return 1f;
      }
      int radius = params.radius();
      if (radius <= 0) {
        return 0f;
      }
      float ringFraction = Math.min(bfs.depth(), radius) / (float) radius;
      return ringFraction * ringFraction;
    }

    /**
     * How many finalize passes have run, as {@code 0f..1f}. Zero while expanding;
     * afterwards the {@link Phase} cursor, so skipped paint phases jump a slice.
     */
    float passes() {
      if (phase == Phase.BFS) {
        return 0f;
      }
      return (phase.ordinal() - Phase.MERGE.ordinal())
        / (float) (Phase.DONE.ordinal() - Phase.MERGE.ordinal());
    }

    SelectionSnapshot snapshot() {
      return snapshot;
    }

    List<StandableRect> preMergeReached() {
      return reached;
    }

    // Whether the paint rim was computed separately from the collision rim.
    boolean raisedVisual() {
      return raisedVisual;
    }

    List<SkirtSpan> collisionOccluders() {
      return collisionOccluders;
    }

    // Each phase's milliseconds as one log-ready string; a skipped paint phase
    // reads 0, which is how the dump shows that the rims coincided.
    String phaseTimings() {
      StringBuilder out = new StringBuilder();
      for (Phase p : Phase.values()) {
        if (p != Phase.DONE) {
          out.append(out.isEmpty() ? "" : " ").append(p.label).append('=')
            .append(millis(phaseNanos[p.ordinal()]));
        }
      }
      return out.toString();
    }
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
   * pre-merge), each tagged with its source cell. From an expanded surface in cell
   * {@code c}, neighbours are sought in cells within Chebyshev {@code R} of
   * {@code c} ({@code R = floor(W)+1}: a bridged/abutting pair sits
   * {@code floor(W)+1} cells apart — {@code 1} for Point/Player, {@code 2} for
   * Ravager; <b>not</b> {@code ceil(W)}, which is {@code 0} for Point and would
   * never connect adjacent floor tiles). A neighbour cell's surfaces are computed
   * on demand (with each box's occluder shell — the columns {@link #exposeBox}
   * scans — exposed first, so the spans-above test sees a complete shell) and a
   * surface joins the next ring iff it is unvisited,
   * {@link RectMath#footprintAdjacent}, and {@link ClimbRule#climbs} (the
   * collect height window {@code [h-reach, h+reach]} stays a valid superset).
   *
   * <p><b>Resumable, one depth ring at a time.</b> The BFS is a stateful object
   * whose BFS state lives in fields, so a flood can span calls: {@link #seed}
   * arms it from the click origin and each {@link #stepRing} expands exactly the
   * current depth. Call {@code k} completes ring {@code k-1}, so after {@code k}
   * steps {@code reached} holds exactly the surfaces at depth {@code <= k-1} — a
   * valid flood at that radius, which is what lets {@link FloodJob} yield between
   * rings.
   *
   * <p>Rings preserve the order a single FIFO queue produced: BFS over
   * unit-weight edges leaves a queue in nondecreasing depth, and within one depth
   * in discovery order, which is exactly {@code ring} then {@code nextRing} (the
   * origin wave likewise contributes all of depth 0 before depth 1). Order matters
   * because the greedy strip merge is order-sensitive, so keeping it keeps the
   * drawn rects.
   *
   * <p><b>It owns every piece of BFS state</b> — the index, the visited
   * map, both rings, the reached list — as plain mutable fields. None of it
   * crosses a thread boundary (unlike the published {@link SelectionSnapshot},
   * which is bundled precisely so it can), so what it needs is not
   * torn-read safety but a single lifetime: dropping the {@code Bfs} reference
   * releases the whole expansion at once, index included.
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
  static final class Bfs {
    // Every flood parameter is captured here at construction, so a flood in
    // flight is immutable with respect to settings: a profile/radius/fluid change
    // can only be answered by a fresh Bfs, never by mutating this one.
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
    // hopCount doubles as the visited set: a key is present iff visited, and its
    // value is the BFS hop-count from the click origin (0 = seed-block top). It is
    // also where merged() reads each reached node's depth back from, so there is no
    // parallel depth list to keep in lock-step.
    private final Map<CellSurface, Integer> hopCount = new HashMap<>();
    // The depth ring being expanded (every node here is at hop-count `depth`) and
    // the one being discovered (hop-count `depth + 1`).
    private List<CellSurface> ring = new ArrayList<>();
    private List<CellSurface> nextRing = new ArrayList<>();
    // Reached nodes in BFS order, the pre-merge output; grows one whole ring per
    // stepRing.
    private final List<CellSurface> reached = new ArrayList<>();
    private int depth = 0;
    private boolean done = false;

    Bfs(Level level, BlockPos start, int depthLimit, EntityProfile profile,
        boolean computeVisualTop, boolean swimmableFluids, double fluidEscape) {
      this(WorldGeometry.levelColumnBoxes(level, computeVisualTop, swimmableFluids),
        start.getX(), start.getY(), start.getZ(), depthLimit, profile, fluidEscape,
        Math.max(start.getY() - depthLimit - 1, level.getMinY()),
        Math.min(start.getY() + depthLimit + 1, level.getMaxY()));
    }

    // World-port constructor: the whole flood reads the world only through
    // `world` and only inside the block-row band [bandLo,bandHi], so a test can
    // drive the BFS over a synthetic ColumnBoxes with no Level (the seam
    // HoleBeams.gatherLedgesFrom gives the ledge gather).
    Bfs(ColumnBoxes world, int ox, int oy, int oz, int depthLimit, EntityProfile profile,
        double fluidEscape, int bandLo, int bandHi) {
      this.ox = ox;
      this.oy = oy;
      this.oz = oz;
      this.depthLimit = depthLimit;
      this.halfW = profile.width() / 2.0;
      this.reach = profile.reach();
      this.climb = new ClimbRule(reach, fluidEscape);
      this.neighbour = (int) Math.floor(profile.width()) + 1;
      this.surfaces = new WorldSurfaceIndex(
        world, halfW, profile.height(), bandLo, bandHi);
    }

    // The pre-merge reached surfaces (for /mobwalk dump), as a fresh list. Answers
    // for whatever rings have completed so far, so it is valid mid-flood too.
    List<StandableRect> preMergeReached() {
      List<StandableRect> out = new ArrayList<>(reached.size());
      for (CellSurface s : reached) {
        out.add(s.rect());
      }
      return out;
    }

    // Rings completed so far, i.e. the next depth to expand.
    int depth() {
      return depth;
    }

    /**
     * Arm the flood from the click origin: the raw pre-occlusion dilated
     * footprints of the clicked block (never painted) enter the exposed-top graph
     * at depth 0 (seed-block tops) or depth 1 (other abutting tops), filling ring
     * 0 and pre-seeding ring 1. An origin over nothing standable finishes the
     * flood outright.
     */
    void seed() {
      List<OriginProbe> probes = buildClickProbes();
      if (probes.isEmpty()) {
        done = true;
        return;
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
        done = true;
        return;
      }
      // Depth-0-before-depth-1 ring placement (and FIFO order within a ring) is
      // what makes hopCount the shortest distance.
      for (SeedWaveEntry entry : initial) {
        CellSurface s = new CellSurface(entry.rect(), entry.cx(), entry.cz());
        if (hopCount.putIfAbsent(s, entry.depth()) == null) {
          (entry.depth() == 0 ? ring : nextRing).add(s);
        }
      }
    }

    /**
     * Expand exactly the current depth ring: append its nodes to {@code reached}
     * and, below the depth limit, discover the next ring. Call {@code k} completes
     * ring {@code k-1}. Returns whether the flood is finished — including on every
     * call after that, so a driver can keep stepping harmlessly.
     */
    boolean stepRing() {
      if (done) {
        return true;
      }
      // At the depth limit: emit this ring but don't explore its neighbours.
      boolean expandRing = depth < depthLimit;
      for (CellSurface s : ring) {
        reached.add(s);
        if (expandRing) {
          expand(s);
        }
      }
      ring = nextRing;
      nextRing = new ArrayList<>();
      depth++;
      done = ring.isEmpty();
      return done;
    }

    // Discover every unvisited neighbour of one node into the next ring.
    private void expand(CellSurface s) {
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
              hopCount.put(t, depth + 1);
              nextRing.add(t);
            }
          }
        }
      }
    }

    /**
     * Merge the reached set into the drawn rects. Valid on a complete flood: the
     * frontier band is {@code depth == depthLimit}, so a partial reached set would
     * label an interior ring as the cutoff edge.
     */
    List<StandableRect> merged() {
      // Composite-priority merge: INNER surface classes then FRONTIER surface
      // classes in one priority partition (inner owns dilation overlap), so
      // the frontier ring stays a separate depth band.
      List<StandableRect> rects = new ArrayList<>(reached.size());
      int[] rawDepths = new int[reached.size()];
      for (int i = 0; i < reached.size(); i++) {
        CellSurface s = reached.get(i);
        rects.add(s.rect());
        rawDepths[i] = hopCount.get(s);
      }
      return RectMath.mergeCoplanarSplitFrontier(rects, rawDepths, depthLimit);
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
