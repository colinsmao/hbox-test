package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;

import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.ColKey;
import dev.kelianmao.mobwalk.client.surface.WorldGeometry.FluidKind;
import dev.kelianmao.mobwalk.client.surface.WorldGeometry.WorldBox;

/**
 * Fluid-surface existence contracts
 * (docs/geometry.md "Fluid surfaces"; PLAN M9 Step 1).
 */
final class FluidPlaneTest {
  private static final double EPS = 1.0e-9;
  private static final double SOURCE_HEIGHT = 8.0 / 9.0;
  private static final double REACH = EntityProfile.PLAYER.reach();

  @Test
  void sourceWaterEmitsFluidSurfaceAtFluidHeight() {
    OptionalDouble h = WorldGeometry.fluidSurfaceHeight(FluidKind.WATER, SOURCE_HEIGHT, REACH);
    assertTrue(h.isPresent());
    assertEquals(SOURCE_HEIGHT, h.getAsDouble(), EPS);
  }

  @Test
  void submergedCellEmitsFullHeightFluidSurface() {
    OptionalDouble h = WorldGeometry.fluidSurfaceHeight(FluidKind.WATER, 1.0, REACH);
    assertTrue(h.isPresent());
    assertEquals(1.0, h.getAsDouble(), EPS);
  }

  @Test
  void fallingCellEmitsFullHeightFluidSurface() {
    // Falling water reads getHeight == 1.0, same as a submerged still cell.
    OptionalDouble h = WorldGeometry.fluidSurfaceHeight(FluidKind.WATER, 1.0, REACH);
    assertTrue(h.isPresent());
    assertEquals(1.0, h.getAsDouble(), EPS);
  }

  @Test
  void thinLayerEmitsFluidSurfaceAtZero() {
    // Water levels 1–3 and Overworld lava's level-2 tail sit at or below 0.4 —
    // fluid surface at cell floor so it stays coplanar with the solid underfoot.
    assertEquals(0.0, WorldGeometry.fluidSurfaceHeight(FluidKind.WATER, 0.4, REACH).orElseThrow(),
      EPS);
    assertEquals(0.0, WorldGeometry.fluidSurfaceHeight(FluidKind.WATER, 3.0 / 9.0, REACH).orElseThrow(),
      EPS);
    assertEquals(0.0, WorldGeometry.fluidSurfaceHeight(FluidKind.LAVA, 2.0 / 9.0, REACH).orElseThrow(),
      EPS);
  }

  @Test
  void disabledKindYieldsNoFluidSurface() {
    assertTrue(WorldGeometry.fluidSurfaceHeight(FluidKind.NONE, SOURCE_HEIGHT, REACH).isEmpty());
  }

  @Test
  void fluidColumnSurfacesAreOneApart() {
    // Three stacked full-height fluid surfaces + floor: consecutive tops differ by
    // 1.0; lowest fluid surface is one block above the floor.
    WorldBox floor = solid(0, 61, 0, 61.0, 62.0);
    WorldBox f0 = fluidSurface(0, 62, 0, 1.0, FluidKind.WATER);
    WorldBox f1 = fluidSurface(0, 63, 0, 1.0, FluidKind.WATER);
    WorldBox f2 = fluidSurface(0, 64, 0, SOURCE_HEIGHT, FluidKind.WATER);
    List<StandableRect> tops = new ArrayList<>();
    tops.addAll(expose(f0, 0.0, floor, f0, f1, f2));
    tops.addAll(expose(f1, 0.0, floor, f0, f1, f2));
    tops.addAll(expose(f2, 0.0, floor, f0, f1, f2));
    tops.sort((a, b) -> Double.compare(a.collisionTopY(), b.collisionTopY()));
    assertEquals(3, tops.size());
    assertEquals(63.0, tops.get(0).collisionTopY(), EPS);
    assertEquals(64.0, tops.get(1).collisionTopY(), EPS);
    assertEquals(64.0 + SOURCE_HEIGHT, tops.get(2).collisionTopY(), EPS);
    assertEquals(1.0, tops.get(0).collisionTopY() - 62.0, EPS);
    assertEquals(1.0, tops.get(1).collisionTopY() - tops.get(0).collisionTopY(), EPS);
  }

  @Test
  void pondFloorSurvivesFluidSurface() {
    // 1-deep pond: fluid surface at 8/9 above the water cell floor; solid floor top
    // at the cell floor survives because a fluid surface does not occlude.
    WorldBox floor = solid(0, 63, 0, 63.0, 64.0);
    WorldBox fluidSurface = fluidSurface(0, 64, 0, SOURCE_HEIGHT, FluidKind.WATER);
    List<StandableRect> out = expose(floor, 0.0, fluidSurface, floor);
    assertEquals(1.0, area(out), EPS, "solid top beneath the fluid surface must survive");
  }

  @Test
  void neighbourTopSurvivesRavagerBesideFluidSurface() {
    // Land at (0,*) top 64; water fluid surface at (1,64,*). Fluid does not occlude,
    // so Ravager's dilated land top keeps its full area.
    double halfW = EntityProfile.RAVAGER.width() / 2.0;
    WorldBox land = solid(0, 63, 0, 63.0, 64.0);
    WorldBox fluidSurface = fluidSurface(1, 64, 0, SOURCE_HEIGHT, FluidKind.WATER);
    List<StandableRect> out = expose(land, halfW, land, fluidSurface);
    assertEquals(2.95 * 2.95, area(out), EPS,
      "neighbouring lower solid must keep its full dilated area beside a fluid surface");
  }

  @Test
  void thinFluidSurfaceAtZeroSurvivesBesideSolid() {
    WorldBox stone = solid(0, 64, 0, 64.0, 65.0);
    WorldBox thin = fluidSurface(0, 65, 0, 0.0, FluidKind.LAVA);
    List<StandableRect> stoneOut = expose(stone, 0.0, stone, thin);
    List<StandableRect> fluidOut = expose(thin, 0.0, stone, thin);
    assertEquals(1.0, area(stoneOut), EPS);
    assertEquals(1.0, area(fluidOut), EPS);
    assertEquals(65.0, stoneOut.get(0).collisionTopY(), EPS);
    assertEquals(65.0, fluidOut.get(0).collisionTopY(), EPS);
  }

  private static WorldBox solid(int bx, int by, int bz, double yMin, double yMax) {
    return new WorldBox(bx, by, bz, bx, bz, bx + 1, bz + 1, yMin, yMax, yMax, yMax,
      FluidKind.NONE, true);
  }

  private static WorldBox fluidSurface(int bx, int by, int bz, double height, FluidKind kind) {
    double top = by + height;
    return new WorldBox(bx, by, bz, bx, bz, bx + 1, bz + 1, by, top, top, top, kind, false);
  }

  private static List<StandableRect> expose(WorldBox target, double halfW, WorldBox... column) {
    Map<ColKey, List<WorldBox>> index = new HashMap<>();
    for (WorldBox b : column) {
      index.computeIfAbsent(new ColKey(b.bx(), b.bz()), k -> new ArrayList<>()).add(b);
    }
    List<StandableRect> out = new ArrayList<>();
    SurfaceSelection.exposeBox(target, index, halfW, EntityProfile.PLAYER.height(), out);
    return out;
  }

  private static double area(List<StandableRect> rects) {
    double sum = 0.0;
    for (StandableRect r : rects) {
      sum += (r.maxX() - r.minX()) * (r.maxZ() - r.minZ());
    }
    return sum;
  }
}
