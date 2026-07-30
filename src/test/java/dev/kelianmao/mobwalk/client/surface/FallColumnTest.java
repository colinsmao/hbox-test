package dev.kelianmao.mobwalk.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The fall column of a drop span ({@link FallColumn}): the span's own rim line, and
 * which geometry counts as being under it. The HOLE/BENIGN decision and the
 * per-landing subdivision are covered by {@code DropClassificationTest} /
 * {@code HoleSubSpanTest} (pure) and validated in-game; this locks the orientation
 * and the drop side, which are easy to get wrong.
 */
final class FallColumnTest {
  private static final double EPS = 1.0e-6;

  private static SkirtSpan span(boolean alongX, boolean maxSide, double line) {
    return new SkirtSpan(alongX, maxSide, line, 0.0, 1.0, 64.0, 64.0,
      SkirtSpan.Direction.DOWN, SkirtSpan.UNLIMITED);
  }

  @Test
  void columnCarriesTheSpansRimLineAndSide() {
    FallColumn fall = FallColumn.of(span(false, true, 4.0));
    assertEquals(4.0, fall.line(), EPS);
    assertEquals(0.0, fall.lo(), EPS);
    assertEquals(1.0, fall.hi(), EPS);
    assertFalse(fall.alongX());
    assertTrue(fall.maxSide());
  }

  @Test
  void plusXEdgeCatchesGeometryBeyondTheRim() {
    // +X edge (alongZ, maxSide) at x = 4: a surface reaching out from x = 4 is under
    // the fall line; one ending there sits behind the rim.
    FallColumn fall = FallColumn.of(span(false, true, 4.0));
    assertTrue(fall.crosses(new StandableRect(4, 0, 5, 1, 60.0)));
    assertFalse(fall.crosses(new StandableRect(3, 0, 4, 1, 60.0)));
  }

  @Test
  void minusXEdgeCatchesGeometryBeyondTheRim() {
    // -X edge (alongZ, minSide) at x = 0: the far side is -X.
    FallColumn fall = FallColumn.of(span(false, false, 0.0));
    assertTrue(fall.crosses(new StandableRect(-1, 0, 0, 1, 60.0)));
    assertFalse(fall.crosses(new StandableRect(0, 0, 1, 1, 60.0)));
  }

  @Test
  void plusZEdgeCatchesGeometryBeyondTheRim() {
    // +Z edge (alongX, maxSide) at z = 4: crossing is tested on Z.
    FallColumn fall = FallColumn.of(span(true, true, 4.0));
    assertTrue(fall.crosses(new StandableRect(0, 4, 1, 5, 60.0)));
    assertFalse(fall.crosses(new StandableRect(0, 3, 1, 4, 60.0)));
  }

  @Test
  void minusZEdgeCatchesGeometryBeyondTheRim() {
    // -Z edge (alongX, minSide) at z = 0: the far side is -Z.
    FallColumn fall = FallColumn.of(span(true, false, 0.0));
    assertTrue(fall.crosses(new StandableRect(0, -1, 1, 0, 60.0)));
    assertFalse(fall.crosses(new StandableRect(0, 0, 1, 1, 60.0)));
  }

  @Test
  void geometryOutsideTheSpanIntervalIsNotUnderTheColumn() {
    // A surface crossing the rim line, but along the edge past the span's [0,1].
    FallColumn fall = FallColumn.of(span(false, true, 4.0));
    assertFalse(fall.crosses(new StandableRect(4, 3, 5, 4, 60.0)));
  }

  @Test
  void clampedToNarrowsTheIntervalAndKeepsTheLine() {
    FallColumn sub = FallColumn.of(span(false, true, 4.0)).clampedTo(0.25, 0.5);
    assertEquals(4.0, sub.line(), EPS);
    assertEquals(0.25, sub.lo(), EPS);
    assertEquals(0.5, sub.hi(), EPS);
    assertTrue(sub.crosses(new StandableRect(4, 0.3, 5, 0.4, 60.0)));
    assertFalse(sub.crosses(new StandableRect(4, 0.6, 5, 0.9, 60.0)));
  }
}
