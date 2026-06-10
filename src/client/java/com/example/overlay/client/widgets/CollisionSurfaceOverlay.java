package com.example.overlay.client.widgets;

import java.util.List;

import com.example.overlay.client.EntityProfile;
import com.example.overlay.client.OverlayManager;
import com.example.overlay.client.StandableRect;
import com.example.overlay.client.SurfaceSelection;
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
 * occlusion-aware (computed in {@link SurfaceSelection#select}): only tops not
 * covered by something directly above are emitted, so e.g. a stair renders as
 * its exposed L. The flat tops/borders draw <b>through walls</b> (depth-off fill
 * layer in {@code WorldOverlayManager}) so any remaining buried surface is
 * visible for debugging; each surface is tinted by <b>height</b> (a blue-to-red
 * gradient across the selection's height range). Every edge drops a
 * <b>depth-tested vertical skirt</b> so the selection reads as a 3D mesh and a
 * real drop reads as an open wall. See {@code PLAN.md}.
 *
 * <p>The stick is a <b>trigger</b>: right-clicking floods the selection from
 * the block under the crosshair (resolved downward to the first non-empty
 * collision shape) outward across walkable, footprint-adjacent surfaces (height
 * steps within the profile's reach) over a spatial window of {@code radius}
 * blocks, into a persistent {@link SurfaceSelection}, replacing any previous
 * selection; right-clicking nothing clears it. The radius is adjustable at runtime via
 * shift+scroll while holding the stick ({@link #adjustRadius}). Each surface is
 * drawn as a translucent fill with an opaque outline plus its skirts. Every
 * selected block's surface is drawn every frame; the selection persists when you switch
 * items and reappears on re-equip, and is emptied by a clearing right-click or
 * a level change.
 *
 * <p>The selection is published into a {@code volatile} snapshot on every stick
 * action ({@link #publish}); {@link #extract} does no per-frame geometry work
 * (it only tracks the held item and resets on a level change), and {@link #emit}
 * re-emits the snapshot each frame.
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

	// Height coloring: map each surface's topY across the selection's height range
	// to a hue ramp from blue (lowest) to red (highest) so elevation/drops read.
	private static final float HUE_LOW = 0.66f;
	private static final float SATURATION = 0.9f;
	private static final float VALUE = 1.0f;
	// Height range below which the selection is treated as flat (single color).
	private static final double FLAT_EPS = 1.0e-6;

	// Vertical skirts dropped from each surface edge so the selection reads as a
	// 3D mesh. Depth = profile.reach() + SKIRT_MARGIN (~2): a region boundary is
	// always a drop > reach, so this clears the reachable zone, and the
	// depth-tested skirt pipeline then occludes the buried part. Skirts are shaded
	// darker than the top for legibility.
	private static final double SKIRT_MARGIN = 1.0;
	private static final float SKIRT_SHADE = 0.55f;
	private static final float SKIRT_ALPHA = 0.6f;
	// How far the skirt's bottom edge leans outward off the block-face plane, so
	// the depth-tested skirt doesn't z-fight the block's own side face. The top
	// edge stays on the surface bound (no corner seam with the top quad).
	private static final double SKIRT_OFFSET = 0.01;

	// Cap the downward walk so looking at tall grass over a hole can't scan into
	// the void; resolution also stops at world min-Y.
	private static final int MAX_DOWNWARD_STEPS = 64;

	// Flood radius: the spatial window half-extent in blocks (not a graph
	// hop-count — merge would make hops meaningless on open ground). Adjustable at
	// runtime via shift+scroll while holding the stick (see adjustRadius), clamped.
	private static final int MIN_RADIUS = 0;
	private static final int MAX_RADIUS = 10;
	private static final int DEFAULT_RADIUS = 3;

	// The computed surfaces, recomputed from scratch on each stick action
	// (onUseItem (re)selects/clears/cycles; adjustRadius re-floods).
	private final SurfaceSelection cache = new SurfaceSelection();

	// Current flood radius and the last resolved seed block, so a radius change
	// can re-flood from the same origin. Both touched only on the client thread
	// (onUseItem and the scroll handler). lastSeed is null when nothing is
	// selected.
	private int selectionRadius = DEFAULT_RADIUS;
	private BlockPos lastSeed;

	// Active entity profile, cycled by sneak+right-click at nothing (see
	// onUseItem). Point is a no-op for dilation, so it reproduces today's
	// point-particle behavior. Written on the client thread (onUseItem), read on
	// the render thread (emit, for the skirt depth), so volatile.
	private volatile EntityProfile profile = EntityProfile.POINT;

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

		// The selection is published on every stick action (publish()), so extract
		// does no per-frame geometry work. It only tracks the held item and resets
		// on a level-identity change (world unload, dimension switch, reconnect).
		if (level != lastLevel) {
			cache.clear();
			lastSeed = null;
			lastLevel = level;
			snapshot = List.of();
		}

		holdingStick = player != null && player.getMainHandItem().is(Items.STICK);
	}

	// Publish the current selection into the volatile snapshot emit() reads. Called
	// after every mutation (select/clear/radius/profile) so per-frame work is nil;
	// editing painted terrain therefore needs a re-click to refresh (intended).
	private void publish() {
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
			cache.select(level, start, selectionRadius, profile);
			lastSeed = start;
		} else {
			// Right-click at nothing clears. Sneaking also advances the profile and
			// pings the HUD; lastSeed stays null so there is no re-flood — the new
			// profile takes effect on the next select.
			cache.clear();
			lastSeed = null;
			if (player.isShiftKeyDown()) {
				profile = profile.next();
				OverlayManager.radiusIndicator().showProfile(profile.name());
			}
		}
		publish();
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
				cache.select(level, lastSeed, selectionRadius, profile);
				publish();
			}
		}
		return selectionRadius;
	}

	@Override
	public void emit(Matrix4fc positionMatrix, BufferBuilder fillBuffer, BufferBuilder skirtBuffer) {
		List<StandableRect> rects = snapshot;
		if (rects.isEmpty()) {
			return;
		}

		// Height range for the color gradient (single color when flat).
		double minTopY = Double.POSITIVE_INFINITY;
		double maxTopY = Double.NEGATIVE_INFINITY;
		for (StandableRect rect : rects) {
			minTopY = Math.min(minTopY, rect.topY());
			maxTopY = Math.max(maxTopY, rect.topY());
		}

		float skirtDepth = (float) (profile.reach() + SKIRT_MARGIN);

		float o = (float) SKIRT_OFFSET;

		for (StandableRect rect : rects) {
			// Draw-time dilation: grow the rect outward by SKIRT_OFFSET so a rect's
			// top meets its own skirts (no seam) and neighbors overlap instead of
			// sharing a coincident skirt edge (which z-fought). Purely cosmetic.
			float minX = (float) rect.minX() - o;
			float minZ = (float) rect.minZ() - o;
			float maxX = (float) rect.maxX() + o;
			float maxZ = (float) rect.maxZ() + o;
			float y = (float) rect.topY() + (float) Y_OFFSET;

			float[] rgb = heightColor(rect.topY(), minTopY, maxTopY);
			float r = rgb[0];
			float g = rgb[1];
			float b = rgb[2];

			// Flat top + opaque outline into the depth-off fill layer (draws through
			// walls). Border strips clamped to half the rect so tiny rects don't
			// invert.
			quad(fillBuffer, positionMatrix, minX, maxX, minZ, maxZ, y, r, g, b, FILL_ALPHA);

			float bx = Math.min(BORDER_THICKNESS, (maxX - minX) * 0.5f);
			float bz = Math.min(BORDER_THICKNESS, (maxZ - minZ) * 0.5f);
			quad(fillBuffer, positionMatrix, minX, maxX, minZ, minZ + bz, y, r, g, b, BORDER_ALPHA);
			quad(fillBuffer, positionMatrix, minX, maxX, maxZ - bz, maxZ, y, r, g, b, BORDER_ALPHA);
			quad(fillBuffer, positionMatrix, minX, minX + bx, minZ, maxZ, y, r, g, b, BORDER_ALPHA);
			quad(fillBuffer, positionMatrix, maxX - bx, maxX, minZ, maxZ, y, r, g, b, BORDER_ALPHA);

			// Vertical skirts at the dilated edges into the depth-tested layer:
			// buried ones (riser-backed, solid-backed interior edges) are occluded by
			// world geometry; open-drop edges stay visible. They meet the dilated top
			// edge exactly and join neighbors, so no corner/edge seam.
			float sr = r * SKIRT_SHADE;
			float sg = g * SKIRT_SHADE;
			float sb = b * SKIRT_SHADE;
			float yBot = y - skirtDepth;
			fadedSkirt(skirtBuffer, positionMatrix, minX, minZ, maxX, minZ, y, yBot, sr, sg, sb);
			fadedSkirt(skirtBuffer, positionMatrix, minX, maxZ, maxX, maxZ, y, yBot, sr, sg, sb);
			fadedSkirt(skirtBuffer, positionMatrix, minX, minZ, minX, maxZ, y, yBot, sr, sg, sb);
			fadedSkirt(skirtBuffer, positionMatrix, maxX, minZ, maxX, maxZ, y, yBot, sr, sg, sb);
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

	// A vertical skirt that is solid over its top half and fades to transparent
	// over its bottom half, so a drop deeper than the skirt doesn't read as a hard
	// floating wall. Two stacked double-winding segments between the horizontal
	// endpoints (xa,za)-(xb,zb).
	private static void fadedSkirt(BufferBuilder buffer, Matrix4fc matrix,
			float xa, float za, float xb, float zb, float yTop, float yBot,
			float r, float g, float b) {
		float yMid = (yTop + yBot) * 0.5f;
		vQuad(buffer, matrix, xa, za, xb, zb, yTop, yMid, r, g, b, SKIRT_ALPHA, SKIRT_ALPHA);
		vQuad(buffer, matrix, xa, za, xb, zb, yMid, yBot, r, g, b, SKIRT_ALPHA, 0.0f);
	}

	// One vertical quad spanning [yBot, yTop] between horizontal endpoints
	// (xa,za)-(xb,zb), with separate alpha at the top and bottom edges (linearly
	// interpolated), emitted with both windings so it's visible from both sides
	// (occlusion is handled by the depth-tested pipeline).
	private static void vQuad(BufferBuilder buffer, Matrix4fc matrix,
			float xa, float za, float xb, float zb, float yTop, float yBot,
			float r, float g, float b, float aTop, float aBot) {
		vertex(buffer, matrix, xa, yBot, za, r, g, b, aBot);
		vertex(buffer, matrix, xa, yTop, za, r, g, b, aTop);
		vertex(buffer, matrix, xb, yTop, zb, r, g, b, aTop);
		vertex(buffer, matrix, xb, yBot, zb, r, g, b, aBot);

		vertex(buffer, matrix, xb, yBot, zb, r, g, b, aBot);
		vertex(buffer, matrix, xb, yTop, zb, r, g, b, aTop);
		vertex(buffer, matrix, xa, yTop, za, r, g, b, aTop);
		vertex(buffer, matrix, xa, yBot, za, r, g, b, aBot);
	}

	// Map a surface height to RGB: a hue ramp from blue (lowest) to red (highest)
	// across the selection's [minTopY, maxTopY]; a flat selection -> single color.
	private static float[] heightColor(double topY, double minTopY, double maxTopY) {
		double range = maxTopY - minTopY;
		float t = range < FLAT_EPS ? 0.5f : (float) ((topY - minTopY) / range);
		float hue = HUE_LOW * (1.0f - t);
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
