package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.ColKey;
import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.FluidKind;
import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.WorldBox;

/**
 * Fluid role contract: a fluid box supplies a standable top and clips nothing
 * (docs/geometry.md "Fluid surfaces"; PLAN M9 Step 1).
 */
final class FluidClipContractTest {
  private static final double EPS = 1.0e-9;
  private static final double SOURCE_HEIGHT = 8.0 / 9.0;
  private static final double HALF_W = EntityProfile.RAVAGER.width() / 2.0;
  private static final double HEIGHT = EntityProfile.RAVAGER.height();

  @Test
  void fluidPlateProvidesStandableTop() {
    WorldBox plate = plate(0, 64, 0, SOURCE_HEIGHT, FluidKind.WATER);
    List<StandableRect> out = expose(plate, 0.0, HEIGHT, plate);
    assertEquals(1, out.size());
    assertEquals(64.0 + SOURCE_HEIGHT, out.get(0).collisionTopY(), EPS);
    assertEquals(1.0, area(out), EPS);
  }

  @Test
  void neighbouringLowerSolidKeepsDilatedAreaUnderRavager() {
    WorldBox land = solid(0, 63, 0, 63.0, 64.0);
    WorldBox plate = plate(1, 64, 0, SOURCE_HEIGHT, FluidKind.WATER);
    List<StandableRect> out = expose(land, HALF_W, HEIGHT, land, plate);
    assertEquals(2.95 * 2.95, area(out), EPS);
  }

  @Test
  void solidTopDirectlyBeneathPlateSurvives() {
    WorldBox floor = solid(0, 63, 0, 63.0, 64.0);
    WorldBox plate = plate(0, 64, 0, SOURCE_HEIGHT, FluidKind.WATER);
    List<StandableRect> out = expose(floor, 0.0, HEIGHT, floor, plate);
    assertEquals(1.0, area(out), EPS);
  }

  @Test
  void floorUnderPlateKeepsHeadroom() {
    // Floor at T=63; plate in the cell above starts at yMin=64, inside the standing
    // column (63, 63+2.2]. A solid ceiling there would bury via headroom; a fluid
    // plate must not (no volume).
    WorldBox floor = solid(0, 62, 0, 62.0, 63.0);
    WorldBox plate = plate(0, 64, 0, SOURCE_HEIGHT, FluidKind.WATER);
    List<StandableRect> out = expose(floor, 0.0, HEIGHT, floor, plate);
    assertEquals(1.0, area(out), EPS, "fluid plate must not rob headroom");
  }

  @Test
  void solidShoreDilatesIntoFluidPlate() {
    // Exposing the plate beside a solid shore: solid clips dilated, pulling the swim
    // region back by W/2 from the shore (same as a wall).
    WorldBox plate = plate(0, 64, 0, SOURCE_HEIGHT, FluidKind.WATER);
    WorldBox shore = solid(1, 64, 0, 64.0, 65.0);
    List<StandableRect> out = expose(plate, HALF_W, HEIGHT, plate, shore);
    // Plate dilated [-0.975, 1.975]^2, shore dilated [0.025, 2.975]x[-0.975, 1.975]
    // clips the plate's +X side back to x=0.025.
    assertEquals((0.025 - (-0.975)) * 2.95, area(out), EPS);
  }

  @Test
  void shoreClipMeetsDilatedRimComplementarity() {
    // Surviving fluid edge meets the shore's dilated rim — no gap, so a flush pond
    // rim is a landing rather than a hole.
    WorldBox plate = plate(0, 64, 0, SOURCE_HEIGHT, FluidKind.WATER);
    WorldBox shore = solid(1, 64, 0, 64.0, 65.0);
    List<StandableRect> fluidOut = expose(plate, HALF_W, HEIGHT, plate, shore);
    List<StandableRect> shoreOut = expose(shore, HALF_W, HEIGHT, plate, shore);
    assertEquals(1, fluidOut.size());
    assertEquals(1, shoreOut.size());
    assertEquals(0.025, fluidOut.get(0).maxX(), EPS);
    assertEquals(0.025, shoreOut.get(0).minX(), EPS);
  }

  private static WorldBox solid(int bx, int by, int bz, double yMin, double yMax) {
    return new WorldBox(bx, by, bz, bx, bz, bx + 1, bz + 1, yMin, yMax, yMax, yMax,
      FluidKind.NONE, true);
  }

  private static WorldBox plate(int bx, int by, int bz, double height, FluidKind kind) {
    double top = by + height;
    return new WorldBox(bx, by, bz, bx, bz, bx + 1, bz + 1, by, top, top, top, kind, false);
  }

  private static List<StandableRect> expose(WorldBox target, double halfW, double height,
      WorldBox... boxes) {
    Map<ColKey, List<WorldBox>> index = new HashMap<>();
    for (WorldBox b : boxes) {
      index.computeIfAbsent(new ColKey(b.bx(), b.bz()), k -> new ArrayList<>()).add(b);
    }
    List<StandableRect> out = new ArrayList<>();
    SurfaceSelection.exposeBox(target, index, halfW, height, out);
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
