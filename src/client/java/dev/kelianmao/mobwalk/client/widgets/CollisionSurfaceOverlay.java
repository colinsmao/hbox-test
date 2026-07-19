package dev.kelianmao.mobwalk.client.widgets;

import java.util.List;

import dev.kelianmao.mobwalk.client.Configs;
import dev.kelianmao.mobwalk.client.Configs.ShowSurfaces;
import dev.kelianmao.mobwalk.client.DownSkirtSpan;
import dev.kelianmao.mobwalk.client.EntityProfile;
import dev.kelianmao.mobwalk.client.HoleSpan;
import dev.kelianmao.mobwalk.client.OccluderSpan;
import dev.kelianmao.mobwalk.client.OverlayManager;
import dev.kelianmao.mobwalk.client.StandableRect;
import dev.kelianmao.mobwalk.client.SurfaceSelection;
import dev.kelianmao.mobwalk.client.WorldOverlay;

import com.mojang.blaze3d.vertex.BufferBuilder;
import org.joml.Matrix4fc;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;

import fi.dy.masa.malilib.util.data.Color4f;

/**
 * Draws the standable surfaces (upward-facing collision faces an entity can
 * stand on) of a region the player selects with the wand. The surfaces are
 * occlusion-aware (computed in {@link SurfaceSelection#select}): only tops not
 * covered by something directly above are emitted, so e.g. a stair renders as
 * its exposed L. The flat tops/borders draw <b>through walls</b> (depth-off fill
 * layer in {@code WorldOverlayManager}) so any remaining buried surface is
 * visible for debugging. Tint comes from Appearance {@code walkableColor} by
 * default; Debug {@code shadeByDepth} switches to a cyclic BFS-depth hue band
 * ({@link #depthColor}) as a continuity-bug aid. Surfaces within the last two
 * blocks before the flood-radius cutoff blend toward <b>grey</b> (the outermost
 * block fully grey) to signal "increase the
 * radius or re-center" — a selection bounded by a real drop stops short of the
 * radius and stays colored, so a radius cutoff reads differently from a
 * true boundary. By default tops (and their skirts/beams) draw on each block's
 * <b>visible face</b> ({@code visualTopY}) so blocks that render taller than they
 * collide (soul sand, mud) aren't buried; Appearance {@code drawOnVisibleFace}
 * switches to the collision height, which re-floods since the visible top is
 * gathered compute-side (gated on the flag; see {@code SurfaceSelection.visibleTop}).
 * Each edge drops a
 * <b>depth-tested vertical skirt</b> so the
 * selection reads as a 3D mesh and a real drop reads as an open wall, but
 * <b>skirt-diffed</b>: the parts of an edge shared with an equal-height neighbour
 * (an internal edge of a continuous level the greedy merge split) are skipped, so
 * they don't read as false interior walls. See {@code PLAN.md}.
 *
 * <p>The wand is a <b>trigger</b>: right-clicking floods the selection from
 * the block under the crosshair (resolved downward to the first non-empty
 * collision shape) outward across walkable, footprint-adjacent surfaces (height
 * steps within the profile's reach) up to a BFS depth limit (adjustable via
 * shift+scroll), into a persistent {@link SurfaceSelection}, replacing any
 * previous selection; right-clicking nothing clears it. Each surface is
 * drawn as a translucent fill plus its skirts, with an opaque outline added only
 * while crouching (a debug aid that otherwise clutters the view). Every
 * selected block's surface is drawn every frame; the selection persists when you switch
 * items and reappears on re-equip, and is emptied by a clearing right-click or
 * a level change.
 *
 * <p>The selection is published into a {@code volatile} snapshot on every wand
 * action ({@link #publish}); {@link #extract} does no per-frame geometry work
 * (it only tracks the held item and resets on a level change), and {@link #emit}
 * re-emits the snapshot each frame.
 */
public final class CollisionSurfaceOverlay implements WorldOverlay {
	// Lifts the quads just above the block face to avoid z-fighting with the top
	// surface. Kept as small as possible so the top hugs the real surface.
	private static final double Y_OFFSET = 0.002;
	// An opaque outline drawn around each rect so adjacent surfaces (and the
	// sub-rects of a single block) stay visually separable through the fill.
	private static final float BORDER_ALPHA = 1.0f;
	private static final float BORDER_THICKNESS = 0.045f;

