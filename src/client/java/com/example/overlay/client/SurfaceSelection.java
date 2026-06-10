package com.example.overlay.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *     in a window of half-extent {@code radius} blocks (plus a {@code ceil(W)}
 *     occluder margin so every box that can trim an edge candidate is captured —
 *     candidate and occluder each grow by {@code W/2}, so a box up to {@code W}
 *     blocks past the window still eats in), grow each box's
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
 *     within the active {@link EntityProfile}'s symmetric {@code reach}. This one
 *     test subsumes the old same-block / own-column / 4-neighbor-column cases: a
 *     glass pane on a block connects to that block's exposed ring because their
 *     footprints abut at the hole edges, no special case needed. A dilated perch
 *     over the void floods directly because adjacency is geometric (no column).
 * </ol>
 *
 * <p>For a gap of width {@code g} flanked by support, each side grows {@code W/2},
 * leaving {@code g - W} uncovered: {@code g <= W} bridges, {@code g > W} leaves a
 * hole — "can't fall into a hole smaller than yourself". (This stage shows only
 * the <em>geometry</em>; fall/unreturnable semantics are a later milestone.)
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
	// exposeBox and the mutable merge accumulator.
	private record Rect(double minX, double minZ, double maxX, double maxZ) {
	}

	// One collision sub-box in absolute world coords: its (undilated) XZ footprint
	// plus its vertical extent. The arrangement dilates the footprint by W/2 on
	// demand; yMin/yMax drive the spans-above occlusion test.
	private record WorldBox(double minX, double minZ, double maxX, double maxZ, double yMin, double yMax) {
	}

	// Column index key (block X/Z) for the per-column box lookup used to bound the
	// occluder search to the candidate's immediate neighborhood.
	private record ColKey(int x, int z) {
	}

	// Grouping key for the merge: two doubles quantized to 1/1024 of a block
	// (finer than any collision-box edge, incl. dilated 0.3 / 0.975 later) so
	// equal spans hash together despite float noise.
	private record SpanKey(long a, long b) {
	}

	// Tolerance for the double coordinate compares (box edges are multiples of
	// 1/16). Used to drop subtraction slivers and to test edge adjacency/overlap.
	private static final double EPS = 1.0e-6;

	// The reached, merged surfaces from the last select (the draw set). Replaced
	// wholesale each select; an immutable snapshot is published by the overlay.
	private List<StandableRect> result = List.of();

	/**
	 * Replace the selection with the merged standable surfaces reachable from the
	 * surfaces of {@code start}, within a spatial window of half-extent
	 * {@code radius} blocks, for the entity's width/reach, across
	 * footprint-adjacent height-gated steps.
	 */
	public void select(Level level, BlockPos start, int radius, EntityProfile profile) {
		double reach = profile.reach();
		double halfW = profile.width() / 2.0;
		// Occluder margin: gather boxes this many blocks BEYOND the window so an
		// outer-ring candidate is trimmed by every box that can eat its dilated top.
		// Both the candidate top and an occluder grow by W/2, so their footprints
		// overlap whenever the undilated gap is < W — i.e. a box up to ceil(W) blocks
		// past the window still trims. ceil(W/2) misses the far half for wide
		// entities (a Ravager wall 2 columns out is never gathered), so use ceil(W).
		int margin = (int) Math.ceil(profile.width());
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
						WorldBox wb = new WorldBox(
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
			exposeBox(seed, index, halfW, seedSurfaces);
		}
		if (seedSurfaces.isEmpty()) {
			result = List.of();
			return;
		}

		// Phase 2: build the dilated arrangement, then merge coplanar adjacent rects.
		List<StandableRect> arrangement = new ArrayList<>();
		for (WorldBox candidate : candidates) {
			exposeBox(candidate, index, halfW, arrangement);
		}
		List<StandableRect> merged = mergeCoplanar(arrangement);

		// Phase 3: flood the merged graph from the rect(s) covering a seed surface.
		result = flood(merged, seedSurfaces, reach);
	}

	public void clear() {
		result = List.of();
	}

	/** Immutable snapshot of the reached surfaces (height-tinted at draw time). */
	public List<StandableRect> allRects() {
		return result;
	}

	// BFS over merged rects: an edge exists iff footprints share an edge with
	// positive overlap and the height difference is within reach (a single
	// symmetric threshold). Seeds are the merged rects that cover a seed surface.
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
	private static boolean footprintAdjacent(StandableRect a, StandableRect b) {
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
	private static List<StandableRect> mergeCoplanar(List<StandableRect> input) {
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
	private static List<Rect> union(List<Rect> rects) {
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
	 * side. It is then cut by every dilated box that <em>spans immediately above</em>
	 * the top ({@code yMin <= T < yMax}, {@code T = target.maxY}) — the one
	 * occlusion rule that both buries a lower top under a taller neighbor and (via
	 * that neighbor's own call) supplies the higher surface. Occluder search is
	 * bounded to the columns the dilated footprint can reach (dilation is
	 * {@code < 1} block), via the per-column {@code index}. {@code halfW == 0}
	 * leaves neighbor footprints merely abutting (zero overlap), so Point matches
	 * the undilated per-block result.
	 */
	private static void exposeBox(WorldBox target, Map<ColKey, List<WorldBox>> index, double halfW,
			List<StandableRect> out) {
		double topY = target.yMax();
		Rect base = new Rect(
			target.minX() - halfW, target.minZ() - halfW,
			target.maxX() + halfW, target.maxZ() + halfW);

		List<Rect> occluders = new ArrayList<>();
		int cxLo = (int) Math.floor(base.minX()) - 1;
		int cxHi = (int) Math.floor(base.maxX()) + 1;
		int czLo = (int) Math.floor(base.minZ()) - 1;
		int czHi = (int) Math.floor(base.maxZ()) + 1;
		for (int cx = cxLo; cx <= cxHi; cx++) {
			for (int cz = czLo; cz <= czHi; cz++) {
				List<WorldBox> column = index.get(new ColKey(cx, cz));
				if (column == null) {
					continue;
				}
				for (WorldBox other : column) {
					if (other == target) {
						continue;
					}
					if (other.yMin() <= topY + EPS && topY < other.yMax() - EPS) {
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

	// Subtract every occluder from base, returning the remaining 0..N rectangles
	// (guillotine subtraction: each cut splits a piece into up to 4 leftovers).
	private static List<Rect> subtractRects(Rect base, List<Rect> occluders) {
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
