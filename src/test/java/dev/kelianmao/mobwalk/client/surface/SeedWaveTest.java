package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.OriginCandidate;
import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.OriginProbe;
import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.SeedWaveEntry;

/**
 * Pure contract of {@link SurfaceSelection#assignOriginWave}: the click origin
 * is a set of non-emitted raw dilated probes; exposed tops adjacent to a probe
 * within reach enter the first BFS wave at depth 0 (seed block) or 1 (other).
 */
final class SeedWaveTest {
  private static final double REACH = 1.2522;
  private static final ClimbRule CLIMB = new ClimbRule(REACH, 0.375);
  // Ravager half-width: a unit box dilates to [-0.975, 1.975].
  private static final double HALF_W = 1.95 / 2.0;

  private static OriginProbe probe(double minX, double minZ, double maxX, double maxZ,
      double topY) {
    return new OriginProbe(minX - HALF_W, minZ - HALF_W, maxX + HALF_W, maxZ + HALF_W, topY,
      HazardClass.NONE);
  }

  private static OriginCandidate cand(StandableRect r, int cx, int cz, boolean seed) {
    return new OriginCandidate(r, cx, cz, seed);
  }

  private static StandableRect top(double minX, double minZ, double maxX, double maxZ,
      double topY) {
    return new StandableRect(minX, minZ, maxX, maxZ, topY, topY);
  }

  private static SeedWaveEntry find(List<SeedWaveEntry> wave, int cx, int cz) {
    return wave.stream()
      .filter(e -> e.cx() == cx && e.cz() == cz)
      .findFirst()
      .orElse(null);
  }

  @Test
  void fullyBuriedShortTopSeedsNeighboursAtDepth1() {
    // Soul-sand style short top fully clipped by taller dilated neighbours:
    // probe still exists; only neighbour exposed tops enter at depth 1.
    OriginProbe origin = probe(0, 0, 1, 1, 0.875);
    StandableRect east = top(1 - HALF_W, 0 - HALF_W, 2 + HALF_W, 1 + HALF_W, 1.0);
    StandableRect west = top(-1 - HALF_W, 0 - HALF_W, 0 + HALF_W, 1 + HALF_W, 1.0);
    List<SeedWaveEntry> wave = SurfaceSelection.assignOriginWave(
      List.of(origin),
      List.of(
        cand(east, 1, 0, false),
        cand(west, -1, 0, false)),
      CLIMB, 3);

    assertEquals(2, wave.size());
    assertEquals(1, find(wave, 1, 0).depth());
    assertEquals(1, find(wave, -1, 0).depth());
    assertTrue(wave.stream().noneMatch(e -> e.depth() == 0));
  }

  @Test
  void openShortTopSeedsAtDepth0AndNeighboursAtDepth1() {
    OriginProbe origin = probe(0, 0, 1, 1, 0.875);
    StandableRect seed = top(0 - HALF_W, 0 - HALF_W, 1 + HALF_W, 1 + HALF_W, 0.875);
    StandableRect east = top(1 - HALF_W, 0 - HALF_W, 2 + HALF_W, 1 + HALF_W, 1.0);
    List<SeedWaveEntry> wave = SurfaceSelection.assignOriginWave(
      List.of(origin),
      List.of(
        cand(seed, 0, 0, true),
        cand(east, 1, 0, false)),
      CLIMB, 3);

    assertEquals(2, wave.size());
    assertEquals(0, find(wave, 0, 0).depth());
    assertEquals(1, find(wave, 1, 0).depth());
    // Depth-0 entries precede depth-1 for FIFO shortest-path enqueue.
    assertEquals(0, wave.get(0).depth());
    assertEquals(1, wave.get(1).depth());
  }