	// Debug depth coloring (gated by Configs.shadeByDepth): tops and their skirts
	// are colored by flood BFS distance from the seed (0 = seed), NOT by height. The
	// hue advances a small fixed step per depth ring and WRAPS, so it is a smooth
	// gradient locally (neighbouring rings are near-identical colors — you can read
	// which rects are neighbours) yet still resolves large radii, and a continuity
	// bug reads as a patch whose color breaks the spatial gradient. The step is sized
	// so a full hue cycle spans DEPTH_CYCLE rings (~20): finer than this and adjacent
	// rings were indistinguishable, coarser and it didn't cycle enough for big
	// floods. Depth -1 ("no flood depth") draws grey.
	private static final float DEPTH_HUE_START = 0.66f; // depth 0 hue (blue)
	private static final int DEPTH_CYCLE = 20;          // rings per full hue cycle
	private static final float DEPTH_HUE_STEP = 1.0f / DEPTH_CYCLE;
	private static final float SATURATION = 0.9f;
	private static final float VALUE = 1.0f;
	private static final float[] DEPTH_UNKNOWN_COLOR = {0.5f, 0.5f, 0.5f};
	// Small epsilon reused by the cutoff-ring geometry.
	private static final double FLAT_EPS = 1.0e-6;

	// Vertical skirts dropped from each surface edge so the selection reads as a
	// 3D mesh. Draw height comes from Appearance downSkirtHeight (0 skips draw).
	// Skirts are shaded darker than the top for legibility.
	private static final float SKIRT_SHADE = 0.55f;
	private static final float SKIRT_ALPHA = 0.6f;
	// Tiny uniform outward push of the (square) skirt edges — NOT a dilation: just
	// enough to lift the skirt off the coplanar terrain side face so it doesn't
	// z-fight. Kept tiny so the gap from the top edge stays invisible and skirts
	// don't visibly overlap neighbours.
	private static final double SKIRT_OFFSET = 0.002;

	// Upward (occluder) skirts: drawn where a surface edge borders a wall/ceiling
	// (the compute-side OccluderSpans), solid at the surface top and fading to
	// transparent at the marker top. A lighter shade than the (darker) downward drop
	// skirts so the two read distinctly. Draw height from Appearance upwardSkirtHeight
	// (0 skips draw), clamped to the available wall above the surface.
	private static final float UP_SKIRT_SHADE = 0.85f;
	private static final float UP_SKIRT_ALPHA = 0.7f;

	// Hole beam: a vertical marker rising from the cliff-edge top of a hole span.
	// Routed to the depth-off BEAM layer or depth-tested SKIRT layer by Appearance
	// showBeamsThroughWalls. Color/opacity from holeBeamColor.
	private static final float BEAM_HEIGHT = 4.0f;

	// Cap the downward walk so looking at tall grass over a hole can't scan into
	// the void; resolution also stops at world min-Y.
	private static final int MAX_DOWNWARD_STEPS = 64;

	// Flood depth limit: the maximum BFS hop-count from the seed. Adjustable at
	// runtime via shift+scroll while holding the wand (see adjustRadius), clamped.
	private static final int MIN_RADIUS = 0;
	private static final int MAX_RADIUS = 30;
	private static final int INITIAL_FLOOD_RADIUS = 20;
	// Past this the scroll steps by 2, keeping the high end usable.
	private static final int COARSE_RADIUS = 10;

	// The computed surfaces, recomputed from scratch on each wand action
	// (onUseItem (re)selects/clears/cycles; adjustRadius re-floods).
	private final SurfaceSelection cache = new SurfaceSelection();

	// Current flood radius and the last resolved seed block, so a radius change
	// can re-flood from the same origin. Both touched only on the client thread
	// (onUseItem and the scroll handler). lastSeed is null when nothing is
	// selected.
	private int selectionRadius = INITIAL_FLOOD_RADIUS;
	private BlockPos lastSeed;

	// Active mob profile comes from Configs.mobProfile() (settings source of
	// truth). Sneak+air-click may cycle it when Debug crouchCycleProfile is on.

	// Last level seen on the extraction thread. A change (world unload, dimension
	// switch, disconnect/reconnect) empties the in-memory selection — a
	// self-contained alternative to a manager-side world-unload hook.
	private Level lastLevel;

