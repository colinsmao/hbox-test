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
import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.FluidKind;
import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.WorldBox;

/**
 * Plate-existence contracts for fluid swim planes
 * (docs/geometry.md "Fluid surfaces"; PLAN M9 Step 1).
 */
final class FluidPlaneTest {
  private static final double EPS = 1.0e-9;
  private static final double SOURCE_HEIGHT = 8.0 / 9.0;
  private static final double REACH = EntityProfile.PLAYER.reach();

  @Test
  void sourceWaterEmitsPlateAtFluidHeight() {
    OptionalDouble h = SurfaceSelection.plateHeight(FluidKind.WATER, SOURCE_HEIGHT, REACH);
    assertTrue(h.isPresent());
    assertEquals(SOURCE_HEIGHT, h.getAsDouble(), EPS);
  }

  @Test
  void submergedCellEmitsFullHeightPlate() {
    OptionalDouble h = SurfaceSelection.plateHeight(FluidKind.WATER, 1.0, REACH);
    assertTrue(h.isPresent());
    assertEquals(1.0, h.getAsDouble(), EPS);
  }

  @Test
  void fallingCellEmitsFullHeightPlate() {
    // Falling water reads getHeight == 1.0, same as a submerged still cell.
    OptionalDouble h = SurfaceSelection.plateHeight(FluidKind.WATER, 1.0, REACH);
    assertTrue(h.isPresent());
    assertEquals(1.0, h.getAsDouble(), EPS);
  }

  @Test
  void thinLayerEmitsPlateAtZero() {
    // Water levels 1–3 and Overworld lava's level-2 tail sit at or below 0.4 —
    // plate at cell floor so it stays coplanar with the solid underfoot.
    assertEquals(0.0, SurfaceSelection.plateHeight(FluidKind.WATER, 0.4, REACH).orElseThrow(), EPS);
    assertEquals(0.0, SurfaceSelection.plateHeight(FluidKind.WATER, 3.0 / 9.0, REACH).orElseThrow(),
      EPS);
    assertEquals(0.0, SurfaceSelection.plateHeight(FluidKind.LAVA, 2.0 / 9.0, REACH).orElseThrow(),
      EPS);
  }

  @Test
  void disabledKindYieldsNoPlate() {
    assertTrue(SurfaceSelection.plateHeight(FluidKind.NONE, SOURCE_HEIGHT, REACH).isEmpty());
  }

  @Test
  void fluidColumnPlanesAreOneApart() {
    // Three stacked full-height plates + floor: consecutive tops differ by 1.0;
    // lowest plate is one block above the floor.
    WorldBox floor = solid(0, 61, 0, 61.0, 62.0);
    WorldBox p0 = plate(0, 62, 0, 1.0, FluidKind.WATER);
    WorldBox p1 = plate(0, 63, 0, 1.0, FluidKind.WATER);
    WorldBox p2 = plate(0, 64, 0, SOURCE_HEIGHT, FluidKind.WATER);
    List<StandableRect> tops = new ArrayList<>();
    tops.addAll(expose(p0, 0.0, floor, p0, p1, p2));
    tops.addAll(expose(p1, 0.0, floor, p0, p1, p2));
    tops.addAll(expose(p2, 0.0, floor, p0, p1, p2));
    tops.sort((a, b) -> Double.compare(a.collisionTopY(), b.collisionTopY()));
    assertEquals(3, tops.size());
    assertEquals(63.0, tops.get(0).collisionTopY(), EPS);
    assertEquals(64.0, tops.get(1).collisionTopY(), EPS);
    assertEquals(64.0 + SOURCE_HEIGHT, tops.get(2).collisionTopY(), EPS);
    assertEquals(1.0, tops.get(0).collisionTopY() - 62.0, EPS);
    assertEquals(1.0, tops.get(1).collisionTopY() - tops.get(0).collisionTopY(), EPS);
  }

  @Test
  void pondFloorSurvivesPlate() {
    // 1-deep pond: plate at 8/9 above the water cell floor; solid floor top at the
    // cell floor survives because a fluid plate does not occlude.
    WorldBox floor = solid(0, 63, 0, 63.0, 64.0);
    WorldBox plate = plate(0, 64, 0, SOURCE_HEIGHT, FluidKind.WATER);
    List<StandableRect> out = expose(floor, 0.0, plate, floor);
    assertEquals(1.0, area(out), EPS, "solid top beneath the plate must survive");
  }

  @Test
  void neighbourTopSurvivesRavagerBesidePlate() {
    // Land at (0,*) top 64; water plate at (1,64,*). Fluid does not occlude, so
    // Ravager's dilated land top keeps its full area.
    double halfW = EntityProfile.RAVAGER.width() / 2.0;
    WorldBox land = solid(0, 63, 0, 63.0, 64.0);
    WorldBox plate = plate(1, 64, 0, SOURCE_HEIGHT, FluidKind.WATER);
    List<StandableRect> out = expose(land, halfW, land, plate);
    assertEquals(2.95 * 2.95, area(out), EPS,
      "neighbouring lower solid must keep its full dilated area beside a fluid plate");
  }

  @Test
  void thinPlateAtZeroSurvivesBesideSolid() {
    WorldBox stone = solid(0, 64, 0, 64.0, 65.0);
    WorldBox thin = plate(0, 65, 0, 0.0, FluidKind.LAVA);
    List<StandableRect> stoneOut = expose(stone, 0.0, stone, thin);
    List<StandableRect> plateOut = expose(thin, 0.0, stone, thin);
    assertEquals(1.0, area(stoneOut), EPS);
    assertEquals(1.0, area(plateOut), EPS);
    assertEquals(65.0, stoneOut.get(0).collisionTopY(), EPS);
    assertEquals(65.0, plateOut.get(0).collisionTopY(), EPS);
  }

  private static WorldBox solid(int bx, int by, int bz, double yMin, double yMax) {
    return new WorldBox(bx, by, bz, bx, bz, bx + 1, bz + 1, yMin, yMax, yMax, yMax,
      FluidKind.NONE, true);
  }

  private static WorldBox plate(int bx, int by, int bz, double height, FluidKind kind) {
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
