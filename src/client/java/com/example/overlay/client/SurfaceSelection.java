package com.example.overlay.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The selection set and the compute-cache in one structure: a
 * {@code BlockPos -> {BlockState, List<StandableRect>}} map. {@link #select}
 * (re)populates it from a right-click; every entry's rectangles are the draw
 * set. In-memory only, not persisted.
 *
 * <p><b>v2 model: surface-indexed walkable flood.</b> The unit of the flood is a
 * single standable <em>surface</em> ({@link StandableRect}), not a block, so a
 * block can contribute several nodes (a stair is a tread + a top) and stacked
 * surfaces (spiral staircases, overhangs) stay distinct. Surfaces are
 * <em>occlusion-aware</em> ({@link #exposedSurfaces}): a sub-box top counts only
 * over the footprint where nothing solid sits directly above it, clipped by the
 * same block's higher boxes and by the block above. Edges are
 * <em>footprint-aware</em> ({@link #footprintAdjacent}) and height-gated by the
 * active {@link EntityProfile}'s symmetric {@code reach}, which together kill the
 * stair-back "ghost step".
 * Candidates come from the same block (siblings, free), the 4 horizontal
 * neighbor columns, and the surface's <em>own</em> column (so a partial-footprint
 * block like a glass pane connects to the block it sits on, whose top has a
 * matching hole). Storage stays block-keyed: reaching any surface stores the
 * whole block's exposed rects (its draw set).
 *
 * <p>Not thread-safe by design. It is mutated only on the client thread
 * ({@code select}/{@code add}/{@code clear}); the render thread never touches it
 * — the overlay publishes an immutable {@link #allRects()} snapshot into a
 * {@code volatile} field for {@code emit} to read.
 */
public final class SurfaceSelection {
	// distance = flood block-transition distance from the seed (seed = 0). Carried
	// so the overlay can tint surfaces by connectivity distance (v1.5 debug aid).
	private record Entry(BlockState state, List<StandableRect> rects, int distance) {
	}

	/** A standable rectangle tagged with its flood distance from the seed. */
	public record DistancedRect(StandableRect rect, int distance) {
	}

	// A surface plus its owning block: the flood's node. visited keys on the rect
	// alone (rects are globally unique by world coords); pos is needed to add the
	// owning block and to tell siblings (same pos, free) from neighbors (+1).
	private record Node(BlockPos pos, StandableRect rect, int depth) {
	}

	// A flood candidate: an exposed surface and the column block that owns it.
	private record Surface(BlockPos pos, StandableRect rect) {
	}

	// An axis-aligned XZ rectangle (world coords), used only for the per-box
	// footprint clip in exposedSurfaces.
	private record Rect(double minX, double minZ, double maxX, double maxZ) {
	}

	// LinkedHashMap for deterministic iteration (stable draw order).
	private final Map<BlockPos, Entry> entries = new LinkedHashMap<>();

	// The 4 horizontal neighbor columns the flood expands across.
	private static final Direction[] HORIZONTAL_NEIGHBORS = {
		Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
	};

	// Tolerance for the double coordinate compares (box edges are multiples of
	// 1/16). Used to drop subtraction slivers and to test edge adjacency/overlap.
	private static final double EPS = 1.0e-6;

	/**
	 * Replace the selection with a breadth-first walkable flood from the surfaces
	 * of {@code start}, following standable terrain across footprint-adjacent,
	 * height-gated steps out to a graph distance of {@code radius} block
	 * transitions. Seed surfaces are distance 0.
	 *
	 * <p>0-1 BFS over surfaces: a same-block sibling step is free (weight 0,
	 * pushed to the deque front), a neighbor-column step costs one transition
	 * (weight 1, pushed to the back). Finalizing each surface on its first pop
	 * gives the shortest block-distance, so neighbor expansion (gated by
	 * {@code depth < radius}) and the stored per-block distance both use the
	 * shortest path.
	 */
	public void select(Level level, BlockPos start, int radius, EntityProfile profile) {
		clear();
		BlockPos origin = start.immutable();
		double reach = profile.reach();

		// Per-select memo so each block's exposed surfaces are computed once; the
		// rect instances are shared, so reference and value identity agree.
		Map<BlockPos, List<StandableRect>> memo = new HashMap<>();
		List<StandableRect> seed = surfacesOf(level, origin, memo);
		if (seed.isEmpty()) {
			return;
		}

		Set<StandableRect> done = new HashSet<>();
		Deque<Node> deque = new ArrayDeque<>();
		for (StandableRect rect : seed) {
			deque.addLast(new Node(origin, rect, 0));
		}

		while (!deque.isEmpty()) {
			Node node = deque.pollFirst();
			if (!done.add(node.rect())) {
				continue;
			}
			add(level, node.pos(), node.depth());

			double t = node.rect().topY();

			// Same-block siblings: free (weight 0), so the flood can hop between a
			// block's own surfaces (stair tread <-> top) without spending a step.
			for (StandableRect sib : surfacesOf(level, node.pos(), memo)) {
				if (sib == node.rect() || done.contains(sib)) {
					continue;
				}
				if (Math.abs(sib.topY() - t) <= reach + EPS && footprintAdjacent(node.rect(), sib)) {
					deque.addFirst(new Node(node.pos(), sib, node.depth()));
				}
			}

			// Neighbor steps cost one transition (weight 1), only while there is
			// radius budget left.
			if (node.depth() < radius) {
				// Own column, but a different block: a partial-footprint block (glass
				// pane, fence, wall) leaves a matching hole in the top of the block it
				// sits on, so that block's floor abuts the partial block's footprint at
				// the hole edges and the two should connect (vertically, same column).
				// Same-block surfaces are siblings, handled (free) above.
				for (Surface cand : collectColumn(level, node.pos(), t, reach, memo)) {
					if (cand.pos().equals(node.pos()) || done.contains(cand.rect())) {
						continue;
					}
					if (footprintAdjacent(node.rect(), cand.rect())) {
						deque.addLast(new Node(cand.pos(), cand.rect(), node.depth() + 1));
					}
				}
				// The 4 horizontal neighbor columns.
				for (Direction dir : HORIZONTAL_NEIGHBORS) {
					BlockPos column = node.pos().relative(dir);
					for (Surface cand : collectColumn(level, column, t, reach, memo)) {
						if (done.contains(cand.rect())) {
							continue;
						}
						if (footprintAdjacent(node.rect(), cand.rect())) {
							deque.addLast(new Node(cand.pos(), cand.rect(), node.depth() + 1));
						}
					}
				}
			}
		}
	}

	// Exposed surfaces of one column block whose top is within reach of T,
	// scanned over a bounded vertical window (clamped at world min-Y).
	private static List<Surface> collectColumn(Level level, BlockPos column, double t, double reach,
			Map<BlockPos, List<StandableRect>> memo) {
		List<Surface> out = new ArrayList<>();
		int from = Math.max((int) Math.floor(t - reach) - 1, level.getMinY());
		int to = (int) Math.floor(t + reach);
		for (int y = from; y <= to; y++) {
			BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
			for (StandableRect rect : surfacesOf(level, pos, memo)) {
				if (Math.abs(rect.topY() - t) <= reach + EPS) {
					out.add(new Surface(pos, rect));
				}
			}
		}
		return out;
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

	// Memoized exposed-surface lookup for the flood.
	private static List<StandableRect> surfacesOf(Level level, BlockPos pos,
			Map<BlockPos, List<StandableRect>> memo) {
		BlockPos key = pos.immutable();
		List<StandableRect> cached = memo.get(key);
		if (cached != null) {
			return cached;
		}
		List<StandableRect> surfaces = exposedSurfaces(level, key);
		memo.put(key, surfaces);
		return surfaces;
	}

	/**
	 * Add a resolved block to the selection at the given flood {@code distance},
	 * computing its exposed surfaces once. A block already present with the same
	 * {@link BlockState} is left untouched (BFS reaches each block once, at its
	 * shortest distance). Blocks with no exposed surface are not stored.
	 */
	public void add(Level level, BlockPos pos, int distance) {
		BlockPos key = pos.immutable();
		BlockState state = level.getBlockState(key);

		Entry existing = entries.get(key);
		if (existing != null && existing.state().equals(state)) {
			return;
		}

		List<StandableRect> rects = exposedSurfaces(level, key);
		if (rects.isEmpty()) {
			entries.remove(key);
		} else {
			entries.put(key, new Entry(state, rects, distance));
		}
	}

	public void clear() {
		entries.clear();
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	/**
	 * Immutable snapshot of every selected block's rectangles, concatenated, each
	 * tagged with its owning block's flood distance for distance-based coloring.
	 */
	public List<DistancedRect> allRects() {
		if (entries.isEmpty()) {
			return List.of();
		}

		List<DistancedRect> all = new ArrayList<>();
		for (Entry entry : entries.values()) {
			for (StandableRect rect : entry.rects()) {
				all.add(new DistancedRect(rect, entry.distance()));
			}
		}
		return all;
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