	// Written on the extraction path, read on the render thread, so volatile.
	// Full draw gate (showSurfaces + profile + wand + non-empty snapshot) sampled
	// once per extract; isVisible() is a field read.
	private volatile boolean visible = false;
	// The opaque per-rect outlines are a debugging aid (they separate adjacent
	// surfaces and sub-rects) but clutter the normal view, so they only draw while
	// crouching. Sampled on the extraction thread, read in emit.
	private volatile boolean crouching = false;
	private volatile List<StandableRect> snapshot = List.of();
	// Upward (occluder) skirt spans, published with the snapshot (compute-side, since
	// they read collision boxes). Read per-frame in emit. See SurfaceSelection.
	private volatile List<OccluderSpan> occluderSnapshot = List.of();
	// Downward drop-skirt spans, published with the snapshot (compute-side, once per
	// select — was a per-frame openSpans scan). Read per-frame in emit. See
	// SurfaceSelection.computeDownSkirts.
	private volatile List<DownSkirtSpan> downSkirtSnapshot = List.of();
	// Hole spans (through-walls beam markers), published with the snapshot
	// (compute-side; the landing scan reads collision boxes). Read per-frame in emit.
	// See SurfaceSelection.computeHoles.
	private volatile List<HoleSpan> holeSnapshot = List.of();

	// Depth-based greying: surfaces near the flood's BFS-depth cutoff (the last 2
	// depth rings) blend toward grey to signal "increase the depth limit"; a
	// selection bounded by a real drop stops short of the limit so it stays colored,
	// making a depth cutoff visually distinct from a true boundary. Published with
	// the snapshot; read per-rect in emit (render thread). The grey ramp is:
	// depth <= limit-2 → no grey; depth == limit-1 → half grey; depth == limit →
	// full grey. Replaces the old spatial (Chebyshev-ring) greying.
	private static final float[] RING_COLOR = {0.5f, 0.5f, 0.5f};
	private volatile int depthLimit;

	@Override
	public String id() {
		return "collision_surface";
	}

	@Override
	public void extract(LevelExtractionContext context) {
		Minecraft client = Minecraft.getInstance();
		Player player = client.player;
		Level level = client.level;

		// The selection is published on every wand action (publish()), so extract
		// does no per-frame geometry work. It samples draw visibility + crouch and
		// resets on a level-identity change (world unload, dimension switch, reconnect).
		if (level != lastLevel) {
			cache.clear();
			lastSeed = null;
			lastLevel = level;
			snapshot = List.of();
			occluderSnapshot = List.of();
			downSkirtSnapshot = List.of();
			holeSnapshot = List.of();
		}

		Item wand = Configs.wandItem();
		boolean holding = player != null
			&& (player.getMainHandItem().is(wand) || player.getOffhandItem().is(wand));
		ShowSurfaces mode = Configs.showSurfaces();
		visible = mode != ShowSurfaces.NEVER
			&& Configs.hasEnabledProfile()
			&& (mode == ShowSurfaces.ALWAYS || holding)
			&& !snapshot.isEmpty();
		// Through-walls tops + crouch borders share this flag; gated by Debug setting.
		crouching = player != null
			&& player.isShiftKeyDown()
			&& Configs.crouchSeeThroughWalls();
	}

	// Publish the current selection into the volatile snapshot emit() reads. Called
	// after every mutation (select/clear/radius/profile) so per-frame work is nil;
	// editing painted terrain therefore needs a re-click to refresh (intended).
	private void publish() {
		snapshot = cache.allRects();
		occluderSnapshot = cache.allOccluders();
		downSkirtSnapshot = cache.allDownSkirts();
		holeSnapshot = cache.allHoles();
		depthLimit = selectionRadius;
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
		return visible;
	}

	/**
	 * Live apply from MaLiLib flood-radius control: update the session radius and
	 * re-flood an active selection so the change shows immediately.
	 */
	public void applyFloodRadius(int radius) {
		int updated = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
		if (updated == selectionRadius) {
			return;
		}
		selectionRadius = updated;
		reselectWithMobProfile();
	}

	/**
	 * Live apply from MaLiLib mob-profile control: re-flood an active selection so
	 * the new entity size shows immediately.
	 */
	public void reselectWithMobProfile() {
		Level level = Minecraft.getInstance().level;
		var profile = Configs.mobProfile();
		if (level != null && lastSeed != null && profile.isPresent()) {
			cache.select(level, lastSeed, selectionRadius, profile.get(), Configs.drawOnVisibleFace());
			publish();
		}
	}