  @Test
  void partialOcclusionKeepsSeedFragmentAtDepth0AndNeighbourAtDepth1() {
    OriginProbe origin = probe(0, 0, 1, 1, 0.875);
    // Surviving remnant on the west half of the short top.
    StandableRect remnant = top(0 - HALF_W, 0 - HALF_W, 0.3, 1 + HALF_W, 0.875);
    StandableRect east = top(1 - HALF_W, 0 - HALF_W, 2 + HALF_W, 1 + HALF_W, 1.0);
    List<SeedWaveEntry> wave = SurfaceSelection.assignOriginWave(
      List.of(origin),
      List.of(
        cand(remnant, 0, 0, true),
        cand(east, 1, 0, false)),
      CLIMB, 3);

    assertEquals(0, find(wave, 0, 0).depth());
    assertEquals(1, find(wave, 1, 0).depth());
  }

  @Test
  void radiusZeroExcludesDepth1Neighbours() {
    OriginProbe origin = probe(0, 0, 1, 1, 0.875);
    StandableRect east = top(1 - HALF_W, 0 - HALF_W, 2 + HALF_W, 1 + HALF_W, 1.0);
    List<SeedWaveEntry> wave = SurfaceSelection.assignOriginWave(
      List.of(origin),
      List.of(cand(east, 1, 0, false)),
      CLIMB, 0);

    assertTrue(wave.isEmpty());
  }

  @Test
  void radiusZeroKeepsSeedTopAtDepth0() {
    OriginProbe origin = probe(0, 0, 1, 1, 0.875);
    StandableRect seed = top(0 - HALF_W, 0 - HALF_W, 1 + HALF_W, 1 + HALF_W, 0.875);
    StandableRect east = top(1 - HALF_W, 0 - HALF_W, 2 + HALF_W, 1 + HALF_W, 1.0);
    List<SeedWaveEntry> wave = SurfaceSelection.assignOriginWave(
      List.of(origin),
      List.of(
        cand(seed, 0, 0, true),
        cand(east, 1, 0, false)),
      CLIMB, 0);

    assertEquals(1, wave.size());
    assertEquals(0, wave.get(0).depth());
    assertEquals(0, wave.get(0).cx());
  }

  @Test
  void reachRejectsTooTallNeighbour() {
    OriginProbe origin = probe(0, 0, 1, 1, 0.875);
    StandableRect high = top(1 - HALF_W, 0 - HALF_W, 2 + HALF_W, 1 + HALF_W,
      0.875 + REACH + 0.5);
    List<SeedWaveEntry> wave = SurfaceSelection.assignOriginWave(
      List.of(origin),
      List.of(cand(high, 1, 0, false)),
      CLIMB, 3);

    assertTrue(wave.isEmpty());
  }

  @Test
  void diagonalNonOverlapRejected() {
    OriginProbe origin = probe(0, 0, 1, 1, 1.0);
    // Corner-only contact: no edge overlap, no area overlap with dilated probe.
    StandableRect diagonal = top(2.0, 2.0, 3.0, 3.0, 1.0);
    List<SeedWaveEntry> wave = SurfaceSelection.assignOriginWave(
      List.of(origin),
      List.of(cand(diagonal, 2, 2, false)),
      CLIMB, 3);

    assertTrue(wave.isEmpty());
  }

  @Test
  void minDepthWinsWhenCandidateSeenAsSeedAndNeighbour() {
    OriginProbe origin = probe(0, 0, 1, 1, 1.0);
    StandableRect same = top(0 - HALF_W, 0 - HALF_W, 1 + HALF_W, 1 + HALF_W, 1.0);
    List<SeedWaveEntry> wave = SurfaceSelection.assignOriginWave(
      List.of(origin),
      List.of(
        cand(same, 0, 0, false),
        cand(same, 0, 0, true)),
      CLIMB, 3);

    assertEquals(1, wave.size());
    assertEquals(0, wave.get(0).depth());
  }

  @Test
  void emptyFirstExpansionYieldsEmpty() {
    OriginProbe origin = probe(0, 0, 1, 1, 0.875);
    // Candidate exists but does not touch the probe footprint.
    StandableRect far = top(10, 10, 11, 11, 0.875);
    List<SeedWaveEntry> wave = SurfaceSelection.assignOriginWave(
      List.of(origin),
      List.of(cand(far, 10, 10, false)),
      CLIMB, 3);

    assertTrue(wave.isEmpty());
  }
}
