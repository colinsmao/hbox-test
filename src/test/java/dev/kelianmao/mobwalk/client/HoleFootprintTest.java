package dev.kelianmao.mobwalk.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kelianmao.mobwalk.client.RectMath.Rect;

import org.junit.jupiter.api.Test;

/**
 * Milestone 5 Step 3: the fall-footprint geometry ({@code SurfaceSelection.fallFootprint})
 * — a one-block band just beyond a drop span's rim, on the drop side, along its
 * [lo,hi]. The HOLE/BENIGN decision and the per-landing subdivision are covered by
 * {@code DropClassificationTest} / {@code HoleSubSpanTest} (pure) and validated in-game;
 * this locks the band's orientation/side, which is easy to get wrong.
 */
final class HoleFootprintTest {
  private static final double EPS = 1.0e-6;

  @Test
  void plusXEdgeBandIsBeyondTheRim() {
    // +X edge (alongZ, maxSide) at x = 4 over z in [0,1]: band x in [4,5].
    DownSkirtSpan sp = new DownSkirtSpan(false, true, 4.0, 0.0, 1.0, 64.0);
    Rect fp = SurfaceSelection.fallFootprint(sp);
    assertEquals(4.0, fp.minX(), EPS);
    assertEquals(5.0, fp.maxX(), EPS);
    assertEquals(0.0, fp.minZ(), EPS);
    assertEquals(1.0, fp.maxZ(), EPS);
  }

  @Test
  void minusXEdgeBandIsBeyondTheRim() {
    // -X edge (alongZ, minSide) at x = 0: band x in [-1,0].
    DownSkirtSpan sp = new DownSkirtSpan(false, false, 0.0, 0.0, 1.0, 64.0);
    Rect fp = SurfaceSelection.fallFootprint(sp);
    assertEquals(-1.0, fp.minX(), EPS);
    assertEquals(0.0, fp.maxX(), EPS);
  }

  @Test
  void plusZEdgeBandIsBeyondTheRim() {
    // +Z edge (alongX, maxSide) at z = 4 over x in [0,1]: band z in [4,5].
    DownSkirtSpan sp = new DownSkirtSpan(true, true, 4.0, 0.0, 1.0, 64.0);
    Rect fp = SurfaceSelection.fallFootprint(sp);
    assertEquals(0.0, fp.minX(), EPS);
    assertEquals(1.0, fp.maxX(), EPS);
    assertEquals(4.0, fp.minZ(), EPS);
    assertEquals(5.0, fp.maxZ(), EPS);
  }

  @Test
  void minusZEdgeBandIsBeyondTheRim() {
    // -Z edge (alongX, minSide) at z = 0: band z in [-1,0].
    DownSkirtSpan sp = new DownSkirtSpan(true, false, 0.0, 0.0, 1.0, 64.0);
    Rect fp = SurfaceSelection.fallFootprint(sp);
    assertEquals(-1.0, fp.minZ(), EPS);
    assertEquals(0.0, fp.maxZ(), EPS);
  }
}
