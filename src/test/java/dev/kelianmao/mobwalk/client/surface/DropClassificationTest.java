package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.kelianmao.mobwalk.client.surface.RectMath.Rect;
import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.DropClass;
import dev.kelianmao.mobwalk.client.surface.SurfaceSelection.DropClassification;

/**
 * The pure drop-edge classifier. A mob leaves a surface at {@code T = 64} over
 * the fall footprint {@code [1,2]x[0,1]}. Verdict: BENIGN if a reached surface
 * lies strictly below T under the footprint AND no intermediate ledge traps the
 * entity; HOLE otherwise.
 */
final class DropClassificationTest {
  private static final double EPS = 1.0e-6;

  private static final double T = 64.0;
  private static final Rect FOOTPRINT = new Rect(1, 0, 2, 1);

  private static DropClassification classify(List<StandableRect> reached, List<StandableRect> ledges) {
    return SurfaceSelection.classifyDrop(FOOTPRINT, T, reached, ledges);
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
  void reachedSurfaceNotOverlappingFootprintIsIgnored() {
    DropClassification c = classify(List.of(new StandableRect(3, 0, 4, 1, 60.0)));
    assertEquals(DropClass.HOLE, c.kind());
  }

  @Test
  void twoDeepIsolatedPitIsHole() {
    DropClassification c = classify(List.of());
    assertEquals(DropClass.HOLE, c.kind());
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
  void ledgeNotOverlappingFootprintIsIgnored() {
    // Ledge at Y=62 but at x=[5,6], no XZ overlap with footprint [1,2]x[0,1].
    StandableRect floor = new StandableRect(1, 0, 2, 1, 59.0);
    StandableRect ledge = new StandableRect(5, 0, 6, 1, 62.0);
    DropClassification c = classify(List.of(floor), List.of(ledge));
    assertEquals(DropClass.BENIGN, c.kind());
  }
}
