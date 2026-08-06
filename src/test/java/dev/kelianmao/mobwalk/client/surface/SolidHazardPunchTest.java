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
 * Coplanar solid-hazard paint: face midplane + conservative corner square
 * (docs/geometry.md). Must fail under (a) full dilated like fluids on
 * magma|stone, (b) pure undilated that clears void overhangs, or (c) MAGMA
 * merge priority claiming a whole air-gap overlap instead of midplane split.
 * Corner punches under-approximate stone (true magma never becomes stone).
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

  @Test
  void stoneAirMagmaRavagerSplitsContestedAirAtMidplane() {
    // Stone [0,1] | air [1,2] | magma [2,3]. Ravager halfW covers the air; closer
    // split is at x=1.5 — not MAGMA priority claiming the whole dilated overlap.
    double halfW = EntityProfile.RAVAGER.width() / 2.0;
    double height = EntityProfile.RAVAGER.height();
    WorldBox stone = solid(0, 0, 0, 0.0, 1.0, HazardClass.NONE);
    WorldBox magma = solid(2, 0, 0, 0.0, 1.0, HazardClass.MAGMA);
    List<StandableRect> out = exposeAndMerge(halfW, height, stone, magma);
    assertEquals(HazardClass.NONE, soleOwner(out, 1.25, 0.5).hazard(),
      "stone-closer half of air gap must not be MAGMA");
    assertEquals(HazardClass.MAGMA, soleOwner(out, 1.75, 0.5).hazard(),
      "magma-closer half of air gap stays MAGMA");
    assertEquals(HazardClass.MAGMA, soleOwner(out, 2.5, 0.5).hazard());
    assertEquals(HazardClass.NONE, soleOwner(out, 0.5, 0.5).hazard());
  }

  @Test
  void stoneAboveMagmaStripRavagerFaceAndCornerBite() {
    // . S .
    // . . .
    // M M M  — center air face midplane; side magmas also lose conservative
    // corner squares near stone (flush-X + gap-Z still gets a square).
    double halfW = EntityProfile.RAVAGER.width() / 2.0;
    double height = EntityProfile.RAVAGER.height();
    double s = Math.sqrt(2.0) - 1.0;
    WorldBox stone = solid(1, 0, 2, 0.0, 1.0, HazardClass.NONE);
    WorldBox m0 = solid(0, 0, 0, 0.0, 1.0, HazardClass.MAGMA);
    WorldBox m1 = solid(1, 0, 0, 0.0, 1.0, HazardClass.MAGMA);
    WorldBox m2 = solid(2, 0, 0, 0.0, 1.0, HazardClass.MAGMA);
    List<StandableRect> out = exposeAndMerge(halfW, height, stone, m0, m1, m2);
    assertEquals(HazardClass.MAGMA, soleOwner(out, 1.5, 1.25).hazard(),
      "center air, magma side of gap midplane");
    assertEquals(HazardClass.NONE, soleOwner(out, 1.5, 1.75).hazard(),
      "center air, stone side of gap midplane");
    // Stone SW corner (1,2): square into SW of side [1-s,1]×[2-s,2]
    assertEquals(HazardClass.NONE, soleOwner(out, 1.0 - s * 0.5, 2.0 - s * 0.5).hazard(),
      "conservative corner square vs side magma");
    assertEquals(HazardClass.MAGMA, soleOwner(out, 0.5, 1.5).hazard(),
      "side middle outside square stays MAGMA");
    assertEquals(HazardClass.MAGMA, soleOwner(out, 2.5, 1.5).hazard());
    assertEquals(HazardClass.MAGMA, soleOwner(out, 0.5, 0.5).hazard());
    assertEquals(HazardClass.MAGMA, soleOwner(out, 1.5, 0.5).hazard());
    assertEquals(HazardClass.MAGMA, soleOwner(out, 2.5, 0.5).hazard());
    assertEquals(HazardClass.MAGMA, soleOwner(out, 1.5, -halfW * 0.5).hazard(),
      "south void lip stays MAGMA");
  }

  @Test
  void stoneInMagmaRingCornerSquareNotRefilledByFaceMagma() {
    // Stone [0,1]²; magma ring at Chebyshev dist 2 (the raster layout). Face
    // magmas must not keep the NE corner square after the diagonal magma punches
    // it — real failure mode of the isolated-diagonal-only test.
    double halfW = EntityProfile.RAVAGER.width() / 2.0;
    double height = EntityProfile.RAVAGER.height();
    double s = Math.sqrt(2.0) - 1.0;
    WorldBox stone = solid(0, 0, 0, 0.0, 1.0, HazardClass.NONE);
    List<WorldBox> boxes = new ArrayList<>();
    boxes.add(stone);
    for (int ix = -2; ix <= 2; ix++) {
      for (int iz = -2; iz <= 2; iz++) {
        if (Math.max(Math.abs(ix), Math.abs(iz)) != 2) {
          continue;
        }
        boxes.add(solid(ix, 0, iz, 0.0, 1.0, HazardClass.MAGMA));
      }
    }
    List<StandableRect> out = exposeAndMerge(halfW, height, boxes.toArray(WorldBox[]::new));
    assertEquals(HazardClass.NONE, soleOwner(out, 1.0 + s * 0.5, 1.0 + s * 0.5).hazard(),
      "NE corner square must not stay MAGMA (face magmas must punch it too)");
    assertEquals(HazardClass.MAGMA, soleOwner(out, 1.7, 1.7).hazard(),
      "crescent toward magma stays MAGMA (conservative)");
    assertEquals(HazardClass.NONE, soleOwner(out, 0.5, 0.5).hazard());
  }

  @Test
  void checkerboardDoesNotOverCarveMagmaViaDiagonalMidplanes() {
    // (s)(m)
    // (m)(s) — edge midplanes only; diagonal stones must not quadrant-carve magma
    // down to a skinny strip (dump failure under Ravager).
    double halfW = EntityProfile.RAVAGER.width() / 2.0;
    double height = EntityProfile.RAVAGER.height();
    WorldBox s00 = solid(0, 0, 0, 0.0, 1.0, HazardClass.NONE);
    WorldBox m10 = solid(1, 0, 0, 0.0, 1.0, HazardClass.MAGMA);
    WorldBox m01 = solid(0, 0, 1, 0.0, 1.0, HazardClass.MAGMA);
    WorldBox s11 = solid(1, 0, 1, 0.0, 1.0, HazardClass.NONE);
    List<StandableRect> out = exposeAndMerge(halfW, height, s00, m10, m01, s11);
    // Magma cell center and an edge lip toward void/open side stay MAGMA.
    assertEquals(HazardClass.MAGMA, soleOwner(out, 1.5, 0.5).hazard());
    assertEquals(HazardClass.MAGMA, soleOwner(out, 0.5, 1.5).hazard());
    // Eastern lip of m10 south of the NE diagonal stone — still closer to magma;
    // must not be removed by a diagonal dual midplane carve.
    assertEquals(HazardClass.MAGMA, soleOwner(out, 1.5 + halfW * 0.5, 0.5).hazard(),
      "edge lip must survive diagonal neighbors");
    // Stone cells remain NONE.
    assertEquals(HazardClass.NONE, soleOwner(out, 0.5, 0.5).hazard());
    assertEquals(HazardClass.NONE, soleOwner(out, 1.5, 1.5).hazard());
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
