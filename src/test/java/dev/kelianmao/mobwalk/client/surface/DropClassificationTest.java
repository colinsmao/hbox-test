package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.kelianmao.mobwalk.client.surface.HoleBeams.DropClass;
import dev.kelianmao.mobwalk.client.surface.HoleBeams.DropClassification;

/**
 * The pure drop-edge classifier. A mob leaves a surface at {@code T = 64} down the
 * fall column of a {@code +X} rim at {@code x = 1} over {@code z in [0,1]}. Verdict:
 * BENIGN if a reached surface lies strictly below T across that column AND no
 * intermediate ledge traps the entity; HOLE otherwise.
 */
final class DropClassificationTest {
  private static final double EPS = 1.0e-6;

  private static final double T = 64.0;
  private static final FallColumn FALL = new FallColumn(false, true, 1.0, 0.0, 1.0);

  private static DropClassification classify(List<StandableRect> reached, List<StandableRect> ledges) {
    return HoleBeams.classifyDrop(FALL, T, reached, ledges);
  }

  private static DropClassification classify(List<StandableRect> reached) {
    return classify(reached, List.of());
  }

  @Test
  void noReachedSurfaceBelowIsHole() {
    DropClassification c = classify(List.of());
    assertEquals(DropClass.HOLE, c.kind());
  }

  @Test
  void reachedSurfaceAtSameHeightIsHole() {
    DropClassification c = classify(List.of(new StandableRect(1, 0, 2, 1, T)));
    assertEquals(DropClass.HOLE, c.kind());
  }

  @Test
  void reachedSurfaceBelowIsBenign() {
    DropClassification c = classify(List.of(new StandableRect(1, 0, 2, 1, 59.0)));
    assertEquals(DropClass.BENIGN, c.kind());
    assertEquals(5.0, c.fallDistance(), EPS);
  }

  @Test
  void topmostReachedSurfaceWins() {
    DropClassification c = classify(List.of(
      new StandableRect(1, 0, 2, 1, 55.0),
      new StandableRect(1, 0, 2, 1, 62.0)));
    assertEquals(DropClass.BENIGN, c.kind());
    assertEquals(2.0, c.fallDistance(), EPS);
  }

  @Test
  void reachedSurfaceNotCrossingTheColumnIsIgnored() {
    DropClassification c = classify(List.of(new StandableRect(3, 0, 4, 1, 60.0)));
    assertEquals(DropClass.HOLE, c.kind());
  }

  @Test
  void reachedSurfaceBesideTheRimIsNotALanding() {
    // The entity leaves the dilated rim at x = 1 and falls down that line. Ground
    // two blocks down that starts half a block out (a terrace across a gap) is
    // beside the fall path, not under it — the rim is still a void drop.
    DropClassification c = classify(List.of(new StandableRect(1.5, 0, 2.5, 1, T - 2.0)));
    assertEquals(DropClass.HOLE, c.kind());
  }

  @Test
  void ledgeClearOfTheRimDoesNotTrap() {
    // A reached floor six down under the rim, plus a ledge two down that starts half
    // a block out: the entity falls past that ledge onto the floor.
    StandableRect floor = new StandableRect(1, 0, 2, 1, T - 6.0);
    StandableRect ledge = new StandableRect(1.5, 0, 2.5, 1, T - 2.0);
    DropClassification c = classify(List.of(floor), List.of(ledge));
    assertEquals(DropClass.BENIGN, c.kind());
    assertEquals(6.0, c.fallDistance(), EPS);
  }

  @Test
  void ledgeBetweenEdgeAndFloorIsHole() {
    // Reached floor at Y=59, but a ledge at Y=62 (between 59 and 64) traps entity.
    StandableRect floor = new StandableRect(1, 0, 2, 1, 59.0);
    StandableRect ledge = new StandableRect(1, 0, 2, 1, 62.0);
    DropClassification c = classify(List.of(floor), List.of(ledge));
    assertEquals(DropClass.HOLE, c.kind());
    assertEquals(2.0, c.fallDistance(), EPS);
  }

  @Test
  void ledgeAtFloorLevelIsIgnored() {
    // A "ledge" at the same Y as the reached floor (at landY) is not intermediate.
    StandableRect floor = new StandableRect(1, 0, 2, 1, 59.0);
    StandableRect notLedge = new StandableRect(1, 0, 2, 1, 59.0);
    DropClassification c = classify(List.of(floor), List.of(notLedge));
    assertEquals(DropClass.BENIGN, c.kind());
  }

  @Test
  void ledgeAtEdgeLevelIsIgnored() {
    // A "ledge" at collisionTopY is not intermediate.
    StandableRect floor = new StandableRect(1, 0, 2, 1, 59.0);
    StandableRect notLedge = new StandableRect(1, 0, 2, 1, T);
    DropClassification c = classify(List.of(floor), List.of(notLedge));
    assertEquals(DropClass.BENIGN, c.kind());
  }

  @Test
  void ledgeNotCrossingTheColumnIsIgnored() {
    // Ledge at Y=62 but at x=[5,6], nowhere near the rim line at x=1.
    StandableRect floor = new StandableRect(1, 0, 2, 1, 59.0);
    StandableRect ledge = new StandableRect(5, 0, 6, 1, 62.0);
    DropClassification c = classify(List.of(floor), List.of(ledge));
    assertEquals(DropClass.BENIGN, c.kind());
  }
}
