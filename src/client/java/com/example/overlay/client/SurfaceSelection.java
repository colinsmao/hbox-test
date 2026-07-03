package com.example.overlay.client;

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

import com.example.overlay.OverlayMod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
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
	// Package-private for unit tests (synthetic boxes feed the classifier/headroom).
	record WorldBox(int bx, int by, int bz,
			double minX, double minZ, double maxX, double maxZ, double yMin, double yMax) {
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
	 */
	public void select(Level level, BlockPos start, int radius, EntityProfile profile) {
		if (!PROFILE_FLOOD) {
			result = LAZY ? selectLazy(level, start, radius, profile)
					: selectEager(level, start, radius, profile);
			occluders = computeOccluders(level, result, profile);
			return;
		}

		long t0 = System.nanoTime();
		List<StandableRect> eager = selectEager(level, start, radius, profile);
		long t1 = System.nanoTime();
		LazyFlood lazy = new LazyFlood(level, start, radius, profile);
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
		OverlayMod.LOGGER.info(
			"[flood] profile={} radius={} | eager {}us scan~{}cols ~{}rows {}rects | lazy {}us {}cols {}rows {}rects | match={}",
			profile.name(), radius,
			(t1 - t0) / 1000, eagerSide * eagerSide, eagerRows, eager.size(),
			(t2 - t1) / 1000, lazy.columnsExposed(), lazy.rowsScanned(), lazyRects.size(), match);
		if (!match) {
			OverlayMod.LOGGER.warn("[flood] COVERAGE MISMATCH lazy != eager (profile={} radius={})",
				profile.name(), radius);
		}
		result = LAZY ? lazyRects : eager;
		occluders = computeOccluders(level, result, profile);
	}

	// The eager, window-driven flood (the verified Stage 4 path; kept as the lazy
	// path's correctness oracle). Enumerates every collision box in the
	// window+margin cube, dilates all, merges, then floods the merged graph.
	private List<StandableRect> selectEager(Level level, BlockPos start, int radius, EntityProfile profile) {
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
					VoxelShape shape = level.getBlockState(cursor).getCollisionShape(level, cursor, CollisionContext.empty());
					if (shape.isEmpty()) {
						continue;
					}
					boolean yInWindow = y >= oy - radius && y <= oy + radius;
					for (AABB box : shape.toAabbs()) {
						WorldBox wb = new WorldBox(x, y, z,
							x + box.minX, z + box.minZ, x + box.maxX, z + box.maxZ,
							y + box.minY, y + box.maxY);
						if (column == null) {
							column = index.computeIfAbsent(new ColKey(x, z), k -> new ArrayList<>());
						}
						column.add(wb);
						if (colInWindow && yInWindow) {
							candidates.add(wb);
						}
						if (originCol && yInWindow) {
							seedBoxes.add(wb);
						}
					}
				}
			}
		}

		// The seed's own dilated tops decide where the flood starts; if the targeted
		// column has no standable top (e.g. a wide entity buried beside a wall),
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
	private List<StandableRect> selectLazy(Level level, BlockPos start, int radius, EntityProfile profile) {
		return new LazyFlood(level, start, radius, profile).run();
	}

	public void clear() {
		result = List.of();
		occluders = List.of();
	}

	/** Immutable snapshot of the reached surfaces (height-tinted at draw time). */
	public List<StandableRect> allRects() {
		return result;
	}

	/** Immutable snapshot of the upward (occluder) skirt spans for the reached set. */
	public List<OccluderSpan> allOccluders() {
		return occluders;
	}

	// BFS over merged rects: an edge exists iff footprints share an edge with
	// positive overlap and the height difference is within reach (a single
	// threshold). Seeds are the merged rects that cover a seed surface.
	private static List<StandableRect> flood(List<StandableRect> rects, List<StandableRect> seeds, double reach) {
		int n = rects.size();
		boolean[] visited = new boolean[n];
		Deque<Integer> queue = new ArrayDeque<>();
		for (int i = 0; i < n; i++) {
			if (coversAnySeed(rects.get(i), seeds)) {
				visited[i] = true;
				queue.addLast(i);
			}
		}

		List<StandableRect> out = new ArrayList<>();
		while (!queue.isEmpty()) {
			int i = queue.pollFirst();
			StandableRect cur = rects.get(i);
			out.add(cur);
			for (int j = 0; j < n; j++) {
				if (visited[j]) {
					continue;
				}
				StandableRect other = rects.get(j);
				if (Math.abs(other.topY() - cur.topY()) <= reach + EPS && footprintAdjacent(cur, other)) {
					visited[j] = true;
					queue.addLast(j);
				}
			}
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
			for (StandableRect r : sorted.subList(i, j)) {
				rects.add(new Rect(r.minX(), r.minZ(), r.maxX(), r.maxZ()));
			}
			rects = union(rects);
			int before;
			do {
				before = rects.size();
				rects = mergeAlong(rects, true);
				rects = mergeAlong(rects, false);
			} while (rects.size() < before);

			for (Rect r : rects) {
				out.add(new StandableRect(r.minX(), r.minZ(), r.maxX(), r.maxZ(), topY));
			}
			i = j;
		}
		return out;
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
			out.add(new StandableRect(exposed.minX(), exposed.minZ(), exposed.maxX(), exposed.maxZ(), topY));
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
					out.add(new OccluderSpan(false, true, r.maxX(), zLo, zHi, topY, top));
				}
				if (Math.abs(oMaxX - r.minX()) < EPS) {
					out.add(new OccluderSpan(false, false, r.minX(), zLo, zHi, topY, top));
				}
			}
			double xLo = Math.max(oMinX, r.minX());
			double xHi = Math.min(oMaxX, r.maxX());
			if (xHi - xLo > EPS) {
				if (Math.abs(oMinZ - r.maxZ()) < EPS) {
					out.add(new OccluderSpan(true, true, r.maxZ(), xLo, xHi, topY, top));
				}
				if (Math.abs(oMaxZ - r.minZ()) < EPS) {
					out.add(new OccluderSpan(true, false, r.minZ(), xLo, xHi, topY, top));
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
			for (int i = 1; i < group.size(); i++) {
				OccluderSpan s = group.get(i);
				if (s.lo() <= hi + EPS) {
					hi = Math.max(hi, s.hi());
					top = Math.max(top, s.topY());
				} else {
					out.add(new OccluderSpan(head.alongX(), head.positiveSide(),
						head.line(), lo, hi, head.baseY(), top));
					lo = s.lo();
					hi = s.hi();
					top = s.topY();
				}
			}
			out.add(new OccluderSpan(head.alongX(), head.positiveSide(),
				head.line(), lo, hi, head.baseY(), top));
		}
		return out;
	}

	// --- Milestone 5: drop-edge hole classification (pure) ---

	// The three classes a drop sub-span falls into. CUTOFF: the edge sits in the
	// radius grey ring, where the selection is incomplete — never a hole/warning.
	// HOLE: a mob leaving the edge is trapped (falls into the void, or onto a
	// topmost landing that is not in the reached set). BENIGN: it lands on a reached
	// surface (however deep / roundabout); the fall distance splits minor from tall
	// in Step 5.
	enum DropClass {
		CUTOFF, HOLE, BENIGN
	}

	// A drop sub-span's classification plus its fall distance (T - landing top),
	// meaningful only for BENIGN (0 otherwise).
	record DropClassification(DropClass kind, double fallDistance) {
	}

	/**
	 * Classify one drop sub-span of a surface edge. <b>Pure:</b> the caller
	 * pre-gathers {@code boxesBelow} (every collision box below the fall footprint,
	 * down to the world floor), mirroring how {@link #occluderSpansForRect} takes
	 * pre-gathered candidate boxes — the downward world scan is the caller's job
	 * (Step 3), so this stays unit-testable with synthetic boxes.
	 *
	 * <p>A mob leaving the edge falls onto the <b>topmost</b> collision box strictly
	 * below the surface top {@code topY} that overlaps {@code fallFootprint} (down is
	 * free; escapability is decided against the whole reached set, not a local reach
	 * probe). That topmost landing decides:
	 * <ul>
	 * <li>no landing at all (void before the world floor) &rarr; {@link DropClass#HOLE};
	 * <li>landing not in the reached set &rarr; {@link DropClass#HOLE} (a trap — this is
	 *     why the <b>topmost</b> landing decides: an unescapable ledge sitting above a
	 *     reached floor is a hole, since the mob is stuck on the ledge);
	 * <li>landing in the reached set &rarr; {@link DropClass#BENIGN}, fall distance
	 *     {@code topY - landing.yMax} (however deep, incl. roundabout escapes the flood
	 *     already encodes).
	 * </ul>
	 * A span whose edge lies in the radius grey ring
	 * ({@code |edgeLine - perpCenter| >= ringStart}) is {@link DropClass#CUTOFF}
	 * regardless: the selection is incomplete there, so it is never a hole/warning
	 * (raising the radius until the real landing is reached resolves it). {@code
	 * perpCenter} is the seed-center coordinate perpendicular to the edge (Z for an
	 * X-running edge, else X); {@code ringStart} is the inner edge of the grey band
	 * ({@code ringEnd - 1}), as {@code publish} derives it.
	 */
	static DropClassification classifyDrop(Rect fallFootprint, double topY,
			double edgeLine, double perpCenter, double ringStart,
			List<WorldBox> boxesBelow, List<StandableRect> reached) {
		if (Math.abs(edgeLine - perpCenter) >= ringStart - EPS) {
			return new DropClassification(DropClass.CUTOFF, 0.0);
		}
		WorldBox landing = null;
		for (WorldBox b : boxesBelow) {
			// Strictly below the surface we are leaving, and under the fall spot.
			if (b.yMax() >= topY - EPS || !overlapsXZ(fallFootprint, b)) {
				continue;
			}
			if (landing == null || b.yMax() > landing.yMax()) {
				landing = b;
			}
		}
		if (landing == null) {
			return new DropClassification(DropClass.HOLE, 0.0);
		}
		double landY = landing.yMax();
		if (reachedCovers(reached, fallFootprint, landY)) {
			return new DropClassification(DropClass.BENIGN, topY - landY);
		}
		return new DropClassification(DropClass.HOLE, 0.0);
	}

	// True iff the box footprint overlaps the fall footprint with positive area.
	private static boolean overlapsXZ(Rect fp, WorldBox b) {
		return Math.min(fp.maxX(), b.maxX()) - Math.max(fp.minX(), b.minX()) > EPS
			&& Math.min(fp.maxZ(), b.maxZ()) - Math.max(fp.minZ(), b.minZ()) > EPS;
	}

	// True iff some reached surface coplanar with height landY covers the fall
	// footprint with positive area — i.e. the topmost landing is in the reached set.
	private static boolean reachedCovers(List<StandableRect> reached, Rect fp, double landY) {
		for (StandableRect r : reached) {
			if (Math.abs(r.topY() - landY) <= EPS
					&& Math.min(r.maxX(), fp.maxX()) - Math.max(r.minX(), fp.minX()) > EPS
					&& Math.min(r.maxZ(), fp.maxZ()) - Math.max(r.minZ(), fp.minZ()) > EPS) {
				return true;
			}
		}
		return false;
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
	 * <p>Bounded by the same 3-D cube as eager: a box is a node iff its source
	 * block is within Chebyshev {@code radius} in X/Z <b>and</b> {@code |by-oy| <=
	 * radius}. The merge/union runs <b>after</b> the flood on the reached set only
	 * (area-preserving, so connectivity is unchanged — {@code footprintAdjacent}
	 * already treats overlap as connected). Result is set-equal to eager.
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

		LazyFlood(Level level, BlockPos start, int radius, EntityProfile profile) {
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
			this.yLo = Math.max(oy - radius - 1, level.getMinY());
			this.yHi = Math.min(oy + radius + 1, level.getMaxY());
		}

		int columnsExposed() {
			return columnsExposed;
		}

		int rowsScanned() {
			return rowsScanned;
		}

		List<StandableRect> run() {
			// Seeds: every standable top in the origin column within the node cube
			// (matches eager's origin-column candidates) — the only place the full
			// vertical band is exposed, and it is a single column.
			List<CellSurface> seeds = collect(ox, oz, oy - radius, oy + radius + 1);
			if (seeds.isEmpty()) {
				return List.of();
			}
			Set<CellSurface> visited = new HashSet<>(seeds);
			Deque<CellSurface> queue = new ArrayDeque<>(seeds);
			List<StandableRect> reached = new ArrayList<>();
			while (!queue.isEmpty()) {
				CellSurface s = queue.pollFirst();
				reached.add(s.rect());
				double h = s.rect().topY();
				for (int cx = s.cx() - neighbour; cx <= s.cx() + neighbour; cx++) {
					if (Math.abs(cx - ox) > radius) {
						continue;
					}
					for (int cz = s.cz() - neighbour; cz <= s.cz() + neighbour; cz++) {
						if (Math.abs(cz - oz) > radius) {
							continue;
						}
						// Only tops within a single step of h can connect, so scan
						// just that height window of the neighbour column.
						for (CellSurface t : collect(cx, cz, h - reach, h + reach)) {
							if (visited.contains(t)) {
								continue;
							}
							if (footprintAdjacent(s.rect(), t.rect())) {
								visited.add(t);
								queue.addLast(t);
							}
						}
					}
				}
			}
			// Merge/union the reached set for rendering (post-flood; area-preserving).
			return mergeCoplanar(reached);
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
				VoxelShape shape = level.getBlockState(cursor).getCollisionShape(level, cursor, CollisionContext.empty());
				if (shape.isEmpty()) {
					continue;
				}
				for (AABB box : shape.toAabbs()) {
					column.add(new WorldBox(cx, y, cz,
						cx + box.minX, cz + box.minZ, cx + box.maxX, cz + box.maxZ,
						y + box.minY, y + box.maxY));
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
