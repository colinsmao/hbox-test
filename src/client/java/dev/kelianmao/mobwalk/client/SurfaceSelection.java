package dev.kelianmao.mobwalk.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.kelianmao.mobwalk.MobWalk;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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
 *     {@code floor(W)+1} apart still interact; this is the one reach the lazy
 *     neighbour search also uses), grow each box's
 *     footprint by the entity half-width {@code W/2} (Minkowski sum with the
 *     {@code WxW} square is just the rect grown on every side), and keep a box-top
 *     at height {@code T} over the footprint where <b>no dilated box spans
 *     immediately above it</b> ({@code minY <= T < maxY}). That single
 *     spans-above/buried test is the occlusion: it both cuts a lower top back by
 *     {@code W/2} near a taller box and supplies that taller box's own (dilated)
 *     top as the surface to stand on. Non-burying overlaps (an air gap between two
 *     tops) stay as <b>distinct levels</b> (not {@code max topY}), so multi-level
 *     is preserved. {@code W = 0} (Point) makes dilation a no-op — neighbor
 *     footprints only abut (zero overlap), so it reproduces the per-block result.
 * <li><b>Merge</b> coplanar ({@code |dTopY| < EPS}) rects into maximal rectangles.
 *     Dilated neighbor tops grow into each other, so each level is first
 *     <b>re-cut to a non-overlapping union</b> (vertical-slab sweep) — otherwise
 *     overlapping translucent quads double-blend into darker seams — then a greedy
 *     strip merge along X then Z collapses the grid back, so a flat floor becomes
 *     one rect instead of many cells (clean skirts, fewer quads).
 * <li><b>Flood</b> the merged-rect graph from the seed rect(s) by <b>geometric
 *     adjacency</b>: two rects are connected iff their footprints share an edge
 *     with positive overlap ({@link #footprintAdjacent}) and their heights are
 *     within the active {@link EntityProfile}'s {@code reach}. This one
 *     test subsumes the old same-block / own-column / 4-neighbor-column cases: a
 *     glass pane on a block connects to that block's exposed ring because their
 *     footprints abut at the hole edges, no special case needed. A dilated perch
 *     over the void floods directly because adjacency is geometric (no column).
 * </ol>
 *
 * <p>For a gap of width {@code g} flanked by support, each side grows {@code W/2},
 * leaving {@code g - W} uncovered: {@code g <= W} bridges, {@code g > W} leaves a
 * hole — "can't fall into a hole smaller than yourself". (This stage shows only
 * the <em>geometry</em>; explicit hole detection is a later milestone.)
 *
 * <p><b>Radius is a spatial budget</b> (the window half-extent in blocks), not a
 * graph hop-count: with merge an open floor is a single rect, so hop-count would
 * make the radius meaningless on open ground. Straight-line reach still matches a
 * per-block hop flood; the cutoff is a Chebyshev square rather than a taxicab
 * diamond. Connectivity gating is unchanged (a drop {@code > reach} or a
 * disconnected patch is never reached).
 *
 * <p>Not thread-safe by design. It is mutated only on the client thread
 * ({@code select}/{@code clear}); the render thread reads only the immutable
 * {@link #allRects()} snapshot the overlay publishes into a {@code volatile} field.
 */
public final class SurfaceSelection {
	// An axis-aligned XZ rectangle (world coords), the per-box footprint clip in
	// exposeBox and the mutable merge accumulator. Package-private so the pure
	// geometry ops can be unit-tested with synthetic rects (no world).
	record Rect(double minX, double minZ, double maxX, double maxZ) {
	}

	// One collision sub-box in absolute world coords: its (undilated) XZ footprint
	// plus its vertical extent. The arrangement dilates the footprint by W/2 on
	// demand; yMin/yMax drive the spans-above occlusion test. bx/by/bz are the
	// source block (the window/cube membership test runs on these, exactly like
	// the eager pass, so lazy and eager agree on which boxes are nodes vs occluders).
	// blockCollisionTop / blockOutlineTop are the SOURCE BLOCK's whole-shape tops
	// (collision vs visible/outline, world Y), carried so exposeBox can raise a
	// standable top to the visible face for render-taller-than-collide blocks (soul
	// sand, mud) without touching any walkability math (see exposeBox / StandableRect).
	// Package-private for unit tests (synthetic boxes feed the classifier/headroom).
	record WorldBox(int bx, int by, int bz,
			double minX, double minZ, double maxX, double maxZ, double yMin, double yMax,
			double blockCollisionTop, double blockOutlineTop) {
		// Boxes gathered as occluders/ledges only never become a drawn top, so they
		// default both block tops to yMax (visualTopY then never raises off yMax).
		WorldBox(int bx, int by, int bz,
				double minX, double minZ, double maxX, double maxZ, double yMin, double yMax) {
			this(bx, by, bz, minX, minZ, maxX, maxZ, yMin, yMax, yMax, yMax);
		}
	}

	// A dilated standable surface tagged with its source cell, the lazy flood's
	// node. The cell bounds the column-local neighbour search; equality is by value
	// (StandableRect is a record) so the visited set dedupes naturally.
	private record CellSurface(StandableRect rect, int cx, int cz) {
	}

	// Column index key (block X/Z) for the per-column box lookup used to bound the
	// occluder search to the candidate's immediate neighborhood. Package-private so
	// the headroom predicate (exposeBox) can be unit-tested with a synthetic index.
	record ColKey(int x, int z) {
	}

	// Grouping key for the merge: two doubles quantized to 1/1024 of a block
	// (finer than any collision-box edge, incl. dilated 0.3 / 0.975 later) so
	// equal spans hash together despite float noise.
	private record SpanKey(long a, long b) {
	}

	// Tolerance for the double coordinate compares (box edges are multiples of
	// 1/16). Used to drop subtraction slivers and to test edge adjacency/overlap.
	private static final double EPS = 1.0e-6;

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

	// Flood path selection (Stage 4.5). LAZY picks the production path: the lazy,
	// output-sensitive flood (exposes columns on demand) vs the eager window scan.
	// PROFILE_FLOOD runs BOTH every select, asserts they cover the same area (the
	// correctness oracle — logs a warning on mismatch), and logs timing + column
	// counts so the two can be A/B'd. Both are compile-time debug switches.
	private static final boolean LAZY = true;
	// Flip on locally to A/B the lazy path against the eager oracle (logs per-select
	// parity + timing/row counts); kept off in committed builds.
	private static final boolean PROFILE_FLOOD = false;

	// The reached, merged surfaces from the last select (the draw set). Replaced
	// wholesale each select; an immutable snapshot is published by the overlay.
	private List<StandableRect> result = List.of();

	// Upward (occluder) skirt spans for the last select: edge sub-spans of the
	// reached surfaces where a box rises above the surface (a wall) or hangs within
	// the entity's headroom (a ceiling). Computed compute-side (it reads collision
	// boxes), published alongside result. Replaced wholesale each select.
	private List<OccluderSpan> occluders = List.of();

	// Downward drop-skirt spans for the last select: each merged-rect edge minus its
	// equal-height merge seams (openSpans) minus the occluder sub-spans above. Once a
	// per-frame O(n^2) render-side scan; now computed compute-side once per select
	// (behavior-preserving) so emit just draws published spans, and so Milestone 5's
	// hole classification can share this same drop-edge pass. Replaced each select.
	private List<DownSkirtSpan> downSkirts = List.of();

	// Hole spans for the last select: drop sub-spans with no reached surface below
	// (void, or unreached ground). Marked by a through-walls beam at the rim.
	// Replaced wholesale each select.
	private List<HoleSpan> holes = List.of();

	// One-shot geometry dump for /mobwalk dump: when set, the next select() logs
	// reached/merged/occluders/skirts/holes then clears the flag. Armed by
	// requestDebugDump() only.
	private boolean debugDumpOnce = false;
	// Pre-merge reached tops captured only when debugDumpOnce is set (lazy path).
	private List<StandableRect> debugReachedPreMerge = List.of();

	/** Arm a one-shot pipeline dump on the next {@link #select}. */
	public void requestDebugDump() {
		debugDumpOnce = true;
	}

	/**
	 * Replace the selection with the merged standable surfaces reachable from the
	 * surfaces of {@code start}, within a spatial window of half-extent
	 * {@code radius} blocks, for the entity's width/reach, across
	 * footprint-adjacent height-gated steps.
	 *
	 * <p>Dispatches to {@link #selectEager} (window scan) or {@link #selectLazy}
	 * (output-sensitive, on-demand column exposure) per {@link #LAZY}. With
	 * {@link #PROFILE_FLOOD} on, both run and are compared (coverage oracle) and
	 * timed; see the field docs.
	 *
	 * <p>{@code computeVisualTop} controls whether the extra visible/outline-top read
	 * (for render-taller-than-collide blocks; see {@code exposeBox}) is done. It is a
	 * per-block cost paid only for blocks colliding below a full cube, so it is off
	 * unless the render toggle wants it — flipping the toggle re-runs {@code select}.
	 */
	public void select(Level level, BlockPos start, int radius, EntityProfile profile,
			boolean computeVisualTop) {
		boolean dump = debugDumpOnce;
		debugReachedPreMerge = List.of();
		if (!PROFILE_FLOOD) {
			if (LAZY) {
				LazyFlood lazy = new LazyFlood(level, start, radius, profile, computeVisualTop);
				result = lazy.run();
				if (dump) {
					debugReachedPreMerge = lazy.preMergeReached();
				}
			} else {
				result = selectEager(level, start, radius, profile, computeVisualTop);
				if (dump) {
					// Eager returns already-flooded merged rects; use that as "reached".
					debugReachedPreMerge = result;
				}
			}
			occluders = computeOccluders(level, result, profile);
			downSkirts = computeDownSkirts(result, occluders);
			holes = computeHoles(level, result, downSkirts, profile, radius);
			if (dump) {
				logFloodDebug(profile, start, radius, computeVisualTop);
				debugDumpOnce = false;
				debugReachedPreMerge = List.of();
			}
			return;
		}

		long t0 = System.nanoTime();
		List<StandableRect> eager = selectEager(level, start, radius, profile, computeVisualTop);
		long t1 = System.nanoTime();
		LazyFlood lazy = new LazyFlood(level, start, radius, profile, computeVisualTop);
		List<StandableRect> lazyRects = lazy.run();
		long t2 = System.nanoTime();

		int eagerMargin = (int) Math.floor(profile.width()) + 1;
		int eagerSide = 2 * (radius + eagerMargin) + 1;
		boolean match = coverageMatches(eager, lazyRects);
		// Eager scans the whole window x full vertical band, every column; lazy's
		// rowsScanned is the apples-to-apples vertical-work comparison.
		int bandLo = Math.max(start.getY() - radius - 1, level.getMinY());
		int bandHi = Math.min(start.getY() + radius + 1, level.getMaxY());
		int eagerRows = eagerSide * eagerSide * (bandHi - bandLo + 1);
		MobWalk.LOGGER.info(
			"[flood] profile={} radius={} | eager {}us scan~{}cols ~{}rows {}rects | lazy {}us {}cols {}rows {}rects | match={}",
			profile.name(), radius,
			(t1 - t0) / 1000, eagerSide * eagerSide, eagerRows, eager.size(),
			(t2 - t1) / 1000, lazy.columnsExposed(), lazy.rowsScanned(), lazyRects.size(), match);
		if (!match) {
			MobWalk.LOGGER.warn("[flood] COVERAGE MISMATCH lazy != eager (profile={} radius={})",
				profile.name(), radius);
		}
		result = LAZY ? lazyRects : eager;
		if (dump) {
			debugReachedPreMerge = LAZY ? lazy.preMergeReached() : result;
		}
		occluders = computeOccluders(level, result, profile);
		downSkirts = computeDownSkirts(result, occluders);
		holes = computeHoles(level, result, downSkirts, profile, radius);
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
		for (OccluderSpan s : occluders) {
			MobWalk.LOGGER.info(
				"[flood-debug]   occ alongX={} side={} line={} [{},{}] baseY={} topY={}",
				s.alongX(), s.positiveSide(), s.line(), s.lo(), s.hi(), s.baseY(), s.topY());
		}
		MobWalk.LOGGER.info("[flood-debug] downskirts={}", downSkirts.size());
		for (DownSkirtSpan s : downSkirts) {
			MobWalk.LOGGER.info(
				"[flood-debug]   drop alongX={} maxSide={} line={} [{},{}] baseY={}",
				s.alongX(), s.maxSide(), s.line(), s.lo(), s.hi(), s.baseY());
		}
		MobWalk.LOGGER.info("[flood-debug] holes={}", holes.size());
		for (HoleSpan s : holes) {
			MobWalk.LOGGER.info(
				"[flood-debug]   hole alongX={} maxSide={} line={} [{},{}] baseY={} fall={}",
				s.alongX(), s.maxSide(), s.line(), s.lo(), s.hi(), s.baseY(), s.fallDistance());
		}
	}

	private static void logFloodDebugRects(String label, List<StandableRect> rects) {
		MobWalk.LOGGER.info("[flood-debug] {}={}", label, rects.size());
		for (StandableRect r : rects) {
			MobWalk.LOGGER.info(
				"[flood-debug]   {} [{},{}]x[{},{}] topY={} depth={}",
				label, r.minX(), r.maxX(), r.minZ(), r.maxZ(), r.topY(), r.depth());
		}
	}

	// The eager, window-driven flood (the verified Stage 4 path; kept as the lazy
	// path's correctness oracle). Enumerates every collision box in the
	// window+margin cube, dilates all, merges, then floods the merged graph.
	private List<StandableRect> selectEager(Level level, BlockPos start, int radius,
			EntityProfile profile, boolean computeVisualTop) {
		double reach = profile.reach();
		double halfW = profile.width() / 2.0;
		double height = profile.height();
		// Occluder margin: gather boxes this many blocks BEYOND the window so an
		// outer-ring candidate is trimmed by every box that can eat its dilated top.
		// Both the candidate top and an occluder grow by W/2, so two cells influence
		// each other while the undilated gap (Δ-1 blocks) is within W — i.e. up to
		// floor(W)+1 blocks apart. This is the SAME reach the lazy neighbour search
		// uses (one formula everywhere). It is a safe superset of the tight
		// positive-overlap occluder reach ceil(W) — equal for non-integer widths
		// (Player 1, Ravager 2); at W=0 (Point) it gathers one extra ring that only
		// touches (zero-area), trimming nothing, so the result is unchanged. (NB the
		// reach is W, not W/2: an early ceil(W/2) under-trimmed wide entities.)
		int margin = (int) Math.floor(profile.width()) + 1;
		BlockPos origin = start.immutable();
		int ox = origin.getX();
		int oy = origin.getY();
		int oz = origin.getZ();

		// Phase 1: gather every collision sub-box in the window+margin, indexed by
		// column, and split off the candidate tops (boxes inside the radius window)
		// and the seed-column boxes. The margin ring supplies dilated occluders for
		// edge candidates without itself producing painted tops.
		int yLo = Math.max(oy - radius - 1, level.getMinY());
		int yHi = Math.min(oy + radius + 1, level.getMaxY());
		Map<ColKey, List<WorldBox>> index = new HashMap<>();
		List<WorldBox> candidates = new ArrayList<>();
		List<WorldBox> seedBoxes = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = ox - radius - margin; x <= ox + radius + margin; x++) {
			for (int z = oz - radius - margin; z <= oz + radius + margin; z++) {
				boolean colInWindow = Math.abs(x - ox) <= radius && Math.abs(z - oz) <= radius;
				boolean originCol = x == ox && z == oz;
				List<WorldBox> column = null;
				for (int y = yLo; y <= yHi; y++) {
					cursor.set(x, y, z);
					BlockState state = level.getBlockState(cursor);
					VoxelShape shape = state.getCollisionShape(level, cursor, CollisionContext.empty());
					if (shape.isEmpty()) {
						continue;
					}
					double blockCollisionTop = y + shape.max(Direction.Axis.Y);
					double blockOutlineTop = visibleTop(level, cursor, state, blockCollisionTop, computeVisualTop);
					boolean yInWindow = y >= oy - radius && y <= oy + radius;
					for (AABB box : shape.toAabbs()) {
						WorldBox wb = new WorldBox(x, y, z,
							x + box.minX, z + box.minZ, x + box.maxX, z + box.maxZ,
							y + box.minY, y + box.maxY,
							blockCollisionTop, blockOutlineTop);
						if (column == null) {
							column = index.computeIfAbsent(new ColKey(x, z), k -> new ArrayList<>());
						}
						column.add(wb);
						if (colInWindow && yInWindow) {
							candidates.add(wb);
						}
						// Seed tops from the clicked block (source block Y = oy).
						if (originCol && y == oy) {
							seedBoxes.add(wb);
						}
					}
				}
			}
		}

		// The clicked block's dilated tops decide where the flood starts; if that
		// block has no standable top (e.g. a wide entity buried beside a wall),
		// there is nothing to select.
		List<StandableRect> seedSurfaces = new ArrayList<>();
		for (WorldBox seed : seedBoxes) {
			exposeBox(seed, index, halfW, height, seedSurfaces);
		}
		if (seedSurfaces.isEmpty()) {
			return List.of();
		}

		// Phase 2: build the dilated arrangement, then merge coplanar adjacent rects.
		List<StandableRect> arrangement = new ArrayList<>();
		for (WorldBox candidate : candidates) {
			exposeBox(candidate, index, halfW, height, arrangement);
		}
		List<StandableRect> merged = mergeCoplanar(arrangement);

		// Phase 3: flood the merged graph from the rect(s) covering a seed surface.
		return flood(merged, seedSurfaces, reach);
	}

	// The lazy, output-sensitive flood (Stage 4.5 production path). See LazyFlood.
	private List<StandableRect> selectLazy(Level level, BlockPos start, int radius,
			EntityProfile profile, boolean computeVisualTop) {
		return new LazyFlood(level, start, radius, profile, computeVisualTop).run();
	}

	public void clear() {
		result = List.of();
		occluders = List.of();
		downSkirts = List.of();
		holes = List.of();
		debugDumpOnce = false;
		debugReachedPreMerge = List.of();
	}

	/** Immutable snapshot of the reached surfaces (height-tinted at draw time). */
	public List<StandableRect> allRects() {
		return result;
	}

	/** Immutable snapshot of the upward (occluder) skirt spans for the reached set. */
	public List<OccluderSpan> allOccluders() {
		return occluders;
	}

	/** Immutable snapshot of the downward drop-skirt spans for the reached set. */
	public List<DownSkirtSpan> allDownSkirts() {
		return downSkirts;
	}

	/** Immutable snapshot of the hole spans (through-walls beam markers) for the reached set. */
	public List<HoleSpan> allHoles() {
		return holes;
	}

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
				if (Math.abs(other.topY() - cur.topY()) <= reach + EPS && footprintAdjacent(cur, other)) {
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
			r.topY(), r.visualTopY(), depth);
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
				if (Math.abs(r.topY() - m.topY()) > EPS) {
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
			if (Math.abs(rect.topY() - seed.topY()) <= EPS
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

	// Merge coplanar (same topY) rects into maximal rects. Group by topY, then
	// within each group first re-cut to a NON-OVERLAPPING union (union, below) —
	// dilated neighbor tops grow into each other, and overlapping translucent quads
	// double-blend into darker seams — then greedily merge abutting equal-span
	// strips along X then Z (repeated until stable), collapsing the grid back to
	// whole rectangles. Greedy is not a minimal partition, but any miss only costs
	// an extra interior skirt, never reachability.
	static List<StandableRect> mergeCoplanar(List<StandableRect> input) {
		if (input.size() < 2) {
			return input;
		}
		List<StandableRect> sorted = new ArrayList<>(input);
		sorted.sort(Comparator.comparingDouble(StandableRect::topY));

		List<StandableRect> out = new ArrayList<>();
		int i = 0;
		while (i < sorted.size()) {
			double topY = sorted.get(i).topY();
			int j = i + 1;
			while (j < sorted.size() && sorted.get(j).topY() - topY <= EPS) {
				j++;
			}

			List<Rect> rects = new ArrayList<>();
			// A coplanar group shares one collision topY; carry the group's max
			// visible top so a raised block (soul sand) still draws on its face after
			// merging with equal-collision-top neighbours. Mixed visible tops in one
			// group are rare (equal collision top, different render height) and the
			// max keeps the raised block visible rather than re-burying it.
			double groupVisualTop = topY;
			for (StandableRect r : sorted.subList(i, j)) {
				rects.add(new Rect(r.minX(), r.minZ(), r.maxX(), r.maxZ()));
				groupVisualTop = Math.max(groupVisualTop, r.visualTopY());
			}
			rects = union(rects);
			int before;
			do {
				before = rects.size();
				rects = mergeAlong(rects, true);
				rects = mergeAlong(rects, false);
			} while (rects.size() < before);

			for (Rect r : rects) {
				out.add(new StandableRect(r.minX(), r.minZ(), r.maxX(), r.maxZ(), topY, groupVisualTop));
			}
			i = j;
		}
		return out;
	}

	// Depth-aware variant of mergeCoplanar used by the lazy flood path. Splits
	// raw nodes into inner (depth < limit) and frontier (depth >= limit), unions
	// each independently, then subtracts the inner area from the frontier so the
	// two tile cleanly with no overlap (inner has priority in the overlap zone
	// where dilated surfaces grow into each other). Result: the frontier ring
	// keeps its real depth and is never collapsed into the inner blob, so the
	// renderer's depth-based perimeter suppression and grey-blend work correctly.
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
		Arrays.sort(idx, Comparator.comparingDouble(a -> nodes.get(a).topY()));
		for (int i = 0; i < idx.length; i++) {
			sorted.set(i, nodes.get(idx[i]));
			sortedDepths[i] = nodeDepths[idx[i]];
		}

		List<StandableRect> out = new ArrayList<>();
		int i = 0;
		while (i < sorted.size()) {
			double topY = sorted.get(i).topY();
			int j = i + 1;
			while (j < sorted.size() && sorted.get(j).topY() - topY <= EPS) {
				j++;
			}

			double groupVisualTop = topY;
			List<Rect> innerRaw = new ArrayList<>();
			List<Rect> frontierRaw = new ArrayList<>();
			for (int k = i; k < j; k++) {
				StandableRect r = sorted.get(k);
				groupVisualTop = Math.max(groupVisualTop, r.visualTopY());
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
					topY, groupVisualTop, best));
			}
			for (Rect r : frontierMerged) {
				out.add(new StandableRect(r.minX(), r.minZ(), r.maxX(), r.maxZ(),
					topY, groupVisualTop, limit));
			}
			i = j;
		}
		return out;
	}

	// The X-then-Z greedy strip merge loop used by both mergeCoplanar and
	// mergeCoplanarSplitFrontier (the split path runs it per bucket).
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
	// never wins over a real depth (only two -1s yield -1).
	private static int minDepth(int a, int b) {
		if (a < 0) {
			return b;
		}
		if (b < 0) {
			return a;
		}
		return Math.min(a, b);
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
		double topY = target.yMax();
		// Draw-only raise (Milestone 6): if this box IS the source block's topmost
		// collision surface and the block renders taller than it collides (soul sand,
		// mud), expose the visible/outline top so the marker is drawn on the face you
		// see rather than buried. Gating on "topmost collision surface" leaves stair
		// treads / bottom slabs / fence tops untouched; everything else keeps
		// visualTopY == topY. Nothing but rendering reads visualTopY.
		double visualTopY = (Math.abs(topY - target.blockCollisionTop()) <= EPS
				&& target.blockOutlineTop() > topY + EPS)
			? target.blockOutlineTop()
			: topY;
		Rect base = new Rect(
			target.minX() - halfW, target.minZ() - halfW,
			target.maxX() + halfW, target.maxZ() + halfW);

		List<Rect> occluders = new ArrayList<>();
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
					// Occluder iff it rises above T AND either reaches down to/below
					// the surface (buried: a box resting on top has yMin == T, a box
					// straddling T has yMin < T) OR floats within the standing column
					// (T, T+H) (a headroom ceiling). The buried term is the H == 0 base
					// case (Point) — without it a box sitting directly on the surface
					// would NOT occlude and every embedded/stacked top would leak.
					boolean buried = other.yMin() <= topY + EPS;
					boolean headroomCeiling = other.yMin() < topY + height - EPS;
					if (other.yMax() > topY + EPS && (buried || headroomCeiling)) {
						occluders.add(new Rect(
							other.minX() - halfW, other.minZ() - halfW,
							other.maxX() + halfW, other.maxZ() + halfW));
					}
				}
			}
		}

		for (Rect exposed : subtractRects(base, occluders)) {
			out.add(new StandableRect(exposed.minX(), exposed.minZ(), exposed.maxX(), exposed.maxZ(), topY, visualTopY));
		}
	}

	// The source block's visible top (world Y), used to raise a standable surface to
	// the face you actually see for render-taller-than-collide blocks (soul sand, mud,
	// cactus, honey, and any modded/future block with the same property). No heuristic:
	// EVERY block state is checked, but the outline shape (getShape) is read at most
	// once per distinct BlockState and memoized in OUTLINE_TOP_REL, so the per-block
	// cost is a map lookup. Returns the collision top unchanged when the toggle is off
	// or the state has no separate outline (NaN memo). The exposeBox raise rule then
	// decides whether to actually lift (only its block's topmost collision surface, and
	// only when the outline is strictly higher), so a fence (outline 1.0 < collision
	// 1.5) is returned here but not raised there.
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
	private record SpanGroupKey(boolean alongX, boolean positiveSide, long line, long baseY) {
	}

	// True iff box is an occluder/wall for a surface at height T given the entity
	// headroom: its top rises strictly above T AND its base sits at or below the top
	// of the standing column T+height. height == 0 (Part A / Point) is the pure wall
	// test (a box rising above T whose base is at or below T); height > 0 also admits
	// ceilings/overhangs hanging within the standing column (Part B headroom). The
	// {@code <=} on the lower bound (vs the strict {@code <} the exposeBox cut uses)
	// is deliberate: occluder spans are only emitted where a dilated occluder ABUTS a
	// surface edge (occluderSpansForRect), and that abutment gate — not the predicate —
	// rejects the boundary/own-floor cases, while keeping Point's at-floor walls
	// (yMin == T) marked.
	static boolean wallOccluder(WorldBox b, double topY, double height) {
		return b.yMax() > topY + EPS && b.yMin() <= topY + height + EPS;
	}

	// Append the upward (occluder) skirt spans for one surface rect: for every
	// candidate box that is a {@link #wallOccluder} of this surface, dilate its
	// footprint by halfW and, where the dilated footprint ABUTS one of the rect's
	// four edges (sharing the edge line with positive overlap along it), emit a span
	// over the overlap — the wall/ceiling face sits at the dilated (set-back) edge,
	// not the real block face. Pure: no world access (candidates are pre-gathered).
	static void occluderSpansForRect(StandableRect r, List<WorldBox> candidates,
			double halfW, double height, List<OccluderSpan> out) {
		double topY = r.topY();
		double visualTopY = r.visualTopY();
		// The occluder skirt inherits its surface's flood-depth so the two share a
		// color band in the debug depth-coloring (see StandableRect.depth).
		int depth = r.depth();
		for (WorldBox b : candidates) {
			if (!wallOccluder(b, topY, height)) {
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
					out.add(new OccluderSpan(false, true, r.maxX(), zLo, zHi, topY, top, visualTopY, depth));
				}
				if (Math.abs(oMaxX - r.minX()) < EPS) {
					out.add(new OccluderSpan(false, false, r.minX(), zLo, zHi, topY, top, visualTopY, depth));
				}
			}
			double xLo = Math.max(oMinX, r.minX());
			double xHi = Math.min(oMaxX, r.maxX());
			if (xHi - xLo > EPS) {
				if (Math.abs(oMinZ - r.maxZ()) < EPS) {
					out.add(new OccluderSpan(true, true, r.maxZ(), xLo, xHi, topY, top, visualTopY, depth));
				}
				if (Math.abs(oMaxZ - r.minZ()) < EPS) {
					out.add(new OccluderSpan(true, false, r.minZ(), xLo, xHi, topY, top, visualTopY, depth));
				}
			}
		}
	}

	// Coalesce occluder spans that are collinear (same orientation + edge line +
	// base height) and overlap/abut along the edge into one span, taking the max
	// occluder top — so stacked/adjacent occluder boxes don't emit overlapping
	// double-blending up-skirts.
	static List<OccluderSpan> mergeOccluderSpans(List<OccluderSpan> spans) {
		if (spans.size() < 2) {
			return spans;
		}
		Map<SpanGroupKey, List<OccluderSpan>> groups = new LinkedHashMap<>();
		for (OccluderSpan s : spans) {
			SpanGroupKey key = new SpanGroupKey(s.alongX(), s.positiveSide(),
				Math.round(s.line() * 1024.0), Math.round(s.baseY() * 1024.0));
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
		}
		List<OccluderSpan> out = new ArrayList<>();
		for (List<OccluderSpan> group : groups.values()) {
			group.sort(Comparator.comparingDouble(OccluderSpan::lo));
			OccluderSpan head = group.get(0);
			double lo = head.lo();
			double hi = head.hi();
			double top = head.topY();
			// baseY is fixed per group (grouped on it); the visible base can differ
			// when a raised block abuts a flush one, so take the max like top.
			double visualBase = head.visualBaseY();
			// Coalesced spans can come from different surfaces (same edge line/height,
			// different depth); take the min so the merged marker reads as the nearest
			// surface's band (mirrors the max-top handling above).
			int depth = head.depth();
			for (int i = 1; i < group.size(); i++) {
				OccluderSpan s = group.get(i);
				if (s.lo() <= hi + EPS) {
					hi = Math.max(hi, s.hi());
					top = Math.max(top, s.topY());
					visualBase = Math.max(visualBase, s.visualBaseY());
					depth = minDepth(depth, s.depth());
				} else {
					out.add(new OccluderSpan(head.alongX(), head.positiveSide(),
						head.line(), lo, hi, head.baseY(), top, visualBase, depth));
					lo = s.lo();
					hi = s.hi();
					top = s.topY();
					visualBase = s.visualBaseY();
					depth = s.depth();
				}
			}
			out.add(new OccluderSpan(head.alongX(), head.positiveSide(),
				head.line(), lo, hi, head.baseY(), top, visualBase, depth));
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
	 * Classify one drop sub-span. Pure: given the fall footprint, the flood's
	 * reached set, and any intermediate standable ledges between the edge and the
	 * reached floor, returns HOLE or BENIGN.
	 *
	 * <ol>
	 * <li>Find the topmost reached surface strictly below {@code topY} that
	 *     overlaps the footprint. If none &rarr; HOLE (void / unreached ground).
	 * <li>If a reached floor exists at {@code landY}: check whether any surface in
	 *     {@code ledges} (dilated standable surfaces with top in {@code (landY,
	 *     topY)}) overlaps the footprint. If yes &rarr; HOLE (entity lands on the
	 *     ledge and is trapped). If no &rarr; BENIGN (fall distance = topY &minus;
	 *     landY).
	 * </ol>
	 */
	static DropClassification classifyDrop(Rect fallFootprint, double topY,
			List<StandableRect> reached, List<StandableRect> ledges) {
		StandableRect landing = null;
		for (StandableRect r : reached) {
			if (r.topY() >= topY - EPS) {
				continue;
			}
			if (Math.min(r.maxX(), fallFootprint.maxX()) - Math.max(r.minX(), fallFootprint.minX()) <= EPS
					|| Math.min(r.maxZ(), fallFootprint.maxZ()) - Math.max(r.minZ(), fallFootprint.minZ()) <= EPS) {
				continue;
			}
			if (landing == null || r.topY() > landing.topY()) {
				landing = r;
			}
		}
		if (landing == null) {
			return new DropClassification(DropClass.HOLE, 0.0);
		}
		double landY = landing.topY();
		for (StandableRect ledge : ledges) {
			if (ledge.topY() <= landY + EPS || ledge.topY() >= topY - EPS) {
				continue;
			}
			if (Math.min(ledge.maxX(), fallFootprint.maxX()) - Math.max(ledge.minX(), fallFootprint.minX()) <= EPS
					|| Math.min(ledge.maxZ(), fallFootprint.maxZ()) - Math.max(ledge.minZ(), fallFootprint.minZ()) <= EPS) {
				continue;
			}
			return new DropClassification(DropClass.HOLE, topY - ledge.topY());
		}
		return new DropClassification(DropClass.BENIGN, topY - landY);
	}

	// Compute the downward drop-skirt spans of the whole reached set, once per
	// select. For each merged rect edge: the edge minus the parts covered by an
	// equal-height neighbour abutting across it (a merge seam, not a drop) minus the
	// occluder (wall/ceiling) sub-spans on that edge (they get an upward skirt), the
	// leftover being the genuine drop sub-spans. This replaces the old per-frame
	// render-side scan (openSpans/upIntervalsOnEdge, O(n^2) every frame) with one
	// compute-side pass; the result must be pixel-identical. Package-private for unit
	// tests (synthetic rects, no world).
	static List<DownSkirtSpan> computeDownSkirts(List<StandableRect> rects, List<OccluderSpan> occluders) {
		List<DownSkirtSpan> out = new ArrayList<>();
		for (StandableRect r : rects) {
			edgeDownSpans(rects, occluders, r, true, false, out);  // -Z edge
			edgeDownSpans(rects, occluders, r, true, true, out);   // +Z edge
			edgeDownSpans(rects, occluders, r, false, false, out); // -X edge
			edgeDownSpans(rects, occluders, r, false, true, out);  // +X edge
		}
		return out;
	}

	// Append the drop sub-spans of one rect edge (see computeDownSkirts). alongX: the
	// edge runs along X at a fixed Z; maxSide: the +axis edge (line = the rect's max
	// coordinate on the perpendicular axis). Coverage from equal-height neighbours and
	// from occluder spans on this edge is subtracted together (set difference is
	// order-independent, so unioning then subtracting matches the old two-stage
	// openSpans-then-occluder subtraction).
	private static void edgeDownSpans(List<StandableRect> rects, List<OccluderSpan> occluders,
			StandableRect r, boolean alongX, boolean maxSide, List<DownSkirtSpan> out) {
		double lo = alongX ? r.minX() : r.minZ();
		double hi = alongX ? r.maxX() : r.maxZ();
		double line = alongX ? (maxSide ? r.maxZ() : r.minZ()) : (maxSide ? r.maxX() : r.minX());

		List<double[]> covered = new ArrayList<>();
		for (StandableRect nb : rects) {
			if (nb == r || Math.abs(nb.topY() - r.topY()) > EPS) {
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
			if (abuts && chi - clo > EPS) {
				covered.add(new double[] {clo, chi});
			}
		}
		for (OccluderSpan s : occluders) {
			if (s.alongX() != alongX || s.positiveSide() != maxSide) {
				continue;
			}
			if (Math.abs(s.line() - line) > EPS || Math.abs(s.baseY() - r.topY()) > EPS) {
				continue;
			}
			double clo = Math.max(s.lo(), lo);
			double chi = Math.min(s.hi(), hi);
			if (chi - clo > EPS) {
				covered.add(new double[] {clo, chi});
			}
		}

		for (double[] iv : subtractIntervals(lo, hi, covered)) {
			// The skirt inherits its surface's flood-depth so the two share a color
			// band in the debug depth-coloring (see StandableRect.depth).
			out.add(new DownSkirtSpan(alongX, maxSide, line, iv[0], iv[1], r.topY(), r.visualTopY(), r.depth()));
		}
	}

	// [lo,hi] minus the union of covered intervals, as the remaining open sub-spans
	// (left-to-right sweep over the sorted intervals). The double-precision twin of
	// the old render-side subtractSpans.
	private static List<double[]> subtractIntervals(double lo, double hi, List<double[]> covered) {
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

	// How far beyond the rim (in blocks) the fall footprint probes for a landing.
	// One block off the cliff edge is where a mob leaving the edge drops.
	private static final double FALL_PROBE = 1.0;

	// Classify each drop span and return the hole sub-spans (through-walls beam
	// candidates). For each drop span: (1) check if a reached surface exists below
	// under the fall footprint, (2) if yes, scan the world between topY and landY for
	// intermediate standable surfaces (ledges) via exposeBox — if any overlap the
	// footprint, the entity gets trapped on the ledge -> HOLE. Because one edge can
	// span several verdicts, the span is SUBDIVIDED at reached-rect boundaries into
	// homogeneous sub-spans. Runs once per select (not per frame).
	private List<HoleSpan> computeHoles(Level level, List<StandableRect> rects,
			List<DownSkirtSpan> drops, EntityProfile profile, int depthLimit) {
		if (drops.isEmpty()) {
			return List.of();
		}
		double halfW = profile.width() / 2.0;
		double height = profile.height();
		List<HoleSpan> out = new ArrayList<>();
		List<StandableRect> ledges = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (DownSkirtSpan sp : drops) {
			if (sp.depth() >= depthLimit) {
				continue;
			}
			Rect band = fallFootprint(sp);
			ledges.clear();
			gatherLedges(level, cursor, band, sp.baseY(), rects, halfW, height, ledges);
			holeSubSpans(sp, band, rects, ledges, out);
		}
		return out;
	}

	// Pure: subdivide one drop span into homogeneous sub-spans (at reached-rect
	// boundaries), classify each via classifyDrop (with ledge check), and append the
	// contiguous HOLE pieces (coalesced) as HoleSpans. A single edge can span reached
	// and unreached ground, so classifying the whole edge at once mislabels it.
	// Package-private for unit tests (synthetic reached rects / ledges, no world).
	static void holeSubSpans(DownSkirtSpan sp, Rect band,
			List<StandableRect> reached, List<StandableRect> ledges, List<HoleSpan> out) {
		double topY = sp.baseY();
		double[] cuts = spanBreakpoints(sp, band, reached);
		double holeLo = Double.NaN;
		double holeHi = 0.0;
		double holeFall = 0.0;
		for (int i = 0; i + 1 < cuts.length; i++) {
			double a = cuts[i];
			double b = cuts[i + 1];
			if (b - a <= EPS) {
				continue;
			}
			Rect subFp = subBand(sp, band, a, b);
			DropClassification c = classifyDrop(subFp, topY, reached, ledges);
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
				out.add(new HoleSpan(sp.alongX(), sp.maxSide(), sp.line(), holeLo, holeHi, topY, holeFall, sp.visualBaseY()));
				holeLo = a;
				holeHi = b;
				holeFall = c.fallDistance();
			}
		}
		if (!Double.isNaN(holeLo)) {
			out.add(new HoleSpan(sp.alongX(), sp.maxSide(), sp.line(), holeLo, holeHi, topY, holeFall, sp.visualBaseY()));
		}
	}

	// Breakpoints along a drop span's varying axis where its classification can
	// change: the span ends, integer block boundaries, and the varying-axis edges of
	// every reached rect whose fixed axis overlaps the fall footprint. Splitting here
	// makes each sub-span homogeneous (uniform "reached below or not"), so classifyDrop
	// is exact on it. Duplicates collapse to zero-width sub-spans (skipped by caller).
	private static double[] spanBreakpoints(DownSkirtSpan sp, Rect band,
			List<StandableRect> rects) {
		double lo = sp.lo();
		double hi = sp.hi();
		List<Double> cuts = new ArrayList<>();
		cuts.add(lo);
		cuts.add(hi);
		for (int k = (int) Math.floor(lo) + 1; k <= (int) Math.ceil(hi) - 1; k++) {
			addCut(cuts, k, lo, hi);
		}
		for (StandableRect r : rects) {
			if (fixedAxisOverlaps(sp, band, r)) {
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

	// True iff rect r overlaps the fall footprint band on the FIXED axis (Z for an
	// X-running span, else X) — i.e. r could be the landing under some sub-span.
	private static boolean fixedAxisOverlaps(DownSkirtSpan sp, Rect band, StandableRect r) {
		if (sp.alongX()) {
			return Math.min(r.maxZ(), band.maxZ()) - Math.max(r.minZ(), band.minZ()) > EPS;
		}
		return Math.min(r.maxX(), band.maxX()) - Math.max(r.minX(), band.minX()) > EPS;
	}

	// The fall footprint band clipped to the varying-axis sub-interval [a,b].
	private static Rect subBand(DownSkirtSpan sp, Rect band, double a, double b) {
		if (sp.alongX()) {
			return new Rect(a, band.minZ(), b, band.maxZ());
		}
		return new Rect(band.minX(), a, band.maxX(), b);
	}

	// The fall footprint of a drop span: a FALL_PROBE-deep band just beyond the rim
	// (on the drop side), along the span's [lo,hi]. maxSide drops toward +axis.
	static Rect fallFootprint(DownSkirtSpan sp) {
		if (sp.alongX()) {
			double zNear = sp.maxSide() ? sp.line() : sp.line() - FALL_PROBE;
			double zFar = sp.maxSide() ? sp.line() + FALL_PROBE : sp.line();
			return new Rect(sp.lo(), zNear, sp.hi(), zFar);
		}
		double xNear = sp.maxSide() ? sp.line() : sp.line() - FALL_PROBE;
		double xFar = sp.maxSide() ? sp.line() + FALL_PROBE : sp.line();
		return new Rect(xNear, sp.lo(), xFar, sp.hi());
	}


	// Scan the world for standable surfaces (via exposeBox) between landY and topY that
	// overlap the fall footprint — intermediate ledges that would trap the entity. For
	// each block column overlapping fp, scan rows in (landY, topY), gather collision
	// boxes, build a local occluder index, and call exposeBox on each candidate whose
	// top is strictly between landY and topY. Appends exposed StandableRects to out.
	// Only called when a reached floor exists below (landY is known).
	private static void gatherLedges(Level level, BlockPos.MutableBlockPos cursor,
			Rect fp, double topY, List<StandableRect> reached, double halfW, double height,
			List<StandableRect> out) {
		// Find landY: the topmost reached surface below topY overlapping the footprint.
		double landY = Double.NEGATIVE_INFINITY;
		for (StandableRect r : reached) {
			if (r.topY() >= topY - EPS) {
				continue;
			}
			if (Math.min(r.maxX(), fp.maxX()) - Math.max(r.minX(), fp.minX()) <= EPS
					|| Math.min(r.maxZ(), fp.maxZ()) - Math.max(r.minZ(), fp.minZ()) <= EPS) {
				continue;
			}
			if (r.topY() > landY) {
				landY = r.topY();
			}
		}
		if (landY == Double.NEGATIVE_INFINITY) {
			return;
		}
		// Scan block columns overlapping the footprint. Candidates are boxes whose
		// top lies in (landY, topY). The occluder index must also include collision
		// from the block row below landY — shapes that live in a lower block but
		// rise into (landY, topY) (walls/fences at height 1.5). Without those
		// occluders-from-below, exposeBox can leave standable ledge fragments that
		// hole-classification then treats as traps. Motivating case: lantern on a
		// wall — the wall box is in floor(landY)-1 and must bury the lantern body.
		//
		// Assumption (recorded): one block row below landY is enough because
		// vanilla collision that matters here extends at most ~1.5 upward from its
		// block Y. A taller single-block collision (or a multi-block pillar whose
		// lowest piece sits further below) would need a deeper yLo; revisit then.
		int xLo = (int) Math.floor(fp.minX());
		int xHi = (int) Math.ceil(fp.maxX()) - 1;
		int zLo = (int) Math.floor(fp.minZ());
		int zHi = (int) Math.ceil(fp.maxZ()) - 1;
		int yLo = (int) Math.floor(landY) - 1;
		int yHi = (int) Math.ceil(topY);
		Map<ColKey, List<WorldBox>> index = new HashMap<>();
		List<WorldBox> candidates = new ArrayList<>();
		for (int x = xLo - (int) Math.ceil(halfW); x <= xHi + (int) Math.ceil(halfW) + 1; x++) {
			for (int z = zLo - (int) Math.ceil(halfW); z <= zHi + (int) Math.ceil(halfW) + 1; z++) {
				for (int y = yLo; y <= yHi; y++) {
					cursor.set(x, y, z);
					VoxelShape shape = level.getBlockState(cursor)
						.getCollisionShape(level, cursor, CollisionContext.empty());
					if (shape.isEmpty()) {
						continue;
					}
					for (AABB box : shape.toAabbs()) {
						WorldBox wb = new WorldBox(x, y, z,
							x + box.minX, z + box.minZ, x + box.maxX, z + box.maxZ,
							y + box.minY, y + box.maxY);
						index.computeIfAbsent(new ColKey(x, z), k -> new ArrayList<>()).add(wb);
						double top = wb.yMax();
						if (top > landY + EPS && top < topY - EPS) {
							candidates.add(wb);
						}
					}
				}
			}
		}
		// exposeBox each candidate and collect fragments overlapping the footprint.
		List<StandableRect> exposed = new ArrayList<>();
		for (WorldBox cand : candidates) {
			exposed.clear();
			exposeBox(cand, index, halfW, height, exposed);
			for (StandableRect s : exposed) {
				if (s.topY() <= landY + EPS || s.topY() >= topY - EPS) {
					continue;
				}
				if (Math.min(s.maxX(), fp.maxX()) - Math.max(s.minX(), fp.minX()) > EPS
						&& Math.min(s.maxZ(), fp.maxZ()) - Math.max(s.minZ(), fp.minZ()) > EPS) {
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
	private List<OccluderSpan> computeOccluders(Level level, List<StandableRect> rects, EntityProfile profile) {
		if (rects.isEmpty()) {
			return List.of();
		}
		double halfW = profile.width() / 2.0;
		double height = profile.height();
		List<OccluderSpan> out = new ArrayList<>();
		List<WorldBox> candidates = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (StandableRect r : rects) {
			double topY = r.topY();
			int xLo = (int) Math.floor(r.minX() - halfW) - 1;
			int xHi = (int) Math.ceil(r.maxX() + halfW);
			int zLo = (int) Math.floor(r.minZ() - halfW) - 1;
			int zHi = (int) Math.ceil(r.maxZ() + halfW);
			int yLo = Math.max((int) Math.floor(topY) - 1, level.getMinY());
			int yHi = Math.min((int) Math.floor(topY + height) + 1, level.getMaxY());
			candidates.clear();
			for (int x = xLo; x <= xHi; x++) {
				for (int z = zLo; z <= zHi; z++) {
					for (int y = yLo; y <= yHi; y++) {
						cursor.set(x, y, z);
						VoxelShape shape = level.getBlockState(cursor)
							.getCollisionShape(level, cursor, CollisionContext.empty());
						if (shape.isEmpty()) {
							continue;
						}
						for (AABB box : shape.toAabbs()) {
							candidates.add(new WorldBox(x, y, z,
								x + box.minX, z + box.minZ, x + box.maxX, z + box.maxZ,
								y + box.minY, y + box.maxY));
						}
					}
				}
			}
			occluderSpansForRect(r, candidates, halfW, height, out);
		}
		return mergeOccluderSpans(out);
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
	 * most once. Only the origin column exposes its full band (to seed every
	 * standable top there). This drops the per-column vertical factor from
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
	 * scans — exposed first, so the spans-above test matches eager) and a surface is
	 * enqueued iff it is unvisited,
	 * {@link #footprintAdjacent}, and within {@code reach}.
	 *
	 * <p><b>Depth-bounded</b> (debug mode): the flood stops when BFS hop-count
	 * exceeds {@code radius} (now interpreted as a max-depth limit, not a spatial
	 * window). There is no spatial X/Z bound on which columns the flood can reach;
	 * the depth limit itself provides termination. A generous Y band
	 * ({@code oy ± radius + 2}) still constrains vertical scanning since each step
	 * changes height by at most {@code reach}. The merge/union runs <b>after</b>
	 * the flood on the reached set only (area-preserving, so connectivity is
	 * unchanged — {@code footprintAdjacent} already treats overlap as connected).
	 */
	private static final class LazyFlood {
		private final Level level;
		private final int ox;
		private final int oy;
		private final int oz;
		private final int radius;
		private final double halfW;
		private final double height;
		private final double reach;
		// Chebyshev neighbour-search radius in cells = floor(W)+1 (see class doc).
		private final int neighbour;
		// Whether to pay the visible/outline-top read (render toggle; see visibleTop).
		private final boolean computeVisualTop;
		private final int yLo;
		private final int yHi;
		// Per-column collision boxes found so far (the occluder index), and which
		// block rows of each column have already been queried (bit i == row yLo+i).
		// Lazy in Y: a column is scanned only over the narrow row windows the flood
		// actually needs near its current height, never the full [yLo,yHi] band.
		private final Map<ColKey, List<WorldBox>> index = new HashMap<>();
		private final Map<ColKey, BitSet> scanned = new HashMap<>();
		// exposeBox memoized per source box (its occluder shell is fixed once the
		// rows around its top are exposed), so a box's top is computed exactly once
		// even though a cell is revisited from many neighbours / at many heights.
		private final Map<WorldBox, List<StandableRect>> boxSurfaces = new HashMap<>();
		private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		private int columnsExposed = 0;
		private int rowsScanned = 0;
		// Pre-merge BFS reached set (for /mobwalk dump); empty until run() finishes.
		private List<StandableRect> preMergeReached = List.of();

		LazyFlood(Level level, BlockPos start, int radius, EntityProfile profile,
				boolean computeVisualTop) {
			this.level = level;
			BlockPos origin = start.immutable();
			this.ox = origin.getX();
			this.oy = origin.getY();
			this.oz = origin.getZ();
			this.radius = radius;
			this.halfW = profile.width() / 2.0;
			this.height = profile.height();
			this.reach = profile.reach();
			this.neighbour = (int) Math.floor(profile.width()) + 1;
			this.computeVisualTop = computeVisualTop;
			this.yLo = Math.max(oy - radius - 1, level.getMinY());
			this.yHi = Math.min(oy + radius + 1, level.getMaxY());
		}

		int columnsExposed() {
			return columnsExposed;
		}

		int rowsScanned() {
			return rowsScanned;
		}

		List<StandableRect> preMergeReached() {
			return preMergeReached;
		}

		List<StandableRect> run() {
			// Seeds: standable tops of the clicked block (matches eager). Other
			// tops in the origin column join via normal BFS hops.
			List<CellSurface> seeds = collectSeedBlock();
			if (seeds.isEmpty()) {
				preMergeReached = List.of();
				return List.of();
			}
			// depth doubles as the visited set: a key is present iff visited, and its
			// value is the BFS hop-count from the seed (0 = seed). FIFO queue + set on
			// enqueue makes this the shortest surface-graph distance.
			Map<CellSurface, Integer> depth = new HashMap<>();
			Deque<CellSurface> queue = new ArrayDeque<>();
			for (CellSurface seed : seeds) {
				if (depth.putIfAbsent(seed, 0) == null) {
					queue.addLast(seed);
				}
			}
			// The reached raw (pre-merge) nodes and their depths, in lock-step; the
			// merged output's per-rect depth is aggregated (min) from these.
			List<StandableRect> reached = new ArrayList<>();
			List<Integer> reachedDepths = new ArrayList<>();
			while (!queue.isEmpty()) {
				CellSurface s = queue.pollFirst();
				int d = depth.get(s);
				reached.add(s.rect());
				reachedDepths.add(d);
				// At the depth limit: emit this node but don't explore its neighbors.
				if (d >= radius) {
					continue;
				}
				double h = s.rect().topY();
				for (int cx = s.cx() - neighbour; cx <= s.cx() + neighbour; cx++) {
					for (int cz = s.cz() - neighbour; cz <= s.cz() + neighbour; cz++) {
						// Only tops within a single step of h can connect, so scan
						// just that height window of the neighbour column.
						for (CellSurface t : collect(cx, cz, h - reach, h + reach)) {
							if (depth.containsKey(t)) {
								continue;
							}
							if (footprintAdjacent(s.rect(), t.rect())) {
								depth.put(t, d + 1);
								queue.addLast(t);
							}
						}
					}
				}
			}
			preMergeReached = List.copyOf(reached);
			// Frontier-split merge: union inner and frontier separately, subtract
			// inner from frontier (inner has priority in the dilation overlap),
			// so the frontier ring keeps its real depth and is never collapsed
			// into the inner blob.
			int[] rawDepths = new int[reachedDepths.size()];
			for (int i = 0; i < rawDepths.length; i++) {
				rawDepths[i] = reachedDepths.get(i);
			}
			return mergeCoplanarSplitFrontier(reached, rawDepths, radius);
		}

		// Standable tops of the clicked seed block (source block Y == oy). Keyed on
		// block Y so every top of that block is seeded, including fence tops above
		// oy+1.
		private List<CellSurface> collectSeedBlock() {
			ensureRows(ox, oz, oy, oy);
			List<WorldBox> column = index.get(new ColKey(ox, oz));
			if (column == null) {
				return List.of();
			}
			List<CellSurface> surfaces = new ArrayList<>();
			int count = column.size();
			for (int i = 0; i < count; i++) {
				WorldBox box = column.get(i);
				if (box.by() != oy) {
					continue;
				}
				for (StandableRect r : tops(box)) {
					surfaces.add(new CellSurface(r, ox, oz));
				}
			}
			return surfaces;
		}

		// Node surfaces of cell (cx,cz) whose top lies in [topLo,topHi] and whose
		// source block is within the node cube. Exposes only the rows that can hold
		// such tops (plus each box's occluder shell), so vertical work tracks the
		// flood front, not the band. exposeBox is memoized per box.
		private List<CellSurface> collect(int cx, int cz, double topLo, double topHi) {
			ensureRows(cx, cz, (int) Math.floor(topLo) - 1, (int) Math.floor(topHi) + 1);
			List<WorldBox> column = index.get(new ColKey(cx, cz));
			if (column == null) {
				return List.of();
			}
			List<CellSurface> surfaces = new ArrayList<>();
			// Index over an explicit count: tops() may append to this same column
			// (occluder rows in the box's own cell), and we must not revisit those.
			int count = column.size();
			for (int i = 0; i < count; i++) {
				WorldBox box = column.get(i);
				// Outside the cube's vertical band -> occluder only, never a node
				// (matches eager's yInWindow candidate test).
				if (Math.abs(box.by() - oy) > radius) {
					continue;
				}
				if (box.yMax() < topLo - EPS || box.yMax() > topHi + EPS) {
					continue;
				}
				for (StandableRect r : tops(box)) {
					surfaces.add(new CellSurface(r, cx, cz));
				}
			}
			return surfaces;
		}

		// Dilated, occluder-trimmed tops of a single box (memoized). Exposes the
		// box's occluder shell — the columns exposeBox scans, over the rows that can
		// hold a box intruding into the standing column (T, T+height] above this top —
		// before computing, so the headroom occlusion test sees the same occluders
		// eager would. The upper shell row is extended by floor(yMax+height)+1 (vs the
		// box's own top) so headroom occluders ABOVE the top are scanned, not just the
		// buried ones; height == 0 collapses it to row±1 (today's spans-above shell).
		private List<StandableRect> tops(WorldBox box) {
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

		// Query (and memoize) the collision boxes in column (cx,cz) for every block
		// row in [a,b] not yet scanned, clamped to the band [yLo,yHi]. Idempotent:
		// each (column,row) is queried at most once, so no duplicate boxes.
		private void ensureRows(int cx, int cz, int a, int b) {
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
				columnsExposed++;
			}
			List<WorldBox> column = index.get(key);
			for (int y = a; y <= b; y++) {
				int bit = y - yLo;
				if (bits.get(bit)) {
					continue;
				}
				bits.set(bit);
				rowsScanned++;
				cursor.set(cx, y, cz);
				BlockState state = level.getBlockState(cursor);
				VoxelShape shape = state.getCollisionShape(level, cursor, CollisionContext.empty());
				if (shape.isEmpty()) {
					continue;
				}
				double blockCollisionTop = y + shape.max(Direction.Axis.Y);
				double blockOutlineTop = visibleTop(level, cursor, state, blockCollisionTop, computeVisualTop);
				for (AABB box : shape.toAabbs()) {
					column.add(new WorldBox(cx, y, cz,
						cx + box.minX, cz + box.minZ, cx + box.maxX, cz + box.maxZ,
						y + box.minY, y + box.maxY,
						blockCollisionTop, blockOutlineTop));
				}
			}
		}
	}

	// Coverage oracle for PROFILE_FLOOD: true iff the two surface sets cover the
	// same area at every height, independent of how each is decomposed into rects
	// (greedy merge is not canonical, so an exact rect-list compare would false-
	// alarm). Group by topY, then per height compare per-x-slab Z-coverage.
	private static boolean coverageMatches(List<StandableRect> a, List<StandableRect> b) {
		Map<Long, List<StandableRect>> ga = groupByTop(a);
		Map<Long, List<StandableRect>> gb = groupByTop(b);
		Set<Long> heights = new HashSet<>(ga.keySet());
		heights.addAll(gb.keySet());
		for (Long h : heights) {
			if (!levelCoversSame(ga.getOrDefault(h, List.of()), gb.getOrDefault(h, List.of()))) {
				return false;
			}
		}
		return true;
	}

	private static Map<Long, List<StandableRect>> groupByTop(List<StandableRect> rects) {
		Map<Long, List<StandableRect>> groups = new HashMap<>();
		for (StandableRect r : rects) {
			groups.computeIfAbsent(Math.round(r.topY() * 1024.0), k -> new ArrayList<>()).add(r);
		}
		return groups;
	}

	// Two coplanar rect sets cover the same area iff, over every x-slab between
	// their combined x-breakpoints, they yield identical merged Z-intervals.
	private static boolean levelCoversSame(List<StandableRect> a, List<StandableRect> b) {
		double[] xs = new double[(a.size() + b.size()) * 2];
		int i = 0;
		for (StandableRect r : a) {
			xs[i++] = r.minX();
			xs[i++] = r.maxX();
		}
		for (StandableRect r : b) {
			xs[i++] = r.minX();
			xs[i++] = r.maxX();
		}
		Arrays.sort(xs);
		for (int s = 0; s + 1 < xs.length; s++) {
			double x0 = xs[s];
			double x1 = xs[s + 1];
			if (x1 - x0 <= EPS) {
				continue;
			}
			if (!intervalsEqual(zSpan(a, x0, x1), zSpan(b, x0, x1))) {
				return false;
			}
		}
		return true;
	}

	// Merged Z-intervals of the rects covering the slab [x0,x1].
	private static List<double[]> zSpan(List<StandableRect> rects, double x0, double x1) {
		List<double[]> iv = new ArrayList<>();
		for (StandableRect r : rects) {
			if (r.minX() <= x0 + EPS && r.maxX() >= x1 - EPS) {
				iv.add(new double[] {r.minZ(), r.maxZ()});
			}
		}
		iv.sort(Comparator.comparingDouble(z -> z[0]));
		List<double[]> merged = new ArrayList<>();
		for (double[] z : iv) {
			if (!merged.isEmpty() && z[0] <= merged.get(merged.size() - 1)[1] + EPS) {
				double[] last = merged.get(merged.size() - 1);
				last[1] = Math.max(last[1], z[1]);
			} else {
				merged.add(new double[] {z[0], z[1]});
			}
		}
		return merged;
	}

	private static boolean intervalsEqual(List<double[]> a, List<double[]> b) {
		if (a.size() != b.size()) {
			return false;
		}
		for (int i = 0; i < a.size(); i++) {
			if (Math.abs(a.get(i)[0] - b.get(i)[0]) > EPS || Math.abs(a.get(i)[1] - b.get(i)[1]) > EPS) {
				return false;
			}
		}
		return true;
	}
}
