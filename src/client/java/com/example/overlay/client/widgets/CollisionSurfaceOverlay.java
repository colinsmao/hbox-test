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
 * sub-box is emitted. For debugging this milestone the world-overlay pipeline
 * draws <b>through walls</b> (depth test disabled in {@code WorldOverlayManager})
 * so surfaces buried inside solid blocks are visible, and each surface is tinted
 * by its BFS distance from the clicked block (an HSV hue ramp). See
 * {@code PLAN.md} for the rationale.
 *
 * <p>The stick is a <b>trigger</b>: right-clicking floods the selection from
 * the block under the crosshair (resolved downward to the first non-empty
 * collision shape) outward across horizontally-connected blocks, out to
 * {@code SELECTION_RADIUS}, into a persistent {@link SurfaceCache}, replacing
 * any previous selection; right-clicking nothing clears it. Every selected
 * block's surface is drawn every frame; the selection persists when you switch
 * items and reappears on re-equip, and is emptied by a clearing right-click or
 * a level change.
 *
 * <p>Immediate-mode like the other world overlays: each frame {@link #extract}
 * publishes the cache's combined rectangles into a {@code volatile} snapshot
 * that {@link #emit} re-emits.
 */
public final class CollisionSurfaceOverlay implements WorldOverlay {
	// Lifts the quads just above the block face to avoid z-fighting with the top
	// surface.
	private static final double Y_OFFSET = 0.01;
	private static final float ALPHA = 0.5f;

	// Distance coloring (v1.5 debug): each BFS ring gets a distinct hue so the
	// flood's connectivity is visible. HUE_STEP is the hue advance per ring;
	// 0.15 keeps several rings distinguishable before the wheel wraps.
	private static final float HUE_STEP = 0.15f;
	private static final float SATURATION = 0.9f;
	private static final float VALUE = 1.0f;

	// Cap the downward walk so looking at tall grass over a hole can't scan into
	// the void; resolution also stops at world min-Y.
	private static final int MAX_DOWNWARD_STEPS = 64;

	// Graph distance the right-click flood expands from the targeted block.
	// A constant for now; a future config could expose it.
	private static final int SELECTION_RADIUS = 3;

	// Selection set + compute-cache. Mutated on the client thread: extract prunes
	// it; the use-key trigger (onUseItem) (re)selects or clears it.
	private final SurfaceCache cache = new SurfaceCache();

	// Last level seen on the extraction thread. A change (world unload, dimension
	// switch, disconnect/reconnect) empties the in-memory selection — a
	// self-contained alternative to a manager-side world-unload hook.
	private Level lastLevel;

	// Written on the extraction path, read on the render thread, so volatile.
	private volatile boolean holdingStick = false;
	private volatile List<SurfaceCache.DistancedRect> snapshot = List.of();

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
		// Right-click with the stick floods the selection from the targeted block
		// (resolved downward to its standable surface) across connected neighbors,
		// replacing the previous selection; right-clicking nothing clears it.
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
			cache.select(level, start, SELECTION_RADIUS);
		} else {
			cache.clear();
		}
		player.swing(hand);
	}

	@Override
	public void emit(Matrix4fc positionMatrix, BufferBuilder buffer) {
		List<SurfaceCache.DistancedRect> rects = snapshot;
		if (rects.isEmpty()) {
			return;
		}

		for (SurfaceCache.DistancedRect tagged : rects) {
			StandableRect rect = tagged.rect();
			float minX = (float) rect.minX();
			float minZ = (float) rect.minZ();
			float maxX = (float) rect.maxX();
			float maxZ = (float) rect.maxZ();
			float y = (float) (rect.topY() + Y_OFFSET);

			float[] rgb = distanceColor(tagged.distance());
			float r = rgb[0];
			float g = rgb[1];
			float b = rgb[2];

			// A flat (zero-thickness) quad must be emitted with both windings, or
			// back-face culling hides it from one side.
			vertex(buffer, positionMatrix, minX, y, minZ, r, g, b);
			vertex(buffer, positionMatrix, minX, y, maxZ, r, g, b);
			vertex(buffer, positionMatrix, maxX, y, maxZ, r, g, b);
			vertex(buffer, positionMatrix, maxX, y, minZ, r, g, b);

			vertex(buffer, positionMatrix, maxX, y, minZ, r, g, b);
			vertex(buffer, positionMatrix, maxX, y, maxZ, r, g, b);
			vertex(buffer, positionMatrix, minX, y, maxZ, r, g, b);
			vertex(buffer, positionMatrix, minX, y, minZ, r, g, b);
		}
	}

	private static void vertex(BufferBuilder buffer, Matrix4fc matrix, float x, float y, float z,
			float r, float g, float b) {
		buffer.addVertex(matrix, x, y, z).setColor(r, g, b, ALPHA);
	}

	// Map a BFS ring distance to an RGB color by stepping the hue per ring.
	private static float[] distanceColor(int distance) {
		float hue = (distance * HUE_STEP) % 1.0f;
		return hsvToRgb(hue, SATURATION, VALUE);
	}

	private static float[] hsvToRgb(float h, float s, float v) {
		int sector = (int) (h * 6.0f) % 6;
		float f = h * 6.0f - (float) Math.floor(h * 6.0f);
		float p = v * (1.0f - s);
		float q = v * (1.0f - f * s);
		float t = v * (1.0f - (1.0f - f) * s);
		return switch (sector) {
			case 0 -> new float[] {v, t, p};
			case 1 -> new float[] {q, v, p};
			case 2 -> new float[] {p, v, t};
			case 3 -> new float[] {p, q, v};
			case 4 -> new float[] {t, p, v};
			default -> new float[] {v, p, q};
		};
	}
}
