package dev.kelianmao.mobwalk.client.widgets;

import java.util.Arrays;
import java.util.List;

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
 * gradient across the selection's height range). Surfaces within the last two
 * blocks before the flood-radius cutoff blend toward <b>grey</b> (the outermost
 * block fully grey) to signal "increase the
 * radius or re-center" — a selection bounded by a real drop stops short of the
 * radius and stays height-colored, so a radius cutoff reads differently from a
 * true boundary. By default tops (and their skirts/beams) draw on each block's
 * <b>visible face</b> ({@code visualTopY}) so blocks that render taller than they
 * collide (soul sand, mud) aren't buried; a standalone key (default {@code V})
 * toggles this against the true collision height ({@link #toggleVisualTop}), which
 * re-floods since the visible top is gathered compute-side. Each edge drops a
 * <b>depth-tested vertical skirt</b> so the
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

	// Height coloring: hue ramp from violet (lowest) through blue, green, yellow to
	// orange (highest). Red is reserved for hole beams and never reached by this ramp.
	private static final float HUE_LOW = 0.75f;   // violet (lowest surfaces)
	private static final float HUE_HIGH = 0.08f;  // orange (highest surfaces)
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

	// Upward (occluder) skirts: drawn where a surface edge borders a wall/ceiling
	// (the compute-side OccluderSpans), solid at the surface top and fading to
	// transparent at the marker top. A lighter shade than the (darker) downward drop
	// skirts so the two read distinctly. Four debug styles cycle on a keybind
	// (cycleOccluderStyle) so the final look can be A/B'd in-game; the heights below
	// are the candidate looks (tiny default).
	private static final float UP_SKIRT_SHADE = 0.85f;
	private static final float UP_SKIRT_ALPHA = 0.7f;
	private static final int OCC_STYLE_COUNT = 4;
	private static final int OCC_STYLE_TINY = 0;
	private static final int OCC_STYLE_HALF = 1;
	private static final int OCC_STYLE_FULL = 2;
	private static final int OCC_STYLE_BOLD = 3;
	private static final double OCC_TINY_HEIGHT = 0.15;
	private static final double OCC_HALF_HEIGHT = 0.5;
	private static final double OCC_BOLD_HEIGHT = 0.1;

	// Hole beam: a through-walls vertical marker rising from the cliff-edge top of a
	// hole span (drawn in the depth-off FILLED layer so it reads through terrain).
	// Solid-ish red at the base, fading out toward a fixed world height so it doesn't
	// read as a hard wall. Per-edge for now (Step 3); Step 4 coalesces to one beam per
	// hole region and tunes this look.
	private static final float BEAM_HEIGHT = 4.0f;
	private static final float BEAM_R = 0.95f;
	private static final float BEAM_G = 0.15f;
	private static final float BEAM_B = 0.1f;
	private static final float BEAM_ALPHA_BASE = 0.85f;
	private static final float BEAM_ALPHA_TOP = 0.05f;

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
	// Debug A/B style for the occluder markers (tiny / half-block / full / bold-line),
	// incremented by a standalone keybind (cycleOccluderStyle, client thread), read in
	// emit (render thread). A render-thread-only choice, so it does not touch the
	// published spans.
	private volatile int occluderStyle = OCC_STYLE_TINY;

	// Whether standable tops render on the block's VISIBLE face rather than the
	// collision top, so render-taller-than-collide blocks (soul sand, mud) aren't
	// buried. Default on (the fix). This is a COMPUTE-side flag, not a per-draw one: it
	// is passed into select() (the visible top is gathered there, gated on it — see
	// SurfaceSelection.visibleTop) and the chosen height is baked into each rect's
	// visualTopY, which emit always draws. Toggling therefore re-floods from lastSeed
	// (toggleVisualTop). Touched only on the client thread (select/toggle); emit no
	// longer reads it, so volatile is just belt-and-suspenders.
	private volatile boolean useVisualTop = true;

	// Outer-ring greying: surfaces within the last block before the flood-radius
	// cutoff are blended toward grey to signal "increase the radius or re-center".
	// A selection bounded by a real drop stops short of the radius, so it never
	// reaches the ring and stays height-colored — that's how a true boundary reads
	// differently from a radius cutoff. Published with the snapshot (client
	// thread), read per-vertex in emit (render thread). ringEnd > ringStart always.
	private static final float[] RING_COLOR = {0.5f, 0.5f, 0.5f};
	private volatile double ringCenterX;
	private volatile double ringCenterZ;
	// The grey buffer is two blocks wide: the color ramps from height-colored to
	// grey over the inner block [ringStart, ringFull], and the outermost block
	// [ringFull, ringEnd] is solid grey. Keeping ringFull one block inside ringEnd
	// is what makes the whole outer block read as fully grey.
	private volatile double ringStart;
	private volatile double ringFull = 1.0;
	private volatile double ringEnd = 2.0;

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
			occluderSnapshot = List.of();
			downSkirtSnapshot = List.of();
			holeSnapshot = List.of();
		}

		holdingStick = player != null && player.getMainHandItem().is(Items.STICK);
		crouching = player != null && player.isShiftKeyDown();
	}

	// Publish the current selection into the volatile snapshot emit() reads. Called
	// after every mutation (select/clear/radius/profile) so per-frame work is nil;
	// editing painted terrain therefore needs a re-click to refresh (intended).
	private void publish() {
		snapshot = cache.allRects();
		occluderSnapshot = cache.allOccluders();
		downSkirtSnapshot = cache.allDownSkirts();
		holeSnapshot = cache.allHoles();
		// The cutoff ring sits inside the window's outer painted extent: the far
		// edge of the outermost column (seed ± radius), grown by the entity
		// half-width, measured (Chebyshev) from the seed block center. The grey
		// buffer is two blocks deep — a ramp block then a solid-grey outer block.
		if (lastSeed != null) {
			double halfW = profile.width() / 2.0;
			ringCenterX = lastSeed.getX() + 0.5;
			ringCenterZ = lastSeed.getZ() + 0.5;
			ringEnd = selectionRadius + 0.5 + halfW;
			ringFull = ringEnd - 1.0;
			ringStart = ringEnd - 2.0;
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
			cache.select(level, start, selectionRadius, profile, useVisualTop);
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
				cache.select(level, lastSeed, selectionRadius, profile, useVisualTop);
				publish();
			}
		}
		return selectionRadius;
	}

	// Advance the occluder-marker debug style (wrapping). Bound to a standalone key in
	// MobWalkClient; a pure render-thread choice, so it does not touch the published
	// spans (no re-flood). Returns the new style index for the on-screen ping.
	public int cycleOccluderStyle() {
		int next = (occluderStyle + 1) % OCC_STYLE_COUNT;
		occluderStyle = next;
		return next;
	}

	// Flip the visible-face-top render mode (soul sand / mud drawn on the face you see
	// vs at their true collision top) and re-flood from the last seed. Unlike the pure
	// render-side cycleOccluderStyle, this MUST recompute: the visible top is gathered
	// compute-side and gated on this flag (see SurfaceSelection.visibleTop), so the
	// snapshot has to be rebuilt. Toggling is rare, so the re-flood cost is a non-issue.
	// Returns the new state for the on-screen ping.
	public boolean toggleVisualTop() {
		useVisualTop = !useVisualTop;
		Level level = Minecraft.getInstance().level;
		if (level != null && lastSeed != null) {
			cache.select(level, lastSeed, selectionRadius, profile, useVisualTop);
			publish();
		}
		return useVisualTop;
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

		for (StandableRect rect : rects) {
			// Tops/borders draw at the TRUE rect bounds, so adjacent surfaces tile
			// exactly instead of overlapping (overlapping translucent quads
			// double-blend into visible seams). Only the skirts are nudged out by a
			// tiny SKIRT_OFFSET to dodge z-fighting the terrain face (not a dilation).
			float minX = (float) rect.minX();
			float minZ = (float) rect.minZ();
			float maxX = (float) rect.maxX();
			float maxZ = (float) rect.maxZ();
			// Draw at the rect's render height (visualTopY): the block's visible face,
			// baked in at flood time. It equals the collision topY when the visible-face
			// mode is off (the raise isn't computed then — see SurfaceSelection.visibleTop)
			// or for the vast majority of blocks, so emit never branches on the mode. The
			// height COLOR below stays keyed on the collision topY (palette stable).
			float y = (float) rect.visualTopY() + (float) Y_OFFSET;

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

		}

		// Downward drop skirts: now published compute-side (openSpans minus the
		// wall/ceiling occluder sub-spans, so a wall edge is not double-skirted),
		// drawn once per span into the depth-tested layer, pushed out by a tiny
		// SKIRT_OFFSET so they clear the coplanar terrain face without z-fighting.
		// Each span carries its edge line, [lo,hi], side, and base height; the height
		// color, the fade, and the depth are derived here the same as before.
		emitDownSkirts(skirtBuffer, positionMatrix, minTopY, maxTopY, skirtDepth);

		// Upward (occluder) skirts: drawn once per published span, into the same
		// depth-tested layer, in the active debug style.
		emitOccluders(skirtBuffer, positionMatrix, minTopY, maxTopY, skirtDepth);

		// Hole beams: through-walls markers at each hole rim, into the depth-off
		// FILLED layer so they read even behind terrain.
		emitHoles(fillBuffer, positionMatrix);
	}

	// Draw a through-walls vertical beam rising from each hole span's rim (baseY),
	// clamped to a fixed world height, solid-ish at the base and fading out at the
	// top. Into the depth-off FILLED buffer so it is visible behind terrain.
	private void emitHoles(BufferBuilder fillBuffer, Matrix4fc positionMatrix) {
		List<HoleSpan> spans = holeSnapshot;
		if (spans.isEmpty()) {
			return;
		}
		for (HoleSpan h : spans) {
			if (isAtOuterEdge(h.alongX(), h.maxSide(), h.line(), h.lo(), h.hi())) {
				continue;
			}
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
			vQuad(fillBuffer, positionMatrix, xa, za, xb, zb, top, base,
				BEAM_R, BEAM_G, BEAM_B, BEAM_ALPHA_TOP, BEAM_ALPHA_BASE);
		}
	}

	// True if a span sits at the very outermost edge of the selection (at or past
	// ringEnd on the Chebyshev metric). Skirts and holes there are suppressed: they
	// are artifacts of the radius cutoff, not real geometry.
	private boolean isAtOuterEdge(boolean alongX, boolean maxSide, double line, double lo, double hi) {
		double perpDist = alongX
			? Math.abs(line - ringCenterZ)
			: Math.abs(line - ringCenterX);
		if (perpDist >= ringEnd - FLAT_EPS) {
			return true;
		}
		double varyLo = alongX
			? Math.abs(lo - ringCenterX)
			: Math.abs(lo - ringCenterZ);
		double varyHi = alongX
			? Math.abs(hi - ringCenterX)
			: Math.abs(hi - ringCenterZ);
		return Math.min(varyLo, varyHi) >= ringEnd - FLAT_EPS;
	}

	// Draw every published downward drop-skirt span: solid over its top half, fading
	// to transparent over the bottom half (so a deep drop doesn't read as a hard
	// floating wall), height-colored and shaded like the surface it hangs from.
	private void emitDownSkirts(BufferBuilder skirtBuffer, Matrix4fc positionMatrix,
			double minTopY, double maxTopY, float skirtDepth) {
		List<DownSkirtSpan> spans = downSkirtSnapshot;
		if (spans.isEmpty()) {
			return;
		}
		float o = (float) SKIRT_OFFSET;
		for (DownSkirtSpan sp : spans) {
			if (isAtOuterEdge(sp.alongX(), sp.maxSide(), sp.line(), sp.lo(), sp.hi())) {
				continue;
			}
			float[] rgb = heightColor(sp.baseY(), minTopY, maxTopY);
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

	// Draw every published upward (occluder) skirt span in the active debug style.
	// Solid at the surface top (baseY), fading to transparent at the marker top, the
	// marker pulled toward the surface interior by SKIRT_OFFSET to clear the wall face.
	private void emitOccluders(BufferBuilder skirtBuffer, Matrix4fc positionMatrix,
			double minTopY, double maxTopY, float skirtClamp) {
		List<OccluderSpan> spans = occluderSnapshot;
		if (spans.isEmpty()) {
			return;
		}
		int style = occluderStyle;
		float o = (float) SKIRT_OFFSET;
		for (OccluderSpan span : spans) {
			// Rise from the rect's render height (visualBaseY); the wall top (span.topY)
			// is unchanged, so the marker just starts a touch higher.
			float base = (float) span.visualBaseY();
			float available = (float) (span.topY()) - base;
			if (available <= 0.0f) {
				continue;
			}
			float markerHeight = switch (style) {
				case OCC_STYLE_HALF -> (float) OCC_HALF_HEIGHT;
				case OCC_STYLE_FULL -> skirtClamp;
				case OCC_STYLE_BOLD -> (float) OCC_BOLD_HEIGHT;
				default -> (float) OCC_TINY_HEIGHT;
			};
			markerHeight = Math.min(markerHeight, available);
			float yTopMarker = base + markerHeight;

			float[] rgb = heightColor(span.baseY(), minTopY, maxTopY);
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

			if (style == OCC_STYLE_BOLD) {
				// A crisp opaque line (no fade), marking the wall edge.
				vQuad(skirtBuffer, positionMatrix, xa, za, xb, zb, yTopMarker, base,
					r, g, b, BORDER_ALPHA, BORDER_ALPHA);
			} else {
				// Solid at the base (T), fading to transparent at the marker top.
				vQuad(skirtBuffer, positionMatrix, xa, za, xb, zb, yTopMarker, base,
					r, g, b, 0.0f, UP_SKIRT_ALPHA);
			}
		}
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

	// The flat top, split into sub-quads at BOTH cutoff-ring squares (the lines
	// |x-center| == ringStart / ringFull, and likewise for z). The grey fade is a
	// per-vertex color, so a single huge quad would smear the ramp across its whole
	// length; splitting at ringStart keeps the ramp inside the inner buffer block,
	// and splitting at ringFull makes the outer block a crisp solid-grey quad (both
	// its edges pin to full grey). The interior stays one fully-colored quad.
	private void fadedTop(BufferBuilder buffer, Matrix4fc matrix,
			float minX, float maxX, float minZ, float maxZ, float y,
			float r, float g, float b, float a) {
		float[] xs = breakpoints(minX, maxX,
			(float) (ringCenterX - ringFull), (float) (ringCenterX - ringStart),
			(float) (ringCenterX + ringStart), (float) (ringCenterX + ringFull));
		float[] zs = breakpoints(minZ, maxZ,
			(float) (ringCenterZ - ringFull), (float) (ringCenterZ - ringStart),
			(float) (ringCenterZ + ringStart), (float) (ringCenterZ + ringFull));
		for (int i = 0; i + 1 < xs.length; i++) {
			for (int j = 0; j + 1 < zs.length; j++) {
				quad(buffer, matrix, xs[i], xs[i + 1], zs[j], zs[j + 1], y, r, g, b, a);
			}
		}
	}

	// Sorted span ends [lo, hi] plus any of the given cut coordinates lying strictly
	// inside, so the span is split where it crosses a cutoff-ring boundary (the
	// ramp-start and full-grey ring lines). Cuts are order-independent and
	// deduplicated (two ring lines can coincide on tiny selections).
	private static float[] breakpoints(float lo, float hi, float... cuts) {
		float[] pts = new float[cuts.length + 2];
		int n = 0;
		pts[n++] = lo;
		pts[n++] = hi;
		for (float c : cuts) {
			if (c > lo + 1.0e-4f && c < hi - 1.0e-4f) {
				pts[n++] = c;
			}
		}
		pts = Arrays.copyOf(pts, n);
		Arrays.sort(pts);
		float[] out = new float[n];
		int m = 0;
		for (float p : pts) {
			if (m == 0 || p > out[m - 1] + 1.0e-4f) {
				out[m++] = p;
			}
		}
		return Arrays.copyOf(out, m);
	}

	// Single emission choke point: blends the vertex color toward RING_COLOR by how
	// deep it is into the cutoff ring (0 one block in, 1 at the window edge), so
	// every layer (top, border, skirt) greys out together near the radius limit.
	private void vertex(BufferBuilder buffer, Matrix4fc matrix, float x, float y, float z,
			float r, float g, float b, float a) {
		double d = Math.max(Math.abs(x - ringCenterX), Math.abs(z - ringCenterZ));
		double t = Math.max(0.0, Math.min(1.0, (d - ringStart) / (ringFull - ringStart)));
		// Ease the ramp up (sqrt) so grey saturates early within the inner ramp
		// block, staying pinned to 0 at ringStart (no bleed inward); the clamp pins
		// it to 1 from ringFull outward, so the whole outer block is solid grey.
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

	// Map a surface height to RGB: a hue ramp from violet (lowest) to orange
	// (highest) across the selection's [minTopY, maxTopY]; never reaches red.
	private static float[] heightColor(double topY, double minTopY, double maxTopY) {
		double range = maxTopY - minTopY;
		float t = range < FLAT_EPS ? 0.5f : (float) ((topY - minTopY) / range);
		float hue = HUE_LOW + (HUE_HIGH - HUE_LOW) * t;
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
