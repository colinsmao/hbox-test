package com.example.overlay.client.widgets;

import java.util.ArrayList;
import java.util.List;

import com.example.overlay.client.StandableRect;
import com.example.overlay.client.WorldOverlay;

import com.mojang.blaze3d.vertex.BufferBuilder;
import org.joml.Matrix4fc;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;

/**
 * Draws the horizontal collision surface (upward-facing collision faces) of the
 * block under the crosshair, but only while holding a stick. The top face of
 * every collision sub-box is emitted; depth-testing against real geometry hides
 * the parts buried inside blocks, so the visible result is the standable surface
 * (e.g. stairs render as an L). See {@code PLAN.md} for the rationale.
 *
 * <p>Stage 1: single hovered block, recomputed every frame (no cache). The
 * stick-as-brush selection set and the surface cache arrive in Stage 2.
 *
 * <p>Immediate-mode like the other world overlays: each frame rebuilds the
 * rectangle snapshot in {@link #extract} and re-emits it in {@link #emit}.
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

		holdingStick = player != null && player.getMainHandItem().is(Items.STICK);
		if (!holdingStick || level == null) {
			snapshot = List.of();
			return;
		}

		HitResult hit = client.hitResult;
		if (hit == null || hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
			snapshot = List.of();
			return;
		}

		BlockPos resolved = resolveDownward(level, blockHit.getBlockPos());
		snapshot = resolved == null ? List.of() : buildRects(level, resolved);
	}

	// Walk down from the targeted block until a non-empty collision shape is
	// found, so pass-through blocks (tall grass, flowers) resolve to the solid
	// block beneath them. Capped and floored at world min-Y.
	private static BlockPos resolveDownward(Level level, BlockPos start) {
		BlockPos.MutableBlockPos cursor = start.mutable();
		int minY = level.getMinY();
		for (int step = 0; step < MAX_DOWNWARD_STEPS && cursor.getY() >= minY; step++) {
			BlockState state = level.getBlockState(cursor);
			if (!state.getCollisionShape(level, cursor, CollisionContext.empty()).isEmpty()) {
				return cursor.immutable();
			}
			cursor.move(0, -1, 0);
		}
		return null;
	}

	// Each collision sub-box's top (maxY) over its [minX,maxX] x [minZ,maxZ]
	// footprint is one standable rectangle, in absolute world coords.
	private static List<StandableRect> buildRects(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
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

	@Override
	public boolean isVisible() {
		return holdingStick && !snapshot.isEmpty();
	}

	@Override
	public void onUseItem(Player player, InteractionHand hand) {
		// Stage 1: no selection to clear yet; keep the arm-swing feedback only.
		if (player.getItemInHand(hand).is(Items.STICK)) {
			player.swing(hand);
		}
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
