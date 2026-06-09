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
 * Draws the standable surfaces (upward-facing collision faces an entity can
 * stand on) of a region the player selects with a stick. The surfaces are
 * occlusion-aware (computed in {@link SurfaceCache#select}): only tops not
 * covered by something directly above are emitted, so e.g. a stair renders as
 * its exposed L. For debugging this milestone the world-overlay pipeline draws
 * <b>through walls</b> (depth test disabled in {@code WorldOverlayManager}) so
 * any remaining buried surface is visible, and each surface is tinted by its
 * flood distance from the clicked block (an HSV hue ramp). See {@code PLAN.md}.
 *
 * <p>The stick is a <b>trigger</b>: right-clicking floods the selection from
 * the block under the crosshair (resolved downward to the first non-empty
 * collision shape) outward across walkable, footprint-adjacent surfaces within a
 * one-block step, out to the current flood radius (block transitions) into a
 * persistent {@link SurfaceCache}, replacing any previous selection;
 * right-clicking nothing clears it. The radius is adjustable at runtime via
 * shift+scroll while holding the stick ({@link #adjustRadius}). Each surface is
 * drawn as a translucent fill with an opaque outline. Every selected
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
	private static final float FILL_ALPHA = 0.5f;
	// An opaque outline drawn around each rect so adjacent surfaces (and the
	// sub-rects of a single block) stay visually separable through the fill.
	private static final float BORDER_ALPHA = 1.0f;
	private static final float BORDER_THICKNESS = 0.045f;

	// Distance coloring (v1.5 debug): each BFS ring gets a distinct hue so the
	// flood's connectivity is visible. HUE_STEP is the hue advance per ring;
	// 0.15 keeps several rings distinguishable before the wheel wraps.
	private static final float HUE_STEP = 0.15f;
	private static final float SATURATION = 0.9f;
	private static final float VALUE = 1.0f;

	// Cap the downward walk so looking at tall grass over a hole can't scan into
	// the void; resolution also stops at world min-Y.
	private static final int MAX_DOWNWARD_STEPS = 64;

	// Flood radius (block transitions from the seed). Adjustable at runtime via
	// shift+scroll while holding the stick (see adjustRadius), clamped to range.
	private static final int MIN_RADIUS = 0;
	private static final int MAX_RADIUS = 10;
	private static final int DEFAULT_RADIUS = 3;

	// Selection set + compute-cache. Mutated on the client thread: extract prunes
	// it; the use-key trigger (onUseItem) (re)selects or clears it.
	private final SurfaceCache cache = new SurfaceCache();

	// Current flood radius and the last resolved seed block, so a radius change
	// can re-flood from the same origin. Both touched only on the client thread
	// (onUseItem and the scroll handler). lastSeed is null when nothing is
	// selected.
	private int selectionRadius = DEFAULT_RADIUS;
	private BlockPos lastSeed;

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
			lastSeed = null;
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
			cache.select(level, start, selectionRadius);
			lastSeed = start;
		} else {
			cache.clear();
			lastSeed = null;
		}
		player.swing(hand);
	}

	// True when shift+scroll should retarget the flood radius instead of switching
	// the hotbar: only while holding the stick (the tool) and sneaking, so plain
	// scroll still changes the hotbar normally.
	public boolean wantsRadiusScroll() {
		Player player = Minecraft.getInstance().player;
		return player != null && player.getMainHandItem().is(Items.STICK) && player.isShiftKeyDown();
	}

	// Change the flood radius by delta (clamped) and, if a selection is active,
	// re-flood from its seed so the change shows immediately. Returns the new
	// radius (for the on-screen indicator), even when clamping left it unchanged.
	public int adjustRadius(int delta) {
		int updated = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, selectionRadius + delta));
		if (updated != selectionRadius) {
			selectionRadius = updated;
			Level level = Minecraft.getInstance().level;
			if (level != null && lastSeed != null) {
				cache.select(level, lastSeed, selectionRadius);
			}
		}
		return selectionRadius;
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

			// Translucent fill, then an opaque outline so neighboring rects stay
			// distinguishable. Border strips are clamped to half the rect so tiny
			// rects don't invert.
			quad(buffer, positionMatrix, minX, maxX, minZ, maxZ, y, r, g, b, FILL_ALPHA);

			float bx = Math.min(BORDER_THICKNESS, (maxX - minX) * 0.5f);
			float bz = Math.min(BORDER_THICKNESS, (maxZ - minZ) * 0.5f);
			quad(buffer, positionMatrix, minX, maxX, minZ, minZ + bz, y, r, g, b, BORDER_ALPHA);
			quad(buffer, positionMatrix, minX, maxX, maxZ - bz, maxZ, y, r, g, b, BORDER_ALPHA);
			quad(buffer, positionMatrix, minX, minX + bx, minZ, maxZ, y, r, g, b, BORDER_ALPHA);
			quad(buffer, positionMatrix, maxX - bx, maxX, minZ, maxZ, y, r, g, b, BORDER_ALPHA);
		}
	}

	// One flat axis-aligned quad over [x0,x1] x [z0,z1] at height y, emitted with
	// both windings (a zero-thickness quad would otherwise be culled from one
	// side).
	private static void quad(BufferBuilder buffer, Matrix4fc matrix,
			float x0, float x1, float z0, float z1, float y,
			float r, float g, float b, float a) {
		vertex(buffer, matrix, x0, y, z0, r, g, b, a);
		vertex(buffer, matrix, x0, y, z1, r, g, b, a);
		vertex(buffer, matrix, x1, y, z1, r, g, b, a);
		vertex(buffer, matrix, x1, y, z0, r, g, b, a);

		vertex(buffer, matrix, x1, y, z0, r, g, b, a);
		vertex(buffer, matrix, x1, y, z1, r, g, b, a);
		vertex(buffer, matrix, x0, y, z1, r, g, b, a);
		vertex(buffer, matrix, x0, y, z0, r, g, b, a);
	}

	private static void vertex(BufferBuilder buffer, Matrix4fc matrix, float x, float y, float z,
			float r, float g, float b, float a) {
		buffer.addVertex(matrix, x, y, z).setColor(r, g, b, a);
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
