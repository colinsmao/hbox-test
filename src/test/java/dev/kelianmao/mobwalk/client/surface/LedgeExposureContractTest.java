package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.ColumnBoxes;
import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.WorldBox;

/**
 * The gather-exposure == flood-exposure contract for {@code gatherLedges}
 * (docs/geometry.md, "Exposure-agreement contract"): a ledge candidate is exposed
 * iff the flood would expose it, which requires the gather occluder index to be a
 * superset of the shell {@code exposeBox} needs for every candidate. Drives the
 * window that builds the index through a synthetic world (the {@link ColumnBoxes}
 * seam), so the window bounds themselves are under test — not just {@code exposeBox}
 * on a hand-built index (that is {@code HeadroomTest}).
 *
 * <p>Reproduces the 00:03:40 false-hole dump: Ravager, rim {@code T = 45.5}, reached
 * floor at {@code 44.0}, candidate ledge top {@code 45.0}. A headroom ceiling sitting
 * ABOVE the rim (rows the rim-height cap {@code ceil(T)} drops) must still bury the
 * candidate — otherwise a phantom ledge fires a false HOLE.
 */
final class LedgeExposureContractTest {
  private static final double EPS = 1.0e-6;

  private static final double HALF_W = EntityProfile.RAVAGER.width() / 2.0;   // 0.975
  private static final double HEIGHT = EntityProfile.RAVAGER.height();        // 2.2
  private static final double T = 45.5;                                       // rim collisionTopY
  // The rim: a +Z edge at z = 0 over x in [0,1]; the entity falls down that line.
  private static final FallColumn FALL = new FallColumn(true, true, 0.0, 0.0, 1.0);

  // A reached floor at Y=44 spanning the footprint (sets landY = 44).
  private static final List<StandableRect> FLOOR = List.of(new StandableRect(-1, -1, 2, 2, 44.0));

  // One full-cube collision box at block (bx,by,bz): footprint [bx,bx+1]x[bz,bz+1],
  // vertical [by,by+1].
  private static WorldBox cube(int bx, int by, int bz) {
    return new WorldBox(bx, by, bz, bx, bz, bx + 1, bz + 1, by, by + 1);
  }

  // A tall wall box at block column (bx,bz) spanning [yMin,yMax].
  private static WorldBox wall(int bx, int bz, double yMin, double yMax) {
    return new WorldBox(bx, (int) Math.floor(yMin), bz, bx, bz, bx + 1, bz + 1, yMin, yMax);
  }

  private static List<StandableRect> gather(ColumnBoxes world) {
    List<StandableRect> out = new ArrayList<>();
    SurfaceSelection.gatherLedgesFrom(world, FALL, T, FLOOR, HALF_W, HEIGHT, out);
    return out;
  }

  private static double area(List<StandableRect> rects) {
    double sum = 0.0;
    for (StandableRect r : rects) {
      sum += (r.maxX() - r.minX()) * (r.maxZ() - r.minZ());
    }
    return sum;
  }

  @Test
  void headroomCeilingAboveRimBuriesCandidateLedge() {
    // Candidate top 45.0 at block (0,44,0); headroom ceiling at (0,47,0): yMin 47.0 is
    // inside the standing column (45.0, 45.0+2.2 = 47.2], so it buries the top. The
    // ceiling sits above the rim (T=45.5), so the flood (window up to floor(T+H)+1 = 48)
    // sees it and paints nothing there. gatherLedges must agree: no ledge -> no HOLE.
    // Fails while the gather window caps at ceil(T)=46 and never queries row 47.
    List<StandableRect> ledges = gather((x, y, z) -> {
      if (x == 0 && z == 0 && y == 44) {
        return List.of(cube(0, 44, 0));
      }
      if (x == 0 && z == 0 && y == 47) {
        return List.of(cube(0, 47, 0));
      }
      return List.of();
    });
    assertTrue(ledges.isEmpty(),
      "candidate under a headroom ceiling above the rim must not expose a ledge");
  }

  @Test
  void ceilingAboveHeadroomLeavesRealTrapLedge() {
    // Same candidate, but the ceiling is at (0,48,0): yMin 48.0 is outside (45.0, 47.2],
    // so it does NOT rob headroom. The 45.0 top is genuinely standable -> a real trap
    // ledge the classifier should still flag. Guards against over-suppression by the fix.
    List<StandableRect> ledges = gather((x, y, z) -> {
      if (x == 0 && z == 0 && y == 44) {
        return List.of(cube(0, 44, 0));
      }
      if (x == 0 && z == 0 && y == 48) {
        return List.of(cube(0, 48, 0));
      }
      return List.of();
    });
    assertTrue(!ledges.isEmpty(), "a genuine ledge under a too-high ceiling must be reported");
    for (StandableRect r : ledges) {
      assertEquals(45.0, r.collisionTopY(), EPS);
    }
  }

  @Test
  void wideOccluderBeyondNarrowWindowTrimsLedge() {
    // XZ half of the contract. A wall at block x=-2 spanning [44,46] rises above the
    // candidate top (45.0) and, dilated by 0.975, reaches to x=-0.025 — it must trim the
    // candidate's western strip. occluderColumns needs columns down to floor(minX - 2*halfW)
    // = -2, but the gather XZ window only reaches xLo - ceil(halfW) = -1, so it never
    // queries x=-2. Expected exposed area after the trim: 2.0 (X) * 2.95 (Z) = 5.9;
    // the un-trimmed (buggy) area is 2.95 * 2.95 = 8.7025.
    List<StandableRect> ledges = gather((x, y, z) -> {
      if (x == 0 && z == 0 && y == 44) {
        return List.of(cube(0, 44, 0));
      }
      if (x == -2 && z == 0 && y == 44) {
        return List.of(wall(-2, 0, 44.0, 46.0));
      }
      return List.of();
    });
    assertEquals(5.9, area(ledges), EPS,
      "occluder within 2*halfW but beyond the narrow gather window must trim the ledge");
  }
}
