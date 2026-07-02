package com.example.overlay.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.example.overlay.client.SurfaceSelection.Rect;

import org.junit.jupiter.api.Test;

/**
 * Stage 0 sanity tests over the existing pure rect ops in {@link SurfaceSelection}
 * ({@code subtractRects} / {@code union} / {@code mergeCoplanar} /
 * {@code footprintAdjacent}). They build synthetic rects only — no world, no game
 * loop — and pin current behavior so later stages can refactor with a net.
 */
final class SurfaceGeometryTest {
	private static final double EPS = 1.0e-9;

	private static double area(List<Rect> rects) {
		double sum = 0.0;
		for (Rect r : rects) {
			sum += (r.maxX() - r.minX()) * (r.maxZ() - r.minZ());
		}
		return sum;
	}

	@Test
	void subtractDisjointKeepsBase() {
		Rect base = new Rect(0, 0, 4, 4);
		List<Rect> out = SurfaceSelection.subtractRects(base, List.of(new Rect(10, 10, 12, 12)));
		assertEquals(16.0, area(out), EPS);
	}

	@Test
	void subtractCenterHoleLeavesFrame() {
		Rect base = new Rect(0, 0, 3, 3);
		List<Rect> out = SurfaceSelection.subtractRects(base, List.of(new Rect(1, 1, 2, 2)));
		// 9 minus the 1x1 hole, partitioned into non-overlapping pieces.
		assertEquals(8.0, area(out), EPS);
	}

	@Test
	void subtractFullCoverLeavesNothing() {
		Rect base = new Rect(0, 0, 2, 2);
		List<Rect> out = SurfaceSelection.subtractRects(base, List.of(new Rect(-1, -1, 3, 3)));
		assertTrue(out.isEmpty());
	}

	@Test
	void unionOfOverlappingRectsCoversCombinedArea() {
		// Two unit squares overlapping in a 1x1 quarter -> union area 7 (4+4-1).
		List<Rect> out = SurfaceSelection.union(List.of(
			new Rect(0, 0, 2, 2),
			new Rect(1, 1, 3, 3)));
		assertEquals(7.0, area(out), EPS);
	}

	@Test
	void mergeCoplanarCollapsesAbuttingStrip() {
		List<StandableRect> merged = SurfaceSelection.mergeCoplanar(List.of(
			new StandableRect(0, 0, 1, 1, 64.0),
			new StandableRect(1, 0, 2, 1, 64.0)));
		assertEquals(1, merged.size());
		StandableRect r = merged.get(0);
		assertEquals(0.0, r.minX(), EPS);
		assertEquals(2.0, r.maxX(), EPS);
	}

	@Test
	void mergeCoplanarKeepsDistinctHeights() {
		List<StandableRect> merged = SurfaceSelection.mergeCoplanar(List.of(
			new StandableRect(0, 0, 1, 1, 64.0),
			new StandableRect(1, 0, 2, 1, 65.0)));
		assertEquals(2, merged.size());
	}

	@Test
	void footprintAdjacentEdgeSharingConnects() {
		StandableRect a = new StandableRect(0, 0, 1, 1, 64.0);
		StandableRect b = new StandableRect(1, 0, 2, 1, 64.0);
		assertTrue(SurfaceSelection.footprintAdjacent(a, b));
	}

	@Test
	void footprintAdjacentDiagonalDoesNotConnect() {
		StandableRect a = new StandableRect(0, 0, 1, 1, 64.0);
		StandableRect b = new StandableRect(1, 1, 2, 2, 64.0);
		assertFalse(SurfaceSelection.footprintAdjacent(a, b));
	}

	@Test
	void footprintAdjacentOverlapConnects() {
		StandableRect a = new StandableRect(0, 0, 2, 2, 64.0);
		StandableRect b = new StandableRect(1, 1, 3, 3, 64.0);
		assertTrue(SurfaceSelection.footprintAdjacent(a, b));
	}
}
