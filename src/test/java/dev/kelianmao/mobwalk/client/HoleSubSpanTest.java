package dev.kelianmao.mobwalk.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import dev.kelianmao.mobwalk.client.SurfaceSelection.Rect;

import org.junit.jupiter.api.Test;

/**
 * A single drop edge can span reached and unreached ground, so
 * {@code holeSubSpans} must subdivide the edge and emit a hole only over the
 * unreached sub-intervals, not label the whole edge by one verdict.
 *
 * <p>Fixture: a {@code +Z} edge (alongX, maxSide) at {@code z = 1},
 * {@code baseY = 64}. The fall footprint band is {@code z in [1,2]}.
 */
final class HoleSubSpanTest {
	private static final double EPS = 1.0e-6;

	private static List<HoleSpan> run(DownSkirtSpan sp, List<StandableRect> reached,
			List<StandableRect> ledges) {
		Rect band = SurfaceSelection.fallFootprint(sp);
		List<HoleSpan> out = new ArrayList<>();
		SurfaceSelection.holeSubSpans(sp, band, reached, ledges, out);
		return out;
	}

	private static List<HoleSpan> run(DownSkirtSpan sp, List<StandableRect> reached) {
		return run(sp, reached, List.of());
	}

	@Test
	void heterogeneousEdgeSplitsHoleFromSafe() {
		// Span x in [0,2]; a reached floor covers x in [1,2] below; x in [0,1] has
		// nothing reached below -> exactly one hole sub-span [0,1].
		DownSkirtSpan sp = new DownSkirtSpan(true, true, 1.0, 0.0, 2.0, 64.0);
		StandableRect reachedFloor = new StandableRect(1, 1, 2, 2, 60.0);
		List<HoleSpan> holes = run(sp, List.of(reachedFloor));
		assertEquals(1, holes.size());
		HoleSpan h = holes.get(0);
		assertEquals(0.0, h.lo(), EPS);
		assertEquals(1.0, h.hi(), EPS);
		assertEquals(true, h.alongX());
		assertEquals(true, h.maxSide());
		assertEquals(1.0, h.line(), EPS);
		assertEquals(64.0, h.baseY(), EPS);
	}

	@Test
	void fullyVoidEdgeCoalescesToOneSpan() {
		// Nothing reached below anywhere -> one hole span over the whole edge [0,2].
		DownSkirtSpan sp = new DownSkirtSpan(true, true, 1.0, 0.0, 2.0, 64.0);
		List<HoleSpan> holes = run(sp, List.of());
		assertEquals(1, holes.size());
		assertEquals(0.0, holes.get(0).lo(), EPS);
		assertEquals(2.0, holes.get(0).hi(), EPS);
	}

	@Test
	void safeMiddleSplitsIntoTwoHoles() {
		// Span x in [0,3]; a reached floor covers only x in [1,2] below; the two
		// void flanks [0,1] and [2,3] are separate holes.
		DownSkirtSpan sp = new DownSkirtSpan(true, true, 1.0, 0.0, 3.0, 64.0);
		StandableRect reachedFloor = new StandableRect(1, 1, 2, 2, 60.0);
		List<HoleSpan> holes = run(sp, List.of(reachedFloor));
		assertEquals(2, holes.size());
		assertEquals(0.0, holes.get(0).lo(), EPS);
		assertEquals(1.0, holes.get(0).hi(), EPS);
		assertEquals(2.0, holes.get(1).lo(), EPS);
		assertEquals(3.0, holes.get(1).hi(), EPS);
	}

	@Test
	void fullyReachedEdgeHasNoHoles() {
		// A reached floor covers the entire footprint -> no holes.
		DownSkirtSpan sp = new DownSkirtSpan(true, true, 1.0, 0.0, 2.0, 64.0);
		StandableRect reachedFloor = new StandableRect(0, 1, 2, 2, 60.0);
		List<HoleSpan> holes = run(sp, List.of(reachedFloor));
		assertEquals(0, holes.size());
	}

	@Test
	void ledgeBetweenEdgeAndReachedFloorIsHole() {
		// A reached floor at Y=60 covers the footprint, but a ledge at Y=62
		// (between 60 and 64) also overlaps -> HOLE (entity trapped on ledge).
		DownSkirtSpan sp = new DownSkirtSpan(true, true, 1.0, 0.0, 2.0, 64.0);
		StandableRect reachedFloor = new StandableRect(0, 1, 2, 2, 60.0);
		StandableRect ledge = new StandableRect(0, 1, 2, 2, 62.0);
		List<HoleSpan> holes = run(sp, List.of(reachedFloor), List.of(ledge));
		assertEquals(1, holes.size());
		assertEquals(0.0, holes.get(0).lo(), EPS);
		assertEquals(2.0, holes.get(0).hi(), EPS);
	}

	@Test
	void ledgeNotOverlappingFootprintIsIgnored() {
		// A ledge at Y=62 exists but at x=[3,4] (no XZ overlap) -> benign.
		DownSkirtSpan sp = new DownSkirtSpan(true, true, 1.0, 0.0, 2.0, 64.0);
		StandableRect reachedFloor = new StandableRect(0, 1, 2, 2, 60.0);
		StandableRect ledge = new StandableRect(3, 1, 4, 2, 62.0);
		List<HoleSpan> holes = run(sp, List.of(reachedFloor), List.of(ledge));
		assertEquals(0, holes.size());
	}
}
