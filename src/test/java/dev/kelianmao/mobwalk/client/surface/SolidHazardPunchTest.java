package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.ColKey;
import dev.kelianmao.mobwalk.client.surface.WorldGeometry.WorldBox;

/**
 * Coplanar-punch contract for solid hazards (soul sand / magma): after dilated
 * expose, undilated footprints of coplanar competing supports punch out hazard
 * paint (docs/geometry.md; PLAN M10 Step 1). Must fail under (a) full dilated
 * like fluids on magma|stone, or (b) pure undilated that clears void overhangs.
 */
final class SolidHazardPunchTest {
  private static final double EPS = RectMath.EPS;
  private static final double PLAYER_HALF_W = EntityProfile.PLAYER.width() / 2.0;
  private static final double PLAYER_H = EntityProfile.PLAYER.height();
  private static final double SOUL_TOP = 14.0 / 16.0;

  @Test
  void magmaBesideStonePlayerPunchesAtBlockEdge() {
    WorldBox magma = solid(0, 0, 0, 0.0, 1.0, HazardClass.MAGMA);
    WorldBox stone = solid(1, 0, 0, 0.0, 1.0, HazardClass.NONE);
    List<StandableRect> out = exposeAndMerge(PLAYER_HALF_W, PLAYER_H, magma, stone);
    assertEquals(HazardClass.MAGMA, soleOwner(out, 0.5, 0.5).hazard());
    assertEquals(HazardClass.NONE, soleOwner(out, 1.15, 0.5).hazard(),
      "coplanar stone must punch dilated magma lip (not full-dilated fluid paint)");
    assertEquals(HazardClass.MAGMA, soleOwner(out, 0.85, 0.5).hazard());
  }

  @Test
  void magmaCliffPlayerKeepsVoidLip() {
    WorldBox magma = solid(0, 0, 0, 0.0, 1.0, HazardClass.MAGMA);
    List<StandableRect> out = exposeAndMerge(PLAYER_HALF_W, PLAYER_H, magma);
    assertEquals(HazardClass.MAGMA, soleOwner(out, 0.5, 0.5).hazard());
    assertEquals(HazardClass.MAGMA, soleOwner(out, 1.15, 0.5).hazard(),
      "void overhang keeps dilated hazard (not pure-undilated core)");
  }

  @Test
  void pointMagmaEqualsUndilatedCell() {
    WorldBox magma = solid(0, 0, 0, 0.0, 1.0, HazardClass.MAGMA);
    WorldBox stone = solid(1, 0, 0, 0.0, 1.0, HazardClass.NONE);
    List<StandableRect> beside = exposeAndMerge(0.0, 0.0, magma, stone);
    assertEquals(HazardClass.MAGMA, soleOwner(beside, 0.5, 0.5).hazard());
    assertEquals(HazardClass.NONE, soleOwner(beside, 1.5, 0.5).hazard());
    assertNotEquals(HazardClass.MAGMA, hazardAtOrNone(beside, 1.15, 0.5));

    List<StandableRect> cliff = exposeAndMerge(0.0, 0.0, magma);
    assertEquals(HazardClass.MAGMA, soleOwner(cliff, 0.5, 0.5).hazard());
    assertNotEquals(HazardClass.MAGMA, hazardAtOrNone(cliff, 1.15, 0.5),
      "Point cliff: MAGMA equals undilated cell only");
  }

  @Test
  void soulSandBesideFullBlockPlayerOcclusionCutsBack() {
    WorldBox soul = soulSand(0, 0, 0);
    WorldBox wall = solid(1, 0, 0, 0.0, 1.0, HazardClass.NONE);
    List<StandableRect> out = exposeAndMerge(PLAYER_HALF_W, PLAYER_H, soul, wall);
    assertNotEquals(HazardClass.SOUL_SAND, hazardAtOrNone(out, 0.85, 0.5),
      "taller wall occludes before punch; lip near wall is not SOUL_SAND");
    assertEquals(HazardClass.SOUL_SAND, soleOwner(out, 0.5, 0.5).hazard());
  }

  @Test
  void twoByTwoMagmaMergesWithoutInteriorNoneSeams() {
    WorldBox a = solid(0, 0, 0, 0.0, 1.0, HazardClass.MAGMA);
    WorldBox b = solid(1, 0, 0, 0.0, 1.0, HazardClass.MAGMA);
    WorldBox c = solid(0, 0, 1, 0.0, 1.0, HazardClass.MAGMA);
    WorldBox d = solid(1, 0, 1, 0.0, 1.0, HazardClass.MAGMA);
    List<StandableRect> out = exposeAndMerge(PLAYER_HALF_W, PLAYER_H, a, b, c, d);
    // Undilated 2x2 union centers and seam midpoints stay MAGMA.
    double[][] samples = {
      {0.5, 0.5}, {1.5, 0.5}, {0.5, 1.5}, {1.5, 1.5},
      {1.0, 0.5}, {0.5, 1.0}, {1.0, 1.0}, {1.0, 1.5}
    };
    for (double[] p : samples) {
      assertEquals(HazardClass.MAGMA, soleOwner(out, p[0], p[1]).hazard(),
        "no NONE seam inside undilated 2x2 at " + p[0] + "," + p[1]);
    }
    // Outer void lip may dilate past the 2x2.
    assertEquals(HazardClass.MAGMA, soleOwner(out, 2.15, 0.5).hazard());
  }

  private static WorldBox solid(int bx, int by, int bz, double yMin, double yMax,
      HazardClass hazard) {
    return new WorldBox(bx, by, bz, bx, bz, bx + 1, bz + 1, yMin, yMax, yMax, yMax,
      hazard, true);
  }

  private static WorldBox soulSand(int bx, int by, int bz) {
    double top = by + SOUL_TOP;
    double outline = by + 1.0;
    return new WorldBox(bx, by, bz, bx, bz, bx + 1, bz + 1, by, top, top, outline,
      HazardClass.SOUL_SAND, true);
  }

  private static List<StandableRect> exposeAndMerge(double halfW, double height,
      WorldBox... boxes) {
    Map<ColKey, List<WorldBox>> index = new HashMap<>();
    for (WorldBox b : boxes) {
      index.computeIfAbsent(new ColKey(b.bx(), b.bz()), k -> new ArrayList<>()).add(b);
    }
    List<StandableRect> raw = new ArrayList<>();
    for (WorldBox b : boxes) {
      SurfaceSelection.exposeBox(b, index, halfW, height, raw);
    }
    return RectMath.mergeAll(raw);
  }

  private static StandableRect soleOwner(List<StandableRect> rects, double x, double z) {
    List<StandableRect> owners = rects.stream()
      .filter(r -> x > r.minX() && x < r.maxX()
        && z > r.minZ() && z < r.maxZ())
      .toList();
    assertEquals(1, owners.size(),
      "expected one owner at " + x + "," + z + " but found " + owners);
    return owners.get(0);
  }

  /** Hazard at (x,z), or NONE when uncovered (open-interval sample). */
  private static HazardClass hazardAtOrNone(List<StandableRect> rects, double x, double z) {
    List<StandableRect> owners = rects.stream()
      .filter(r -> x > r.minX() && x < r.maxX()
        && z > r.minZ() && z < r.maxZ())
      .toList();
    assertTrue(owners.size() <= 1,
      "expected at most one owner at " + x + "," + z + " but found " + owners);
    return owners.isEmpty() ? HazardClass.NONE : owners.get(0).hazard();
  }
}
