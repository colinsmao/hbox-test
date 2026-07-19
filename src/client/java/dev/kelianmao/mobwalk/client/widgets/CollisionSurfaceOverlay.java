package dev.kelianmao.mobwalk.client.widgets;

import java.util.List;

import dev.kelianmao.mobwalk.client.Configs;
import dev.kelianmao.mobwalk.client.Configs.ShowSurfaces;
import dev.kelianmao.mobwalk.client.DownSkirtSpan;
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

/**
 * Input/lifecycle driver for standable-surface selection: wand select/clear,
 * radius/profile re-flood, publish into {@code volatile} snapshots, and
 * visibility. Geometry emission is delegated to {@link SurfaceEmitter}.
 *
 * <p>The wand is a <b>trigger</b>: right-clicking floods the selection from
 * the block under the crosshair (resolved downward to the first non-empty
 * collision shape) outward across walkable, footprint-adjacent surfaces (height
 * steps within the profile's reach) up to a BFS depth limit (adjustable via
 * shift+scroll), into a persistent {@link SurfaceSelection}, replacing any
 * previous selection; right-clicking nothing clears it. The surfaces are
 * occlusion-aware (computed in {@link SurfaceSelection#select}): only tops not
 * covered by something directly above are selected, so e.g. a stair yields its
 * exposed L. By default tops (and their skirts/beams) draw on each block's
 * <b>visible face</b> ({@code visualTopY}); Appearance {@code drawOnVisibleFace}
 * switches to the collision height, which re-floods since the visible top is
 * gathered compute-side (gated on the flag; see {@code SurfaceSelection.visibleTop}).
 *
 * <p>The selection is published into a {@code volatile} snapshot on every wand
 * action ({@link #publish}); {@link #extract} does no per-frame geometry work
 * (it only tracks visibility/crouch and resets on a level change), and
 * {@link #emit} forwards the snapshot to {@link SurfaceEmitter} each frame.
 */
public final class CollisionSurfaceOverlay implements WorldOverlay {
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
  // the snapshot; read per-rect in emit (render thread).
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
    SurfaceEmitter.emit(positionMatrix, fillBuffer, skirtBuffer, beamBuffer,
      snapshot, occluderSnapshot, downSkirtSnapshot, holeSnapshot, depthLimit, crouching);
  }
}
