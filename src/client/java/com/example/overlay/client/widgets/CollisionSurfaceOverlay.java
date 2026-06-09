package com.example.overlay.client.widgets;

import java.util.List;

import com.example.overlay.client.StandableRect;
import com.example.overlay.client.SurfaceCache;
import com.example.overlay.client.WorldOverlay;

import com.mojang.blaze3d.vertex.BufferBuilder;
import org.joml.Matrix4fc;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;

/**
 * Draws the horizontal collision surface (upward-facing collision faces) of
 * blocks the player selects with a stick. The top face of every collision
 * sub-box is emitted; depth-testing against real geometry hides the parts
 * buried inside blocks, so the visible result is the standable surface (e.g.
 * stairs render as an L). See {@code PLAN.md} for the rationale.
 *
 * <p>The stick is a <b>trigger</b>: right-clicking selects the block under the
 * crosshair (resolved downward to the first non-empty collision shape) into a
 * persistent {@link SurfaceCache}, replacing any previous selection;
 * right-clicking nothing clears it. (v1b will widen a right-click to the
 * block's connected neighbors.) Every selected block's surface is drawn every
 * frame; the selection persists when you switch items and reappears on
 * re-equip, and is emptied by a clearing right-click or a level change.
 *
 * <p>Immediate-mode like the other world overlays: each frame {@link #extract}
 * publishes the cache's combined rectangles into a {@code volatile} snapshot
 * that {@link #emit} re-emits.
 */
public final class CollisionSurfaceOverlay implements WorldOverlay {
	// Lifts the quads just above the block face to avoid z-fighting with the top
	// surface. The fixed surface color (no palette/cycle in this milestone).
	private static final double Y_OFFSET = 0.01;
	private static final float RED = 0.2f;
	private static final float GREEN = 0.8f;
	private static final float BLUE = 1.0f;
	private static final float ALPHA = 0.5f;

	// Cap the downward walk so looking at tall grass over a hole can't scan into
	// the void; resolution also stops at world min-Y.
	private static final int MAX_DOWNWARD_STEPS = 64;

	// Selection set + compute-cache. Mutated on the client thread: extract prunes
	// it; the use-key trigger (onUseItem) (re)selects or clears it.
	private final SurfaceCache cache = new SurfaceCache();

	// Last level seen on the extraction thread. A change (world unload, dimension
	// switch, disconnect/reconnect) empties the in-memory selection — a
	// self-contained alternative to a manager-side world-unload hook.
	private Level lastLevel;

	// Written on the extraction path, read on the render thread, so volatile.
	private volatile boolean holdingStick = false;
	private volatile List<StandableRect> snapshot = List.of();

	@Override
	public String id() {
		return "collision_surface";
	}

	@Override
	public void extract(LevelExtractionContext context) {
		Minecraft client = Minecraft.getInstance();
		Player player = client.player;
		Level level = client.level;

		if (level != lastLevel) {
			cache.clear();
			lastLevel = level;
		}

		holdingStick = player != null && player.getMainHandItem().is(Items.STICK);
		if (level == null) {
			snapshot = List.of();
			return;
		}

		// Place/break can invalidate already-selected blocks; reconcile first. The
		// selection itself only changes on the right-click trigger (onUseItem).
		cache.pruneStale(level);

		snapshot = cache.allRects();
	}

	// Walk down from the targeted block until a non-empty collision shape is
	// found, so pass-through blocks (tall grass, flowers) resolve to the solid
	// block beneath them. Capped and floored at world min-Y.
	private static BlockPos resolveDownward(Level level, BlockPos start) {
		BlockPos.MutableBlockPos cursor = start.mutable();
		int minY = level.getMinY();
		for (int step = 0; step < MAX_DOWNWARD_STEPS && cursor.getY() >= minY; step++) {
			if (!level.getBlockState(cursor).getCollisionShape(level, cursor, CollisionContext.empty()).isEmpty()) {
				return cursor.immutable();
			}
			cursor.move(0, -1, 0);
		}
		return null;
	}

	@Override
	public boolean isVisible() {
		return holdingStick && !snapshot.isEmpty();
	}

	@Override
	public void onUseItem(Player player, InteractionHand hand) {
		// Right-click with the stick (re)selects the targeted block, resolved
		// downward to its standable surface, replacing the previous selection;
		// right-clicking nothing clears it. v1b will widen the hit into a flood.
		if (!player.getItemInHand(hand).is(Items.STICK)) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		Level level = client.level;
		BlockPos start = null;
		if (level != null) {
			HitResult hit = client.hitResult;
			if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
				start = resolveDownward(level, blockHit.getBlockPos());
			}
		}

		if (start != null) {
			cache.select(level, start);
		} else {
			cache.clear();
		}
		player.swing(hand);
	}

	@Override
	public void emit(Matrix4fc positionMatrix, BufferBuilder buffer) {
		List<StandableRect> rects = snapshot;
		if (rects.isEmpty()) {
			return;
		}

		for (StandableRect rect : rects) {
			float minX = (float) rect.minX();
			float minZ = (float) rect.minZ();
			float maxX = (float) rect.maxX();
			float maxZ = (float) rect.maxZ();
			float y = (float) (rect.topY() + Y_OFFSET);

			// A flat (zero-thickness) quad must be emitted with both windings, or
			// back-face culling hides it from one side.
			vertex(buffer, positionMatrix, minX, y, minZ);
			vertex(buffer, positionMatrix, minX, y, maxZ);
			vertex(buffer, positionMatrix, maxX, y, maxZ);
			vertex(buffer, positionMatrix, maxX, y, minZ);

			vertex(buffer, positionMatrix, maxX, y, minZ);
			vertex(buffer, positionMatrix, maxX, y, maxZ);
			vertex(buffer, positionMatrix, minX, y, maxZ);
			vertex(buffer, positionMatrix, minX, y, minZ);
		}
	}

	private static void vertex(BufferBuilder buffer, Matrix4fc matrix, float x, float y, float z) {
		buffer.addVertex(matrix, x, y, z).setColor(RED, GREEN, BLUE, ALPHA);
	}
}
