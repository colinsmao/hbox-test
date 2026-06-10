package com.example.overlay.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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
 * <p><b>v3 model: enumerate &rarr; merge &rarr; flood (geometric adjacency).</b>
 * {@link #select} works in three phases:
 * <ol>
 * <li><b>Enumerate</b> the occlusion-aware exposed surfaces
 *     ({@link #exposedSurfaces}) of every block in a spatial window of half-extent
 *     {@code radius} blocks around the seed. A sub-box top counts only over the
 *     footprint where nothing solid sits directly above it (clipped by the same
 *     block's higher boxes and the block above), so e.g. a stair contributes its
 *     exposed L, not its buried back half.
 * <li><b>Merge</b> coplanar ({@code |dTopY| < EPS}) footprint-adjacent rects into
 *     maximal rectangles (greedy strip merge along X then Z), so a flat floor
 *     becomes one rect instead of a grid of unit cells (clean skirts, fewer quads).
 * <li><b>Flood</b> the merged-rect graph from the seed rect(s) by <b>geometric
 *     adjacency</b>: two rects are connected iff their footprints share an edge
 *     with positive overlap ({@link #footprintAdjacent}) and their heights are
 *     within the active {@link EntityProfile}'s symmetric {@code reach}. This one
 *     test subsumes the old same-block / own-column / 4-neighbor-column cases: a
 *     glass pane on a block connects to that block's exposed ring because their
 *     footprints abut at the hole edges, no special case needed.
 * </ol>
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
	// An axis-aligned XZ rectangle (world coords), used for the per-box footprint
	// clip in exposedSurfaces and as the mutable merge accumulator.
	private record Rect(double minX, double minZ, double maxX, double maxZ) {
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
	 * {@code radius} blocks, across footprint-adjacent height-gated steps.
	 */
	public void select(Level level, BlockPos start, int radius, EntityProfile profile) {
		double reach = profile.reach();
		BlockPos origin = start.immutable();

		// The seed's own exposed surfaces decide where the flood starts; if the
		// targeted block has no standable top, there is nothing to select.
		List<StandableRect> seedSurfaces = exposedSurfaces(level, origin);
		if (seedSurfaces.isEmpty()) {
			result = List.of();
			return;
		}

		// Phase 1: enumerate every exposed surface in the radius-block window.
		int minY = level.getMinY();
		int maxY = level.getMaxY();
		int y0 = Math.max(origin.getY() - radius, minY);
		int y1 = Math.min(origin.getY() + radius, maxY);
		List<StandableRect> all = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
			for (int z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
				for (int y = y0; y <= y1; y++) {
					cursor.set(x, y, z);
					all.addAll(exposedSurfaces(level, cursor));
				}
			}
		}

		// Phase 2: merge coplanar adjacent rects into maximal rectangles.
		List<StandableRect> merged = mergeCoplanar(all);

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

	// Two surfaces connect only if their rects share an edge with positive
	// overlap: they abut along X (one's maxX == the other's minX) with Z-overlap,
	// or along Z with X-overlap. This stops a partial-footprint surface (e.g. a
	// stair tread) from connecting on a side it does not physically touch.
	private static boolean footprintAdjacent(StandableRect a, StandableRect b) {
		double zOverlap = Math.min(a.maxZ(), b.maxZ()) - Math.max(a.minZ(), b.minZ());
		if (zOverlap > EPS
				&& (Math.abs(a.maxX() - b.minX()) < EPS || Math.abs(b.maxX() - a.minX()) < EPS)) {
			return true;
		}
		double xOverlap = Math.min(a.maxX(), b.maxX()) - Math.max(a.minX(), b.minX());
		return xOverlap > EPS
				&& (Math.abs(a.maxZ() - b.minZ()) < EPS || Math.abs(b.maxZ() - a.minZ()) < EPS);
	}

	// Merge coplanar (same topY) footprint-adjacent rects into maximal rects.
	// Group by topY, then within each group greedily merge abutting equal-span
	// strips along X then Z (repeated until stable), which collapses a grid of
	// unit cells back to whole rectangles. Greedy is not a minimal partition, but
	// any miss only costs an extra interior skirt, never reachability.
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
	 * Occlusion-aware standable surfaces of a block, in absolute world coords.
	 * Each collision sub-box's top is a candidate surface over its footprint; the
	 * footprint is clipped to where nothing solid sits <em>directly above</em> it,
	 * so only genuinely standable area remains (and only that area is drawn).
	 *
	 * <p>Occluders for a box top at height {@code h} are: same-block boxes that
	 * span {@code h} (sitting on or rising through it), and boxes of the block
	 * above shifted up by 1 (only relevant when {@code h == 1.0}). Headroom beyond
	 * the immediately-above cell is intentionally ignored (entity-height headroom
	 * is deferred).
	 */
	private static List<StandableRect> exposedSurfaces(Level level, BlockPos pos) {
		VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.empty());
		if (shape.isEmpty()) {
			return List.of();
		}
		List<AABB> boxes = shape.toAabbs();

		BlockPos above = pos.above();
		VoxelShape aboveShape = level.getBlockState(above).getCollisionShape(level, above, CollisionContext.empty());
		List<AABB> aboveBoxes = aboveShape.isEmpty() ? List.of() : aboveShape.toAabbs();

		double ox = pos.getX();
		double oz = pos.getZ();
		int py = pos.getY();

		List<StandableRect> out = new ArrayList<>();
		for (AABB box : boxes) {
			double h = box.maxY;
			Rect footprint = new Rect(box.minX, box.minZ, box.maxX, box.maxZ);

			List<Rect> occluders = new ArrayList<>();
			for (AABB other : boxes) {
				if (other == box) {
					continue;
				}
				// A box that occupies the slab just above h (sits on it or rises
				// through it) hides that part of the top.
				if (other.minY <= h + EPS && h < other.maxY - EPS) {
					occluders.add(new Rect(other.minX, other.minZ, other.maxX, other.maxZ));
				}
			}
			for (AABB a : aboveBoxes) {
				if (a.minY + 1.0 <= h + EPS && h < a.maxY + 1.0 - EPS) {
					occluders.add(new Rect(a.minX, a.minZ, a.maxX, a.maxZ));
				}
			}

			double topY = py + h;
			for (Rect exposed : subtractRects(footprint, occluders)) {
				out.add(new StandableRect(
					ox + exposed.minX(),
					oz + exposed.minZ(),
					ox + exposed.maxX(),
					oz + exposed.maxZ(),
					topY));
			}
		}
		return out;
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