	/** Soft-disabled roster: drop any leftover selection so draw stays off. */
	public void clearSelectionForSoftDisable() {
		cache.clear();
		lastSeed = null;
		publish();
	}

	@Override
	public void onUseItem(Player player) {
		// Right-click with the wand floods the selection from the targeted block
		// (resolved downward to its standable surface) across connected neighbors,
		// replacing the previous selection; right-clicking nothing clears it.
		//
		// The wand may be in either hand. Pick the acting hand main-first, falling
		// back to the off hand ONLY when the main hand is empty (or also a wand):
		// a non-empty main hand is assumed to consume the right-click (place/use), so
		// an off-hand wand doesn't also fire — approximating vanilla's "main acts
		// first, off hand only if main did nothing" without an interaction-result
		// mixin (this is edge-detected off the use key, so the true result is unseen).
		Item wand = Configs.wandItem();
		InteractionHand hand;
		if (player.getMainHandItem().is(wand)) {
			hand = InteractionHand.MAIN_HAND;
		} else if (player.getOffhandItem().is(wand) && player.getMainHandItem().isEmpty()) {
			hand = InteractionHand.OFF_HAND;
		} else {
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
			if (!Configs.hasEnabledProfile()) {
				OverlayManager.radiusIndicator().showProfile("no profiles active");
			} else {
				var profile = Configs.mobProfile();
				if (profile.isPresent()) {
					cache.select(level, start, selectionRadius, profile.get(), Configs.drawOnVisibleFace());
					lastSeed = start;
				}
			}
		} else {
			// Right-click at nothing clears. Soft-disabled: HUD "no profiles active".
			// Otherwise when Debug crouchCycleProfile is on, sneaking advances the
			// roster profile and pings the HUD; lastSeed stays null so there is no
			// re-flood — the new profile takes effect on the next select.
			cache.clear();
			lastSeed = null;
			if (!Configs.hasEnabledProfile()) {
				OverlayManager.radiusIndicator().showProfile("no profiles active");
			} else if (player.isShiftKeyDown() && Configs.crouchCycleProfile()) {
				if (Configs.cycleMobProfile().isPresent()) {
					OverlayManager.radiusIndicator().showProfile(
						Configs.profileDisplayLabel(Configs.activeProfileId())
					);
				}
			}
		}
		publish();
		player.swing(hand);
	}

