package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.Bfs;
import dev.kelianmao.mobwalk.client.surface.WorldGeometry.ColumnBoxes;

/**
 * The resumable contract of {@link Bfs}: {@code stepRing} advances the flood
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

  private static Bfs bfsOver(ColumnBoxes world, int depthLimit) {
    return new Bfs(world, 0, 0, 0, depthLimit, EntityProfile.POINT, FLUID_ESCAPE, -8, 8);
  }

  // Every ring in one go: the reference whole run that sliced runs are held to.
  private static List<StandableRect> runWhole(Bfs bfs) {
    bfs.seed();
    while (!bfs.stepRing()) {
      // stepRing expands one depth ring per call.
    }
    return bfs.merged();
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
    Bfs bfs = bfsOver(worldOf(plane(4, 0)), 3);
    bfs.seed();
    assertEquals(0, bfs.depth(), "seeding arms ring 0 without expanding it");
    assertTrue(bfs.preMergeReached().isEmpty(), "nothing is reached before the first step");

    assertFalse(bfs.stepRing());
    assertEquals(1, bfs.depth());
    assertEquals(Set.of(new Tile(0, 0)), tiles(bfs.preMergeReached()),
      "step 1 completes ring 0: the clicked block's own top");

    assertFalse(bfs.stepRing());
    assertEquals(2, bfs.depth());
    assertEquals(
      Set.of(new Tile(0, 0), new Tile(1, 0), new Tile(-1, 0), new Tile(0, 1), new Tile(0, -1)),
      tiles(bfs.preMergeReached()),
      "step 2 adds ring 1: the four edge-sharing neighbours, not the diagonals");

    assertFalse(bfs.stepRing());
    assertEquals(3, bfs.depth());
    assertEquals(manhattanBall(2), tiles(bfs.preMergeReached()));
    assertEquals(13, bfs.preMergeReached().size(), "one reached node per column, no duplicates");

    assertTrue(bfs.stepRing(), "ring 3 is the depth limit, so its nodes are not expanded");
    assertEquals(4, bfs.depth());
    assertEquals(manhattanBall(3), tiles(bfs.preMergeReached()));
    assertEquals(25, bfs.preMergeReached().size());
  }

  @Test
  void sliceGranularityDoesNotChangeTheResult() {
    ColumnBoxes world = worldOf(unevenGround());
    List<StandableRect> wholeFlood = runWhole(bfsOver(world, 3));
    assertFalse(wholeFlood.isEmpty(), "the fixture floods");

    // Two floods stepped one ring at a time, interleaved with each other: state is
    // per Bfs, so a paused flood resumes to the same output as an unpaused one.
    Bfs first = bfsOver(world, 3);
    Bfs second = bfsOver(world, 3);
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
    Bfs bfs = bfsOver(worldOf(plane(2, 0)), 1);
    List<StandableRect> flooded = runWhole(bfs);
    List<StandableRect> reached = bfs.preMergeReached();
    assertFalse(reached.isEmpty());

    assertTrue(bfs.stepRing());
    assertTrue(bfs.stepRing());
    assertEquals(reached, bfs.preMergeReached(), "extra steps reach nothing further");
    assertEquals(flooded, bfs.merged());
  }

  @Test
  void aPartlyAdvancedFloodDrainsToTheSameResult() {
    // Resuming a paused flood answers what running it in one go would have: where
    // a driver yielded leaves no trace in the output.
    ColumnBoxes world = worldOf(unevenGround());
    List<StandableRect> wholeFlood = runWhole(bfsOver(world, 3));

    Bfs bfs = bfsOver(world, 3);
    bfs.seed();
    bfs.stepRing();
    bfs.stepRing();
    while (!bfs.stepRing()) {
      // Drain the rings the pause left.
    }

    assertEquals(wholeFlood, bfs.merged());
  }

  @Test
  void originOverNothingFinishesEmpty() {
    // Clicked cell (0,0,0) is air; the ground sits well below it, so the click
    // origin has no collision box of its own and the flood completes with no nodes.
    Bfs bfs = bfsOver(worldOf(plane(4, -3)), 3);
    bfs.seed();
    assertTrue(bfs.stepRing());
    assertTrue(bfs.preMergeReached().isEmpty());
    assertTrue(bfs.merged().isEmpty());
    assertTrue(runWhole(bfsOver(worldOf(plane(4, -3)), 3)).isEmpty());
  }
}
