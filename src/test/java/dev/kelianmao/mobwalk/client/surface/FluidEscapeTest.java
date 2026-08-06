package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Escape-cap contract of {@link ClimbRule} (docs/geometry.md Escape cap; PLAN M9
 * Step 3). Pure doubles — no world.
 */
final class FluidEscapeTest {
  private static final double REACH = EntityProfile.PLAYER.reach();
  private static final double DEFAULT_ESCAPE = 0.375; // 6/16
  private static final double SOURCE_TOP = 8.0 / 9.0;
  private static final double DROP = ClimbRule.FLUID_SURFACE_DROP;

  private static StandableRect solid(double topY) {
    return new StandableRect(0, 0, 1, 1, topY, topY, HazardClass.NONE);
  }

  private static StandableRect water(double topY) {
    return new StandableRect(0, 0, 1, 1, topY, topY, HazardClass.WATER);
  }

  private static StandableRect lava(double topY) {
    return new StandableRect(0, 0, 1, 1, topY, topY, HazardClass.LAVA);
  }

  @Test
  void noneNoneUsesPlainReach() {
    ClimbRule climb = new ClimbRule(REACH, DEFAULT_ESCAPE);
    assertTrue(climb.climbs(solid(0.0), solid(REACH)));
    assertFalse(climb.climbs(solid(0.0), solid(REACH + 0.01)));
  }

  @Test
  void fluidToSolidIsCapped() {
    ClimbRule climb = new ClimbRule(REACH, DEFAULT_ESCAPE);
    double budget = SOURCE_TOP + DROP + DEFAULT_ESCAPE;
    assertTrue(climb.climbs(water(SOURCE_TOP), solid(budget)));
    assertFalse(climb.climbs(water(SOURCE_TOP), solid(budget + 0.01)));
  }

  @Test
  void fluidToFluidUsesPlainReach() {
    ClimbRule climb = new ClimbRule(REACH, 0.0);
    assertTrue(climb.climbs(water(62.0), water(63.0)));
    assertTrue(climb.climbs(lava(10.0), water(10.0 + REACH)));
  }

  @Test
  void solidToFluidUsesPlainReach() {
    ClimbRule climb = new ClimbRule(REACH, 0.0);
    assertTrue(climb.climbs(solid(0.0), water(REACH)));
    assertFalse(climb.climbs(solid(0.0), water(REACH + 0.01)));
  }

  @Test
  void onlyFluidKindsTriggerEscapeCap() {
    assertTrue(HazardClass.WATER.isFluid());
    assertTrue(HazardClass.LAVA.isFluid());
    assertFalse(HazardClass.NONE.isFluid());
  }

  @Test
  void landClampKeepsEscapeNoEasierThanReach() {
    // Escape alone would allow further than reach; min with reach wins.
    ClimbRule climb = new ClimbRule(0.2, 2.0);
    assertTrue(climb.climbs(water(0.0), solid(0.2)));
    assertFalse(climb.climbs(water(0.0), solid(0.2 + DROP + 0.01)));
  }

  @Test
  void sourceHeightSixSixteenthsPassesSevenRejects() {
    ClimbRule climb = new ClimbRule(REACH, DEFAULT_ESCAPE);
    // Rim one block above the water cell: collision tops at blockY+1 + height.
    // Plane at SOURCE_TOP above blockY; budget = SOURCE_TOP + 1/9 + 6/16 = 1 + 6/16.
    double waterCellY = 64.0;
    double plane = waterCellY + SOURCE_TOP;
    double snowTop = waterCellY + 1.0 + 6.0 / 16.0;
    double campfireTop = waterCellY + 1.0 + 7.0 / 16.0;
    assertTrue(climb.climbs(water(plane), solid(snowTop)));
    assertFalse(climb.climbs(water(plane), solid(campfireTop)));
  }

  @Test
  void climbsIsSymmetric() {
    ClimbRule climb = new ClimbRule(REACH, DEFAULT_ESCAPE);
    StandableRect a = water(SOURCE_TOP);
    StandableRect b = solid(SOURCE_TOP + DROP + DEFAULT_ESCAPE);
    StandableRect c = solid(SOURCE_TOP + DROP + DEFAULT_ESCAPE + 0.01);
    assertTrue(climb.climbs(a, b));
    assertTrue(climb.climbs(b, a));
    assertFalse(climb.climbs(a, c));
    assertFalse(climb.climbs(c, a));
  }

  @Test
  void fluidColumnConnectsAtAnyEscapeSetting() {
    StandableRect lower = water(62.0);
    StandableRect upper = water(63.0);
    assertTrue(new ClimbRule(REACH, 0.0).climbs(lower, upper));
    assertTrue(new ClimbRule(REACH, 2.0).climbs(lower, upper));
    assertTrue(new ClimbRule(1.0, 0.0).climbs(lower, upper));
  }

  @Test
  void submergedPlaneGrantsNoMoreThanSurfaceAbove() {
    // Surface at SOURCE_TOP; submerged full-height plane one block below.
    // Same solid rim above the surface cell: submerged must not clear a taller
    // rim than the surface plane can (escape measured from each plane + DROP).
    ClimbRule climb = new ClimbRule(REACH, DEFAULT_ESCAPE);
    double surfaceY = 64.0 + SOURCE_TOP;
    double submergedY = 63.0;
    double snowTop = 65.0 + 6.0 / 16.0;
    double campfireTop = 65.0 + 7.0 / 16.0;
    assertTrue(climb.climbs(water(surfaceY), solid(snowTop)));
    assertFalse(climb.climbs(water(surfaceY), solid(campfireTop)));
    // Submerged → campfire: |ΔY| is larger; still rejected (no more than surface).
    assertFalse(climb.climbs(water(submergedY), solid(campfireTop)));
    // Submerged → snow: budget from submerged is submergedY+DROP+escape ≪ snowTop.
    assertFalse(climb.climbs(water(submergedY), solid(snowTop)));
  }

  @Test
  void thinSheetClimbsOutViaCoplanarSolidAtFullReach() {
    // Thin fluid at cell floor (height 0) shares collisionTopY with the solid
    // underfoot; the coplanar solid uses plain reach to the shore. Direct
    // fluid→solid at escape 0 is capped to DROP only; raising escape to reach
    // restores a land-equivalent direct hop.
    ClimbRule landOnly = new ClimbRule(REACH, 0.0);
    StandableRect sheet = water(10.0);
    StandableRect underfoot = solid(10.0);
    StandableRect shore = solid(10.0 + REACH);
    assertTrue(landOnly.climbs(sheet, underfoot));
    assertTrue(landOnly.climbs(underfoot, shore));
    assertFalse(landOnly.climbs(sheet, shore));
    assertTrue(new ClimbRule(REACH, REACH).climbs(sheet, shore));
  }
}
