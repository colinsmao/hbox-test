package com.example.overlay.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
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
 * <p>Not thread-safe by design. It is mutated only on the client thread
 * ({@code select}/{@code add}/{@code pruneStale}/{@code clear}); the render
 * thread never touches it — the overlay publishes an immutable
 * {@link #allRects()} snapshot into a {@code volatile} field for {@code emit}
 * to read.
 */
public final class SurfaceCache {
	// distance = BFS ring distance from the selection seed (seed = 0). Carried so
	// the overlay can tint surfaces by connectivity distance (v1.5 debug aid).
	private record Entry(BlockState state, List<StandableRect> rects, int distance) {
	}

	/** A standable rectangle tagged with its BFS distance from the seed. */
	public record DistancedRect(StandableRect rect, int distance) {
	}

	// LinkedHashMap for deterministic iteration (stable draw order).
	private final Map<BlockPos, Entry> entries = new LinkedHashMap<>();

	// The 4 horizontal directions the flood expands over (same Y). v2 keeps this
	// set but tightens the per-edge acceptance test (see select / isStandable).
	private static final Direction[] HORIZONTAL_NEIGHBORS = {
		Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
	};

	/**
	 * Replace the selection with a breadth-first flood from {@code start} over
	 * horizontally-adjacent blocks (4-connected, same Y) that have a non-empty
	 * collision shape, out to a graph distance of {@code radius}. The seed is
	 * distance 0, so {@code radius <= 0} selects only the seed.
	 *
	 * <p>Standard BFS: a work queue, a {@code visited} set so each block is
	 * processed at most once, and ring-by-ring depth tracking so the search
	 * terminates at {@code radius}. v2 will keep this traversal unchanged and
	 * only replace the neighbor-acceptance test ({@link #isStandable}) with a
	 * standable-surface reachability check (max height difference).
	 */
	public void select(Level level, BlockPos start, int radius) {
		clear();
		BlockPos origin = start.immutable();
		add(level, origin, 0);

		Set<BlockPos> visited = new HashSet<>();
		visited.add(origin);
		Deque<BlockPos> frontier = new ArrayDeque<>();
		frontier.add(origin);

		for (int depth = 0; depth < radius && !frontier.isEmpty(); depth++) {
			for (int ring = frontier.size(); ring > 0; ring--) {
				BlockPos current = frontier.poll();
				for (Direction dir : HORIZONTAL_NEIGHBORS) {
					BlockPos next = current.relative(dir);
					if (!visited.add(next)) {
						continue;
					}
					if (isStandable(level, next)) {
						add(level, next, depth + 1);
						frontier.add(next);
					}
				}
			}
		}
	}

	// v1b acceptance test: any block with a non-empty collision shape at this
	// exact Y. v2 widens this to a reachability check across standable surfaces.
	private static boolean isStandable(Level level, BlockPos pos) {
		return !level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.empty()).isEmpty();
	}

	/**
	 * Add a resolved block to the selection at the given BFS {@code distance},
	 * computing its rectangles once. A block already present with the same
	 * {@link BlockState} is left untouched (BFS reaches each block once, at its
	 * shortest distance). Blocks whose collision shape is empty are not stored.
	 */
	public void add(Level level, BlockPos pos, int distance) {
		BlockPos key = pos.immutable();
		BlockState state = level.getBlockState(key);

		Entry existing = entries.get(key);
		if (existing != null && existing.state().equals(state)) {
			return;
		}

		List<StandableRect> rects = computeRects(level, key, state);
		if (rects.isEmpty()) {
			entries.remove(key);
		} else {
			entries.put(key, new Entry(state, rects, distance));
		}
	}

	/**
	 * Drop or recompute entries whose stored {@link BlockState} no longer matches
	 * the world (place/break), so the painted surfaces stay accurate. Per-entry
	 * block-state lookup; fine for now, can be throttled later.
	 */
	public void pruneStale(Level level) {
		Iterator<Map.Entry<BlockPos, Entry>> it = entries.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<BlockPos, Entry> mapEntry = it.next();
			BlockPos pos = mapEntry.getKey();
			BlockState current = level.getBlockState(pos);
			if (current.equals(mapEntry.getValue().state())) {
				continue;
			}

			List<StandableRect> rects = computeRects(level, pos, current);
			if (rects.isEmpty()) {
				it.remove();
			} else {
				mapEntry.setValue(new Entry(current, rects, mapEntry.getValue().distance()));
			}
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
	 * tagged with its owning block's BFS distance for distance-based coloring.
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

	// Each collision sub-box's top (maxY) over its [minX,maxX] x [minZ,maxZ]
	// footprint is one standable rectangle, in absolute world coords.
	private static List<StandableRect> computeRects(Level level, BlockPos pos, BlockState state) {
		VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());
		if (shape.isEmpty()) {
			return List.of();
		}

		List<AABB> boxes = shape.toAabbs();
		List<StandableRect> rects = new ArrayList<>(boxes.size());
		for (AABB box : boxes) {
			rects.add(new StandableRect(
				pos.getX() + box.minX,
				pos.getZ() + box.minZ,
				pos.getX() + box.maxX,
				pos.getZ() + box.maxZ,
				pos.getY() + box.maxY));
		}
		return rects;
	}
}