	// True when scroll should retarget the flood radius instead of switching the
	// hotbar: Debug "crouch to scroll radius" enabled, holding the wand (either
	// hand), and crouching. When the option is off the feature is inactive — scroll
	// always leaves the hotbar alone to vanilla.
	public boolean wantsRadiusScroll() {
		if (!Configs.crouchScrollRadius()) {
			return false;
		}
		Player player = Minecraft.getInstance().player;
		Item wand = Configs.wandItem();
		return player != null
			&& (player.getMainHandItem().is(wand) || player.getOffhandItem().is(wand))
			&& player.isShiftKeyDown();
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
			reselectWithMobProfile();
		}
		return selectionRadius;
	}

	/**
	 * One-shot flood geometry dump for {@code /mobwalk dump}: re-selects from
	 * {@code lastSeed} with debug logging armed. Returns null if there is no
	 * selection; otherwise the post-dump counts for the chat summary.
	 */
	public FloodDebugCounts dumpFloodDebug() {
		Level level = Minecraft.getInstance().level;
		var profile = Configs.mobProfile();
		if (level == null || lastSeed == null || profile.isEmpty()) {
			return null;
		}
		cache.requestDebugDump();
		cache.select(level, lastSeed, selectionRadius, profile.get(), Configs.drawOnVisibleFace());
		publish();
		return new FloodDebugCounts(
			cache.allRects().size(),
			cache.allOccluders().size(),
			cache.allDownSkirts().size(),
			cache.allHoles().size());
	}

	/** Counts returned by {@link #dumpFloodDebug} for the chat status line. */
	public record FloodDebugCounts(int merged, int occluders, int skirts, int holes) {
	}

	@Override
	public void emit(Matrix4fc positionMatrix, BufferBuilder fillBuffer, BufferBuilder skirtBuffer,
			BufferBuilder beamBuffer) {
		List<StandableRect> rects = snapshot;
		if (rects.isEmpty()) {
			return;
		}

		int limit = depthLimit;

		for (StandableRect rect : rects) {
			if (!Configs.showCutoffRing() && inCutoffRing(rect.depth(), limit)) {
				continue;
			}
			float minX = (float) rect.minX();
			float minZ = (float) rect.minZ();
			float maxX = (float) rect.maxX();
			float maxZ = (float) rect.maxZ();
			float y = (float) rect.visualTopY() + (float) Y_OFFSET;

			float[] rgb = greyBlend(surfaceRgb(rect.depth()), rect.depth(), limit);
			float r = rgb[0];
			float g = rgb[1];
			float b = rgb[2];
			float fillAlpha = Configs.walkableColor().a;

			BufferBuilder topBuffer = crouching ? fillBuffer : skirtBuffer;
			quad(topBuffer, positionMatrix, minX, maxX, minZ, maxZ, y, r, g, b, fillAlpha);

			if (crouching) {
				float bx = Math.min(BORDER_THICKNESS, (maxX - minX) * 0.5f);
				float bz = Math.min(BORDER_THICKNESS, (maxZ - minZ) * 0.5f);
				quad(fillBuffer, positionMatrix, minX, maxX, minZ, minZ + bz, y, r, g, b, BORDER_ALPHA);
				quad(fillBuffer, positionMatrix, minX, maxX, maxZ - bz, maxZ, y, r, g, b, BORDER_ALPHA);
				quad(fillBuffer, positionMatrix, minX, minX + bx, minZ, maxZ, y, r, g, b, BORDER_ALPHA);
				quad(fillBuffer, positionMatrix, maxX - bx, maxX, minZ, maxZ, y, r, g, b, BORDER_ALPHA);
			}

		}

		// Downward drop skirts: now published compute-side (openSpans minus the
		// wall/ceiling occluder sub-spans, so a wall edge is not double-skirted),
		// drawn once per span into the depth-tested layer, pushed out by a tiny
		// SKIRT_OFFSET so they clear the coplanar terrain face without z-fighting.
		// Each span carries its edge line, [lo,hi], side, base height, and its
		// surface's flood-depth (the debug color and fade derive here).
		emitDownSkirts(skirtBuffer, positionMatrix);

		// Upward (occluder) skirts: drawn once per published span, into the same
		// depth-tested layer, at Appearance upwardSkirtHeight.
		emitOccluders(skirtBuffer, positionMatrix);

		// Hole beams: depth-off beam layer when showBeamsThroughWalls, else
		// depth-tested skirt layer (occluded by terrain). Emit after skirts so
		// beams still sit above skirt quads when sharing that buffer.
		BufferBuilder holes = Configs.showBeamsThroughWalls() ? beamBuffer : skirtBuffer;
		emitHoles(holes, positionMatrix);
	}

	// Draw a vertical beam rising from each hole span's rim (baseY), clamped to a
	// fixed world height, at holeBeamColor opacity. Caller picks beam vs skirt
	// buffer (Appearance showBeamsThroughWalls).
	private void emitHoles(BufferBuilder beamBuffer, Matrix4fc positionMatrix) {
		if (!Configs.showHoleBeams()) {
			return;
		}
		List<HoleSpan> spans = holeSnapshot;
		if (spans.isEmpty()) {
			return;
		}
		Color4f beam = Configs.holeBeamColor();
		float r = beam.r;
		float g = beam.g;
		float b = beam.b;
		float a = beam.a;
		// HoleSpan doesn't carry a flood-depth, so holes at the cutoff edge can't
		// be depth-suppressed — that's acceptable: depth-limit artifacts are rare
		// at cliff edges (the flood stops mid-surface, not at a drop).
		for (HoleSpan h : spans) {
			// Rise from the rim's render height (visualBaseY).
			float base = (float) h.visualBaseY();
			float top = base + BEAM_HEIGHT;
			float xa;
			float za;
			float xb;
			float zb;
			if (h.alongX()) {
				xa = (float) h.lo();
				xb = (float) h.hi();
				za = (float) h.line();
				zb = (float) h.line();
			} else {
				za = (float) h.lo();
				zb = (float) h.hi();
				xa = (float) h.line();
				xb = (float) h.line();
			}
			vQuad(beamBuffer, positionMatrix, xa, za, xb, zb, top, base,
				r, g, b, a, a);
		}
	}

	// Draw every published downward drop-skirt span: solid over its top half, fading
	// to transparent over the bottom half (so a deep drop doesn't read as a hard
	// floating wall), depth-colored (inherited from its surface) and shaded darker.
	// Spans at the outermost depth ring are suppressed (depth-cutoff artifacts).
	// Appearance downSkirtHeight <= 0 skips the draw entirely.
	private void emitDownSkirts(BufferBuilder skirtBuffer, Matrix4fc positionMatrix) {
		float skirtDepth = (float) Configs.downSkirtHeight();
		if (skirtDepth <= 0.0f) {
			return;
		}
		List<DownSkirtSpan> spans = downSkirtSnapshot;
		if (spans.isEmpty()) {
			return;
		}
		int limit = depthLimit;
		float o = (float) SKIRT_OFFSET;
		for (DownSkirtSpan sp : spans) {
			if (sp.depth() >= limit) {
				continue;
			}
			if (!Configs.showCutoffRing() && inCutoffRing(sp.depth(), limit)) {
				continue;
			}
			float[] rgb = greyBlend(surfaceRgb(sp.depth()), sp.depth(), limit);
			float sr = rgb[0] * SKIRT_SHADE;
			float sg = rgb[1] * SKIRT_SHADE;
			float sb = rgb[2] * SKIRT_SHADE;
			// Hang from the rect's render height (visualBaseY); color still keyed on the
			// collision baseY (palette stable across the mode toggle).
			float yTop = (float) sp.visualBaseY() + (float) Y_OFFSET;
			float yBot = yTop - skirtDepth;
			float push = sp.maxSide() ? o : -o;
			if (sp.alongX()) {
				float z = (float) sp.line() + push;
				fadedSkirt(skirtBuffer, positionMatrix,
					(float) sp.lo() - o, z, (float) sp.hi() + o, z, yTop, yBot, sr, sg, sb);
			} else {
				float x = (float) sp.line() + push;
				fadedSkirt(skirtBuffer, positionMatrix,
					x, (float) sp.lo() - o, x, (float) sp.hi() + o, yTop, yBot, sr, sg, sb);
			}
		}
	}

	// Draw every published upward (occluder) skirt span at Appearance upwardSkirtHeight,
	// solid at the surface top and fading to transparent at the marker top, the
	// marker pulled toward the surface interior by SKIRT_OFFSET to clear the wall face.
	// Spans at the outermost depth ring are suppressed (depth-cutoff artifacts).
	// upwardSkirtHeight <= 0 skips the draw entirely.
	private void emitOccluders(BufferBuilder skirtBuffer, Matrix4fc positionMatrix) {
		float configuredHeight = (float) Configs.upwardSkirtHeight();
		if (configuredHeight <= 0.0f) {
			return;
		}
		List<OccluderSpan> spans = occluderSnapshot;
		if (spans.isEmpty()) {
			return;
		}
		int limit = depthLimit;
		float o = (float) SKIRT_OFFSET;
		for (OccluderSpan span : spans) {
			if (span.depth() >= limit) {
				continue;
			}
			if (!Configs.showCutoffRing() && inCutoffRing(span.depth(), limit)) {
				continue;
			}
			// Rise from the rect's render height (visualBaseY); the wall top (span.topY)
			// is unchanged, so the marker just starts a touch higher.
			float base = (float) span.visualBaseY();
			float available = (float) (span.topY()) - base;
			if (available <= 0.0f) {
				continue;
			}
			float markerHeight = Math.min(configuredHeight, available);
			float yTopMarker = base + markerHeight;

			float[] rgb = greyBlend(surfaceRgb(span.depth()), span.depth(), limit);
			float r = rgb[0] * UP_SKIRT_SHADE;
			float g = rgb[1] * UP_SKIRT_SHADE;
			float b = rgb[2] * UP_SKIRT_SHADE;

			// Nudge toward the surface interior (away from the wall face) to dodge
			// z-fighting; the interior is on the -axis side when the occluder is on +.
			float shift = span.positiveSide() ? -o : o;
			float xa;
			float za;
			float xb;
			float zb;
			if (span.alongX()) {
				float line = (float) span.line() + shift;
				xa = (float) span.lo();
				xb = (float) span.hi();
				za = line;
				zb = line;
			} else {
				float line = (float) span.line() + shift;
				za = (float) span.lo();
				zb = (float) span.hi();
				xa = line;
				xb = line;
			}

			// Solid at the base (T), fading to transparent at the marker top.
			vQuad(skirtBuffer, positionMatrix, xa, za, xb, zb, yTopMarker, base,
				r, g, b, 0.0f, UP_SKIRT_ALPHA);
		}
	}

	// One flat axis-aligned quad over [x0,x1] x [z0,z1] at height y, emitted with
	// both windings (a zero-thickness quad would otherwise be culled from one
	// side). Grey blending (depth-based) is applied per-rect before calling this,
	// so the quad is uniform color.
	private static void quad(BufferBuilder buffer, Matrix4fc matrix,
			float x0, float x1, float z0, float z1, float y,
			float r, float g, float b, float a) {
		buffer.addVertex(matrix, x0, y, z0).setColor(r, g, b, a);
		buffer.addVertex(matrix, x0, y, z1).setColor(r, g, b, a);
		buffer.addVertex(matrix, x1, y, z1).setColor(r, g, b, a);
		buffer.addVertex(matrix, x1, y, z0).setColor(r, g, b, a);

		buffer.addVertex(matrix, x1, y, z0).setColor(r, g, b, a);
		buffer.addVertex(matrix, x1, y, z1).setColor(r, g, b, a);
		buffer.addVertex(matrix, x0, y, z1).setColor(r, g, b, a);
		buffer.addVertex(matrix, x0, y, z0).setColor(r, g, b, a);
	}

	// True for depths in the cutoff-ring band (partial/full grey when shown):
	// depth == limit-1 → half grey; depth >= limit → full grey.
	private static boolean inCutoffRing(int depth, int limit) {
		return depth >= 0 && depth > limit - 2;
	}

	// Blend a base color toward RING_COLOR by how close the rect's BFS depth is to
	// the flood limit (the depth-based replacement of the old spatial ring greying).
	// depth <= limit-2: no grey; depth == limit-1: half grey; depth >= limit: full.
	private static float[] greyBlend(float[] rgb, int depth, int limit) {
		if (depth < 0 || depth <= limit - 2) {
			return rgb;
		}
		float t = Math.max(0.0f, Math.min(1.0f, (depth - (limit - 2)) * 0.5f));
		return new float[] {
			rgb[0] + (RING_COLOR[0] - rgb[0]) * t,
			rgb[1] + (RING_COLOR[1] - rgb[1]) * t,
			rgb[2] + (RING_COLOR[2] - rgb[2]) * t,
		};
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
		buffer.addVertex(matrix, xa, yBot, za).setColor(r, g, b, aBot);
		buffer.addVertex(matrix, xa, yTop, za).setColor(r, g, b, aTop);
		buffer.addVertex(matrix, xb, yTop, zb).setColor(r, g, b, aTop);
		buffer.addVertex(matrix, xb, yBot, zb).setColor(r, g, b, aBot);

		buffer.addVertex(matrix, xb, yBot, zb).setColor(r, g, b, aBot);
		buffer.addVertex(matrix, xb, yTop, zb).setColor(r, g, b, aTop);
		buffer.addVertex(matrix, xa, yTop, za).setColor(r, g, b, aTop);
		buffer.addVertex(matrix, xa, yBot, za).setColor(r, g, b, aBot);
	}

	// Base RGB for a surface/skirt at the given flood depth: Appearance walkable
	// color, or the cyclic depth-hue band when Debug shadeByDepth is on.
	private static float[] surfaceRgb(int depth) {
		if (Configs.shadeByDepth()) {
			return depthColor(depth);
		}
		Color4f c = Configs.walkableColor();
		return new float[] {c.r, c.g, c.b};
	}

	// Map a flood BFS depth (distance from the seed) to RGB: the hue advances a small
	// step (1/DEPTH_CYCLE) per ring and wraps, so it is smooth between neighbouring
	// rings (readable as neighbours) but completes a full cycle every ~DEPTH_CYCLE
	// rings so large floods stay legible; a discontinuity breaks the local gradient.
	// Depth -1 ("no flood depth") is drawn grey. See the DEPTH_* constants.
	private static float[] depthColor(int depth) {
		if (depth < 0) {
			return DEPTH_UNKNOWN_COLOR;
		}
		float hue = DEPTH_HUE_START + DEPTH_HUE_STEP * depth;
		hue -= (float) Math.floor(hue);
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
