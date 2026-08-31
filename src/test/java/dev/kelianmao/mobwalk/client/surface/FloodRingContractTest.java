package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.FloodJob;
import dev.kelianmao.mobwalk.client.surface.WorldGeometry.ColumnBoxes;

/**
 * The resumable contract of {@link FloodJob}: {@code stepRing} advances the flood
 * exactly one depth ring, and where a caller yields never changes the result.
 * Drives the BFS itself through a synthetic world (the {@link ColumnBoxes} seam),
 * so the loop is under test rather than the per-box passes it calls
 * ({@code exposeBox} is {@code HeadroomTest}, the merge is {@code MergeContractTest}).
 *
 * <p>Fixtures use {@link EntityProfile#POINT} ({@code W=0}, {@code H=0}): dilation
 * is a no-op, so on a flat plane every block exposes exactly its own footprint and
 * {@code footprintAdjacent} connects the four edge-sharing neighbours (diagonals
 * meet at a corner with zero overlap). Flood depth is therefore the <b>Manhattan
 * distance</b> in blocks — an independent yardstick for the expected ring
 * membership.
 */
final class FloodRingContractTest {
  // Default fluid-escape rim; the fixtures are dry, so it never applies.
  private static final double FLUID_ESCAPE = 0.375;

  // A full-cube collision box at block (x,y,z).
  private record Cube(int x, int y, int z) {
  }

  // A reached surface identified by the block column it sits on.
  private record Tile(int x, int z) {
  }

  private static ColumnBoxes worldOf(Set<Cube> cubes) {
    return (x, y, z) -> cubes.contains(new Cube(x, y, z))
      ? List.of(new WorldGeometry.WorldBox(x, y, z, x, z, x + 1, z + 1, y, y + 1))
      : List.of();
  }

  // A solid plane of cubes at block row y, spanning [-extent,extent] in X and Z.
  private static Set<Cube> plane(int extent, int y) {
    Set<Cube> out = new HashSet<>();
    for (int x = -extent; x <= extent; x++) {
      for (int z = -extent; z <= extent; z++) {
        out.add(new Cube(x, y, z));
      }
    }
    return out;
  }

  private static FloodJob job(ColumnBoxes world, int depthLimit) {
    return new FloodJob(world, 0, 0, 0, depthLimit, EntityProfile.POINT, FLUID_ESCAPE, -8, 8);
  }

  private static Set<Tile> tiles(List<StandableRect> rects) {
    Set<Tile> out = new HashSet<>();
    for (StandableRect r : rects) {
      out.add(new Tile((int) Math.floor(r.minX()), (int) Math.floor(r.minZ())));
    }
    return out;
  }

  private static Set<Tile> manhattanBall(int radius) {
    Set<Tile> out = new HashSet<>();
    for (int x = -radius; x <= radius; x++) {
      for (int z = -radius; z <= radius; z++) {
        if (Math.abs(x) + Math.abs(z) <= radius) {
          out.add(new Tile(x, z));
        }
      }
    }
    return out;
  }

  // Flat plane, one step up, a void gap, and a wall out of reach: the flood spans
  // several rings, skips blocked columns and routes around them.
  private static Set<Cube> unevenGround() {
    Set<Cube> cubes = plane(4, 0);
    // Void column: at W=0 nothing bridges it, so the flood detours.
    cubes.remove(new Cube(-2, 0, 0));
    // One block up (reach 1.0 climbs it); it buries the plane top beneath it.
    cubes.add(new Cube(2, 1, 0));
    // Two blocks up: past reach, and it buries the plane top beneath it, so this
    // column stays out of the reached set entirely.
    cubes.add(new Cube(0, 1, 2));
    cubes.add(new Cube(0, 2, 2));
    return cubes;
  }

  @Test
  void eachStepRingCompletesExactlyOneDepth() {
    // Manhattan ball sizes are 2d^2+2d+1: 1, 5, 13, 25.
    FloodJob job = job(worldOf(plane(4, 0)), 3);
    job.seed();
    assertEquals(0, job.depth(), "seeding arms ring 0 without expanding it");
    assertTrue(job.preMergeReached().isEmpty(), "nothing is reached before the first step");

    assertFalse(job.stepRing());
    assertEquals(1, job.depth());
    assertEquals(Set.of(new Tile(0, 0)), tiles(job.preMergeReached()),
      "step 1 completes ring 0: the clicked block's own top");

    assertFalse(job.stepRing());
    assertEquals(2, job.depth());
    assertEquals(
      Set.of(new Tile(0, 0), new Tile(1, 0), new Tile(-1, 0), new Tile(0, 1), new Tile(0, -1)),
      tiles(job.preMergeReached()),
      "step 2 adds ring 1: the four edge-sharing neighbours, not the diagonals");

    assertFalse(job.stepRing());
    assertEquals(3, job.depth());
    assertEquals(manhattanBall(2), tiles(job.preMergeReached()));
    assertEquals(13, job.preMergeReached().size(), "one reached node per column, no duplicates");

    assertTrue(job.stepRing(), "ring 3 is the depth limit, so its nodes are not expanded");
    assertEquals(4, job.depth());
    assertEquals(manhattanBall(3), tiles(job.preMergeReached()));
    assertEquals(25, job.preMergeReached().size());
  }

  @Test
  void sliceGranularityDoesNotChangeTheResult() {
    ColumnBoxes world = worldOf(unevenGround());
    List<StandableRect> wholeFlood = job(world, 3).runToCompletion();
    assertFalse(wholeFlood.isEmpty(), "the fixture floods");

    // Two jobs stepped one ring at a time, interleaved with each other: state is
    // per job, so a paused flood resumes to the same output as an unpaused one.
    FloodJob first = job(world, 3);
    FloodJob second = job(world, 3);
    first.seed();
    second.seed();
    boolean firstDone = false;
    boolean secondDone = false;
    while (!firstDone || !secondDone) {
      firstDone = first.stepRing();
      secondDone = second.stepRing();
    }

    assertEquals(wholeFlood, first.merged(), "ring-at-a-time equals all-rings-at-once");
    assertEquals(wholeFlood, second.merged());
  }

  @Test
  void steppingPastCompletionChangesNothing() {
    FloodJob job = job(worldOf(plane(2, 0)), 1);
    List<StandableRect> flooded = job.runToCompletion();
    List<StandableRect> reached = job.preMergeReached();
    assertFalse(reached.isEmpty());

    assertTrue(job.stepRing());
    assertTrue(job.stepRing());
    assertEquals(reached, job.preMergeReached(), "extra steps reach nothing further");
    assertEquals(flooded, job.merged());
  }

  @Test
  void originOverNothingFinishesEmpty() {
    // Clicked cell (0,0,0) is air; the ground sits well below it, so the click
    // origin has no collision box of its own and the job completes with no nodes.
    FloodJob job = job(worldOf(plane(4, -3)), 3);
    job.seed();
    assertTrue(job.stepRing());
    assertTrue(job.preMergeReached().isEmpty());
    assertTrue(job.merged().isEmpty());
    assertTrue(job(worldOf(plane(4, -3)), 3).runToCompletion().isEmpty());
  }
}
