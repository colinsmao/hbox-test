package com.example.overlay.client.widgets;

import java.util.ArrayList;
import java.util.Comparator;
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
 * gradient across the selection's height range). Surfaces within the last block
 * before the flood-radius cutoff blend toward <b>grey</b> to signal "increase the
 * radius or re-center" — a selection bounded by a real drop stops short of the
 * radius and stays height-colored, so a radius cutoff reads differently from a
 * true boundary. Each edge drops a <b>depth-tested vertical skirt</b> so the
 * selection reads as a 3D mesh and a real drop reads as an open wall, but
 * <b>skirt-diffed</b>: the parts of an edge shared with an equal-height neighbour
 * (an internal edge of a continuous level the greedy merge split) are skipped, so
 * they don't read as false interior walls. See {@code PLAN.md}.
 *
 * <p>The stick is a <b>trigger</b>: right-clicking floods the selection from
 * the block under the crosshair (resolved downward to the first non-empty
 * collision shape) outward across walkable, footprint-adjacent surfaces (height
 * steps within the profile's reach) over a spatial window of {@code radius}
 * blocks, into a persistent {@link SurfaceSelection}, replacing any previous
 * selection; right-clicking nothing clears it. The radius is adjustable at runtime via
 * shift+scroll while holding the stick ({@link #adjustRadius}). Each surface is
 * drawn as a translucent fill plus its skirts, with an opaque outline added only
 * while crouching (a debug aid that otherwise clutters the view). Every
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
	// surface. Kept as small as possible so the top hugs the real surface.
	private static final double Y_OFFSET = 0.002;
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
	// Tiny uniform outward push of the (square) skirt edges — NOT a dilation: just
	// enough to lift the skirt off the coplanar terrain side face so it doesn't
	// z-fight. Kept tiny so the gap from the top edge stays invisible and skirts
	// don't visibly overlap neighbours.
	private static final double SKIRT_OFFSET = 0.002;
	// Edge-coincidence tolerance for the skirt-diff (which edge sub-spans abut an
	// equal-height neighbour). Merged rects share exact slab-boundary coords, so a
	// small epsilon is ample.
	private static final double SKIRT_EPS = 1.0e-6;

	// Cap the downward walk so looking at tall grass over a hole can't scan into
	// the void; resolution also stops at world min-Y.
	private static final int MAX_DOWNWARD_STEPS = 64;

	// Flood radius: the spatial window half-extent in blocks (not a graph
	// hop-count — merge would make hops meaningless on open ground). Adjustable at
	// runtime via shift+scroll while holding the stick (see adjustRadius), clamped.
	private static final int MIN_RADIUS = 0;
	private static final int MAX_RADIUS = 20;
	private static final int DEFAULT_RADIUS = 3;
	// Past this the scroll steps by 2 (the window grows quadratically, so coarse
	// steps keep the high end usable without a huge tick count): 0..10 by 1, then
	// 12, 14, ..., 20.
	private static final int COARSE_RADIUS = 10;

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
	// The opaque per-rect outlines are a debugging aid (they separate adjacent
	// surfaces and sub-rects) but clutter the normal view, so they only draw while
	// crouching. Sampled on the extraction thread, read in emit.
	private volatile boolean crouching = false;
	private volatile List<StandableRect> snapshot = List.of();

	// Outer-ring greying: surfaces within the last block before the flood-radius
	// cutoff are blended toward grey to signal "increase the radius or re-center".
	// A selection bounded by a real drop stops short of the radius, so it never
	// reaches the ring and stays height-colored — that's how a true boundary reads
	// differently from a radius cutoff. Published with the snapshot (client
	// thread), read per-vertex in emit (render thread). ringEnd > ringStart always.
	private static final float[] RING_COLOR = {0.5f, 0.5f, 0.5f};
	private volatile double ringCenterX;
	private volatile double ringCenterZ;
	private volatile double ringStart;
	private volatile double ringEnd = 1.0;

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
		crouching = player != null && player.isShiftKeyDown();
	}

	// Publish the current selection into the volatile snapshot emit() reads. Called
	// after every mutation (select/clear/radius/profile) so per-frame work is nil;
	// editing painted terrain therefore needs a re-click to refresh (intended).
	private void publish() {
		snapshot = cache.allRects();
		// The cutoff ring sits one block inside the window's outer painted extent:
		// the far edge of the outermost column (seed ± radius), grown by the
		// entity half-width, measured (Chebyshev) from the seed block center.
		if (lastSeed != null) {
			double halfW = profile.width() / 2.0;
			ringCenterX = lastSeed.getX() + 0.5;
			ringCenterZ = lastSeed.getZ() + 0.5;
			ringEnd = selectionRadius + 0.5 + halfW;
			ringStart = ringEnd - 1.0;
		}
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
		// delta is a scroll direction (+/-1). Step by 2 in the coarse range so the
		// sequence is 0..10 by 1 then 12,14,..,20 (up: coarse once we reach the
		// threshold; down: coarse only while strictly above it, so 12->10->9).
		int dir = Integer.signum(delta);
		int step = (dir > 0 ? selectionRadius >= COARSE_RADIUS : selectionRadius > COARSE_RADIUS) ? 2 : 1;
		int updated = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, selectionRadius + dir * step));
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
			// Tops/borders draw at the TRUE rect bounds, so adjacent surfaces tile
			// exactly instead of overlapping (overlapping translucent quads
			// double-blend into visible seams). Only the skirts are nudged out by a
			// tiny SKIRT_OFFSET to dodge z-fighting the terrain face (not a dilation).
			float minX = (float) rect.minX();
			float minZ = (float) rect.minZ();
			float maxX = (float) rect.maxX();
			float maxZ = (float) rect.maxZ();
			float y = (float) rect.topY() + (float) Y_OFFSET;

			float[] rgb = heightColor(rect.topY(), minTopY, maxTopY);
			float r = rgb[0];
			float g = rgb[1];
			float b = rgb[2];

			// Flat top, split at the cutoff ring so the grey fade stays confined to
			// the outer band even on a long merged rect. Seeing surfaces through
			// solid blocks is a debug aid, so it only draws into the depth-off
			// (through-walls) layer while crouching; otherwise it goes into the
			// depth-tested layer and is occluded by terrain like a real surface.
			BufferBuilder topBuffer = crouching ? fillBuffer : skirtBuffer;
			fadedTop(topBuffer, positionMatrix, minX, maxX, minZ, maxZ, y, r, g, b, FILL_ALPHA);

			// Opaque outline: a debug aid that separates adjacent surfaces and the
			// sub-rects of one block, but clutters the normal view — only while
			// crouching. Border strips clamped to half the rect so tiny rects don't
			// invert.
			if (crouching) {
				float bx = Math.min(BORDER_THICKNESS, (maxX - minX) * 0.5f);
				float bz = Math.min(BORDER_THICKNESS, (maxZ - minZ) * 0.5f);
				quad(fillBuffer, positionMatrix, minX, maxX, minZ, minZ + bz, y, r, g, b, BORDER_ALPHA);
				quad(fillBuffer, positionMatrix, minX, maxX, maxZ - bz, maxZ, y, r, g, b, BORDER_ALPHA);
				quad(fillBuffer, positionMatrix, minX, minX + bx, minZ, maxZ, y, r, g, b, BORDER_ALPHA);
				quad(fillBuffer, positionMatrix, maxX - bx, maxX, minZ, maxZ, y, r, g, b, BORDER_ALPHA);
			}

			// Vertical (square) skirts into the depth-tested layer, pushed out by a
			// tiny SKIRT_OFFSET so they clear the coplanar terrain side face without
			// z-fighting. Skirt-diff: each edge is skirted only over the sub-spans
			// NOT shared with an equal-height neighbour — the greedy merge splits a
			// holed/L-shaped level into several rects, and a skirt on such an internal
			// (partly-shared) edge reads as a false interior wall (depth can't hide it
			// where it overhangs air near a hole). True drop/boundary edges keep their
			// skirts; the window-boundary edge does too (grey ring signals the cutoff).
			float sMinX = minX - o;
			float sMinZ = minZ - o;
			float sMaxX = maxX + o;
			float sMaxZ = maxZ + o;
			float sr = r * SKIRT_SHADE;
			float sg = g * SKIRT_SHADE;
			float sb = b * SKIRT_SHADE;
			float yBot = y - skirtDepth;
			for (float[] sp : openSpans(rects, rect, EDGE_MIN_Z)) {
				fadedSkirt(skirtBuffer, positionMatrix, sp[0] - o, sMinZ, sp[1] + o, sMinZ, y, yBot, sr, sg, sb);
			}
			for (float[] sp : openSpans(rects, rect, EDGE_MAX_Z)) {
				fadedSkirt(skirtBuffer, positionMatrix, sp[0] - o, sMaxZ, sp[1] + o, sMaxZ, y, yBot, sr, sg, sb);
			}
			for (float[] sp : openSpans(rects, rect, EDGE_MIN_X)) {
				fadedSkirt(skirtBuffer, positionMatrix, sMinX, sp[0] - o, sMinX, sp[1] + o, y, yBot, sr, sg, sb);
			}
			for (float[] sp : openSpans(rects, rect, EDGE_MAX_X)) {
				fadedSkirt(skirtBuffer, positionMatrix, sMaxX, sp[0] - o, sMaxX, sp[1] + o, y, yBot, sr, sg, sb);
			}
		}
	}

	// Edge selectors for openSpans: the four sides of a rect.
	private static final int EDGE_MIN_Z = 0;
	private static final int EDGE_MAX_Z = 1;
	private static final int EDGE_MIN_X = 2;
	private static final int EDGE_MAX_X = 3;

	// The sub-spans of one rect edge that should drop a skirt: the edge's full
	// extent minus the parts covered by an equal-height neighbour abutting across
	// it (an internal edge of a continuous level the greedy merge happened to split
	// — not a real drop). Returns [lo,hi] pairs along the edge's varying axis (X for
	// the Z edges, Z for the X edges) in true world coords. O(n) over the reached
	// set per edge; n is the merged-rect count, so cheap enough per frame, and the
	// same diff feeds the future hole/surface overlay.
	private static List<float[]> openSpans(List<StandableRect> rects, StandableRect r, int edge) {
		double lo = edge < EDGE_MIN_X ? r.minX() : r.minZ();
		double hi = edge < EDGE_MIN_X ? r.maxX() : r.maxZ();
		List<double[]> covered = new ArrayList<>();
		for (StandableRect nb : rects) {
			if (nb == r || Math.abs(nb.topY() - r.topY()) > SKIRT_EPS) {
				continue;
			}
			boolean abuts = false;
			double clo = 0.0;
			double chi = 0.0;
			switch (edge) {
				case EDGE_MIN_Z -> {
					abuts = Math.abs(nb.maxZ() - r.minZ()) < SKIRT_EPS;
					clo = Math.max(nb.minX(), r.minX());
					chi = Math.min(nb.maxX(), r.maxX());
				}
				case EDGE_MAX_Z -> {
					abuts = Math.abs(nb.minZ() - r.maxZ()) < SKIRT_EPS;
					clo = Math.max(nb.minX(), r.minX());
					chi = Math.min(nb.maxX(), r.maxX());
				}
				case EDGE_MIN_X -> {
					abuts = Math.abs(nb.maxX() - r.minX()) < SKIRT_EPS;
					clo = Math.max(nb.minZ(), r.minZ());
					chi = Math.min(nb.maxZ(), r.maxZ());
				}
				default -> {
					abuts = Math.abs(nb.minX() - r.maxX()) < SKIRT_EPS;
					clo = Math.max(nb.minZ(), r.minZ());
					chi = Math.min(nb.maxZ(), r.maxZ());
				}
			}
			if (abuts && chi - clo > SKIRT_EPS) {
				covered.add(new double[] {clo, chi});
			}
		}
		return subtractSpans(lo, hi, covered);
	}

	// [lo,hi] minus the union of the covered intervals, as the remaining open
	// sub-spans (sweep left to right over the sorted intervals).
	private static List<float[]> subtractSpans(double lo, double hi, List<double[]> covered) {
		covered.sort(Comparator.comparingDouble(c -> c[0]));
		List<float[]> out = new ArrayList<>();
		double cur = lo;
		for (double[] c : covered) {
			if (c[0] > cur + SKIRT_EPS) {
				out.add(new float[] {(float) cur, (float) Math.min(c[0], hi)});
			}
			cur = Math.max(cur, c[1]);
			if (cur >= hi - SKIRT_EPS) {
				break;
			}
		}
		if (hi - cur > SKIRT_EPS) {
			out.add(new float[] {(float) cur, (float) hi});
		}
		return out;
	}

	// One flat axis-aligned quad over [x0,x1] x [z0,z1] at height y, emitted with
	// both windings (a zero-thickness quad would otherwise be culled from one
	// side).
	private void quad(BufferBuilder buffer, Matrix4fc matrix,
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

	// The flat top, split into sub-quads at the cutoff-ring square (the lines
	// |x-center| == ringStart and |z-center| == ringStart). The grey fade is a
	// per-vertex color, so a single huge quad would smear the ramp across its whole
	// length; splitting keeps the fade inside the <=1-block outer band — the
	// interior stays one fully-colored quad, the outer strips fade correctly.
	private void fadedTop(BufferBuilder buffer, Matrix4fc matrix,
			float minX, float maxX, float minZ, float maxZ, float y,
			float r, float g, float b, float a) {
		float[] xs = breakpoints(minX, maxX, (float) (ringCenterX - ringStart), (float) (ringCenterX + ringStart));
		float[] zs = breakpoints(minZ, maxZ, (float) (ringCenterZ - ringStart), (float) (ringCenterZ + ringStart));
		for (int i = 0; i + 1 < xs.length; i++) {
			for (int j = 0; j + 1 < zs.length; j++) {
				quad(buffer, matrix, xs[i], xs[i + 1], zs[j], zs[j + 1], y, r, g, b, a);
			}
		}
	}

	// Sorted span ends [lo, hi] plus any of the two cut coordinates lying strictly
	// inside, so the span is split where it crosses the cutoff-ring boundary.
	private static float[] breakpoints(float lo, float hi, float cutA, float cutB) {
		float lowCut = Math.min(cutA, cutB);
		float highCut = Math.max(cutA, cutB);
		boolean useLow = lowCut > lo + 1.0e-4f && lowCut < hi - 1.0e-4f;
		boolean useHigh = highCut > lo + 1.0e-4f && highCut < hi - 1.0e-4f;
		if (useLow && useHigh) {
			return new float[] {lo, lowCut, highCut, hi};
		}
		if (useLow) {
			return new float[] {lo, lowCut, hi};
		}
		if (useHigh) {
			return new float[] {lo, highCut, hi};
		}
		return new float[] {lo, hi};
	}

	// Single emission choke point: blends the vertex color toward RING_COLOR by how
	// deep it is into the cutoff ring (0 one block in, 1 at the window edge), so
	// every layer (top, border, skirt) greys out together near the radius limit.
	private void vertex(BufferBuilder buffer, Matrix4fc matrix, float x, float y, float z,
			float r, float g, float b, float a) {
		double d = Math.max(Math.abs(x - ringCenterX), Math.abs(z - ringCenterZ));
		double t = Math.max(0.0, Math.min(1.0, (d - ringStart) / (ringEnd - ringStart)));
		// Ease the ramp up (sqrt) so the grey saturates early and fills most of the
		// outer block, while staying pinned to 0 at ringStart (no bleed inward).
		float f = (float) Math.sqrt(t);
		float rr = r + (RING_COLOR[0] - r) * f;
		float gg = g + (RING_COLOR[1] - g) * f;
		float bb = b + (RING_COLOR[2] - b) * f;
		buffer.addVertex(matrix, x, y, z).setColor(rr, gg, bb, a);
	}

	// A vertical skirt that is solid over its top half and fades to transparent
	// over its bottom half, so a drop deeper than the skirt doesn't read as a hard
	// floating wall. Two stacked double-winding segments between the horizontal
	// endpoints (xa,za)-(xb,zb).
	private void fadedSkirt(BufferBuilder buffer, Matrix4fc matrix,
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
	private void vQuad(BufferBuilder buffer, Matrix4fc matrix,
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
