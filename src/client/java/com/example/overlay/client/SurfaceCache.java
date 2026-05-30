package com.example.overlay.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The brush selection set and the compute-cache in one structure: a
 * {@code BlockPos -> {BlockState, List<StandableRect>}} map. Brushing inserts;
 * every entry's rectangles are the draw set. In-memory only, not persisted.
 *
 * <p>Not thread-safe by design. It is mutated only on the client/extraction
 * thread ({@code add}/{@code pruneStale}/{@code clear}); the render thread never
 * touches it — the overlay publishes an immutable {@link #allRects()} snapshot
 * into a {@code volatile} field for {@code emit} to read.
 */
public final class SurfaceCache {
	private record Entry(BlockState state, List<StandableRect> rects) {
	}

	// LinkedHashMap for deterministic iteration (stable draw order).
	private final Map<BlockPos, Entry> entries = new LinkedHashMap<>();

	/**
	 * Add a resolved block to the selection, computing its rectangles once. A
	 * block already present with the same {@link BlockState} is left untouched, so
	 * a sweeping crosshair accumulates entries cheaply. Blocks whose collision
	 * shape is empty are not stored.
	 */
	public void add(Level level, BlockPos pos) {
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
			entries.put(key, new Entry(state, rects));
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
				mapEntry.setValue(new Entry(current, rects));
			}
		}
	}

	public void clear() {
		entries.clear();
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	/** Immutable snapshot of every selected block's rectangles, concatenated. */
	public List<StandableRect> allRects() {
		if (entries.isEmpty()) {
			return List.of();
		}

		List<StandableRect> all = new ArrayList<>();
		for (Entry entry : entries.values()) {
			all.addAll(entry.rects());
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
