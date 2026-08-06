package dev.kelianmao.mobwalk.client.surface;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Adapter over the {@link ColumnBoxes} port: translate Minecraft block/fluid state
 * into domain geometry ({@link WorldBox}, {@link HazardClass}). Pure flood / skirt /
 * hole logic lives in {@link SurfaceSelection}; emission policy that decides whether
 * a cell produces a fluid surface ({@link #fluidSurfaceHeight}) lives here with the
 * translation that stamps {@link HazardClass} from vanilla fluid tags.
 */
public final class WorldGeometry {
  private WorldGeometry() {
  }

  /**
   * Vanilla {@code LivingEntity.getFluidJumpThreshold()} — at or below this fluid
   * height the fluid surface sits at the cell floor ({@code 0}) so it stays
   * coplanar with the solid underfoot; above it the surface sits at
   * {@code getHeight}.
   */
  static final double FLUID_JUMP_THRESHOLD = 0.4;

  // One collision sub-box in absolute world coords: its (undilated) XZ footprint
  // plus its vertical extent. The arrangement dilates the footprint by W/2 on
  // demand; yMin/yMax drive the spans-above occlusion test. bx/by/bz are the
  // source block (LazyFlood's depth-band and seed-block tests run on these).
  // blockCollisionTop / blockOutlineTop are the SOURCE BLOCK's whole-shape tops
  // (collision vs visible/outline, world Y), carried so exposeBox can raise a
  // standable top to the visible face for render-taller-than-collide blocks (soul
  // sand, mud) without touching any walkability math (see exposeBox / StandableRect).
  // hazard: surface hazard identity (NONE on ordinary solids). occludes: participates
  // in burial/headroom clip — solids true; non-occluding support surfaces (fluid)
  // false. Zero-thickness geometry alone still headroom-occludes; this bit skips clip.
  // Package-private for unit tests (synthetic boxes feed the classifier/headroom).
  record WorldBox(int bx, int by, int bz,
      double minX, double minZ, double maxX, double maxZ, double yMin, double yMax,
      double blockCollisionTop, double blockOutlineTop,
      HazardClass hazard, boolean occludes) {
    // Boxes gathered as occluders/ledges only never become a drawn top, so they
    // default both block tops to yMax (visualTopY then never raises off yMax).
    WorldBox(int bx, int by, int bz,
        double minX, double minZ, double maxX, double maxZ, double yMin, double yMax) {
      this(bx, by, bz, minX, minZ, maxX, maxZ, yMin, yMax, yMax, yMax, HazardClass.NONE, true);
    }

    WorldBox(int bx, int by, int bz,
        double minX, double minZ, double maxX, double maxZ, double yMin, double yMax,
        double blockCollisionTop, double blockOutlineTop) {
      this(bx, by, bz, minX, minZ, maxX, maxZ, yMin, yMax, blockCollisionTop, blockOutlineTop,
        HazardClass.NONE, true);
    }
  }

  // World-read port of the shared WorldSurfaceIndex: the collision boxes of the
  // block at (x,y,z) in absolute WorldBox coords (empty if none). Production wraps
  // the Level read (the flood's producer also fills the visible/outline top);
  // tests inject a synthetic world so the lazy scan window that builds the occluder
  // index (not just exposeBox) is exercised directly.
  @FunctionalInterface
  interface ColumnBoxes {
    List<WorldBox> at(int x, int y, int z);
  }

  // Per-BlockState memo of the block's outline (visible) top, relative to the block
  // origin (i.e. state.getShape().max(Y)); NaN means "no separate outline, don't
  // raise". Populated lazily the first time each distinct state is seen, so the
  // visible-top read (getShape) is paid at most once per block STATE rather than per
  // block instance — no full-block heuristic, so a modded/future block that renders
  // taller than it collides is caught automatically the first time it appears. The
  // property is treated as position-independent (keyed by state only); the handful of
  // context-dependent blocks never have a neighbour-varying TOP raise, so caching the
  // first-seen value is safe in practice. Static so the memo survives across selects;
  // only ever touched on the client thread, ConcurrentHashMap purely for safety.
  private static final Map<BlockState, Double> OUTLINE_TOP_REL = new ConcurrentHashMap<>();

  // Level-backed ColumnBoxes producer: the collision boxes of block (x,y,z) as
  // absolute WorldBoxes carrying the block's collision/outline tops (the outline
  // read is paid only when computeVisualTop is on), plus an optional non-occluding
  // fluid surface (HazardClass on that box only). Shared by the flood, the ledge
  // gather, and the occluder pass so there is one world-read implementation behind
  // WorldSurfaceIndex / OccluderSkirts.computeFrom.
  static ColumnBoxes levelColumnBoxes(Level level, boolean computeVisualTop,
      boolean swimmableFluids) {
    BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();
    return (x, y, z) -> {
      scan.set(x, y, z);
      BlockState state = level.getBlockState(scan);
      FluidState fluidState = state.getFluidState();
      HazardClass hazard = hazardClass(fluidState, swimmableFluids);
      double fluidHeight = fluidState.isEmpty() ? 0.0 : fluidState.getHeight(level, scan);
      OptionalDouble fluidTop = fluidSurfaceHeight(hazard, fluidHeight);

      VoxelShape shape = state.getCollisionShape(level, scan, CollisionContext.empty());
      List<WorldBox> boxes = new ArrayList<>();
      if (fluidTop.isPresent()) {
        double top = y + fluidTop.getAsDouble();
        boxes.add(new WorldBox(x, y, z,
          x, z, x + 1, z + 1,
          y, top, top, top, hazard, false));
      }
      if (shape.isEmpty()) {
        return boxes;
      }
      double blockCollisionTop = y + shape.max(Direction.Axis.Y);
      double blockOutlineTop = visibleTop(level, scan, state, blockCollisionTop, computeVisualTop);
      for (AABB box : shape.toAabbs()) {
        boxes.add(new WorldBox(x, y, z,
          x + box.minX, z + box.minZ, x + box.maxX, z + box.maxZ,
          y + box.minY, y + box.maxY,
          blockCollisionTop, blockOutlineTop, HazardClass.NONE, true));
      }
      return boxes;
    };
  }

  /**
   * Whether a cell emits a fluid surface, and at what height above the block floor.
   * Enabled fluid hazard always emits: at {@code getHeight} when above
   * {@link #FLUID_JUMP_THRESHOLD}, otherwise at {@code 0} (coplanar with the solid
   * underfoot). Escape from fluid onto a solid rim is a climb-budget cap
   * ({@link ClimbRule}), not a change to this plane height.
   */
  static OptionalDouble fluidSurfaceHeight(HazardClass hazard, double fluidHeight) {
    if (hazard == HazardClass.NONE) {
      return OptionalDouble.empty();
    }
    if (fluidHeight <= FLUID_JUMP_THRESHOLD + RectMath.EPS) {
      return OptionalDouble.of(0.0);
    }
    return OptionalDouble.of(fluidHeight);
  }

  // Minecraft FluidState tags → HazardClass. Toggle off or empty/untagged fluid
  // maps to NONE (no fluid surface).
  static HazardClass hazardClass(FluidState fluid, boolean swimmableFluids) {
    if (!swimmableFluids || fluid.isEmpty()) {
      return HazardClass.NONE;
    }
    if (fluid.is(FluidTags.WATER)) {
      return HazardClass.WATER;
    }
    if (fluid.is(FluidTags.LAVA)) {
      return HazardClass.LAVA;
    }
    return HazardClass.NONE;
  }

  // The source block's visible top (world Y), used to raise a standable surface to
  // the face you actually see for render-taller-than-collide blocks (soul sand, mud,
  // cactus, honey, and any modded/future block with the same property). No heuristic:
  // EVERY block state is checked, but the outline shape (getShape) is read at most
  // once per distinct BlockState and memoized in OUTLINE_TOP_REL, so the per-block
  // cost is a map lookup. Returns the collision top when the state has no separate
  // outline (NaN memo). The exposeBox raise rule then decides whether to actually
  // lift (only its block's topmost collision surface, and only when the outline is
  // strictly higher), so a fence (outline 1.0 < collision 1.5) is returned here but
  // not raised there. Gated on computeVisualTop (the Appearance render toggle): off,
  // it returns the collision top without the outline read, so no rect raises and the
  // neighbour split never fires.
  static double visibleTop(Level level, BlockPos pos, BlockState state,
      double blockCollisionTop, boolean computeVisualTop) {
    if (!computeVisualTop) {
      return blockCollisionTop;
    }
    Double outlineRel = OUTLINE_TOP_REL.get(state);
    if (outlineRel == null) {
      VoxelShape outline = state.getShape(level, pos, CollisionContext.empty());
      outlineRel = outline.isEmpty() ? Double.NaN : outline.max(Direction.Axis.Y);
      OUTLINE_TOP_REL.put(state, outlineRel);
    }
    if (Double.isNaN(outlineRel)) {
      return blockCollisionTop;
    }
    return pos.getY() + outlineRel;
  }
}
