package dev.kelianmao.mobwalk.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import dev.kelianmao.mobwalk.client.SurfaceSelection.Rect;

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

	@Test
	void floodFromHighSeedDoesNotReachDeepSameColumn() {
		// Seeded carpet at 66.0625 with ground at 64 in the same footprint:
		// |ΔY| = 2.0625 > reach 1.0 → only the seed height is reached.
		StandableRect carpet = new StandableRect(0, 0, 1, 1, 66.0625);
		StandableRect ground = new StandableRect(0, 0, 1, 1, 64.0);
		List<StandableRect> reached = SurfaceSelection.flood(
			List.of(carpet, ground), List.of(carpet), 1.0);
		assertEquals(1, reached.size());
		assertEquals(66.0625, reached.get(0).topY(), EPS);
	}

	@Test
	void floodReachesSameColumnWithinReach() {
		StandableRect upper = new StandableRect(0, 0, 1, 1, 65.0);
		StandableRect lower = new StandableRect(0, 0, 1, 1, 64.0);
		List<StandableRect> reached = SurfaceSelection.flood(
			List.of(upper, lower), List.of(upper), 1.0);
		assertEquals(2, reached.size());
	}

	@Test
	void floodJoinsOneBlockPlusCarpetAtJumpReach() {
		StandableRect floor = new StandableRect(0, 0, 1, 1, 64.0);
		StandableRect carpet = new StandableRect(0, 0, 1, 1, 65.0625);
		assertEquals(1, SurfaceSelection.flood(
			List.of(floor, carpet), List.of(floor), 1.0).size());
		assertEquals(2, SurfaceSelection.flood(
			List.of(floor, carpet), List.of(floor), EntityProfile.DEFAULT_JUMP_REACH).size());
	}

	@Test
	void floodRejectsOneBlockPlusSlabAtJumpReach() {
		StandableRect floor = new StandableRect(0, 0, 1, 1, 64.0);
		StandableRect slab = new StandableRect(0, 0, 1, 1, 65.5);
		assertEquals(1, SurfaceSelection.flood(
			List.of(floor, slab), List.of(floor), EntityProfile.DEFAULT_JUMP_REACH).size());
	}

	@Test
	void depthForMergedTakesMinOverCoveringNodes() {
		// One merged rect [0,2] covers two same-height raw nodes at depths {0, 2};
		// the merged depth is the min (0 = nearest the flood reached this patch).
		StandableRect merged = new StandableRect(0, 0, 2, 1, 64.0);
		List<StandableRect> rawNodes = List.of(
			new StandableRect(0, 0, 1, 1, 64.0),
			new StandableRect(1, 0, 2, 1, 64.0));
		int[] depths = SurfaceSelection.depthForMerged(
			List.of(merged), rawNodes, new int[] {0, 2});
		assertEquals(1, depths.length);
		assertEquals(0, depths[0]);
	}

	@Test
	void depthForMergedPicksTheOverlappingNodeOnly() {
		// A merged rect overlapping only the depth-2 node gets 2, not the disjoint 0.
		StandableRect merged = new StandableRect(3, 0, 4, 1, 64.0);
		List<StandableRect> rawNodes = List.of(
			new StandableRect(0, 0, 1, 1, 64.0),
			new StandableRect(3, 0, 4, 1, 64.0));
		int[] depths = SurfaceSelection.depthForMerged(
			List.of(merged), rawNodes, new int[] {0, 2});
		assertEquals(2, depths[0]);
	}

	@Test
	void depthForMergedIgnoresDifferentHeightNodes() {
		// A node at a different collision top does not contribute; the only covering
		// node at the merged rect's height is the depth-5 one.
		StandableRect merged = new StandableRect(0, 0, 1, 1, 64.0);
		List<StandableRect> rawNodes = List.of(
			new StandableRect(0, 0, 1, 1, 60.0),
			new StandableRect(0, 0, 1, 1, 64.0));
		int[] depths = SurfaceSelection.depthForMerged(
			List.of(merged), rawNodes, new int[] {0, 5});
		assertEquals(5, depths[0]);
	}

	@Test
	void downSkirtInheritsSurfaceDepth() {
		// A skirt span carries its source rect's flood-depth so the two share a band.
		StandableRect r = new StandableRect(0, 0, 1, 1, 64.0, 64.0, 3);
		List<DownSkirtSpan> spans = SurfaceSelection.computeDownSkirts(List.of(r), List.of());
		assertFalse(spans.isEmpty());
		for (DownSkirtSpan s : spans) {
			assertEquals(3, s.depth());
		}
	}

	@Test
	void splitFrontierKeepsFrontierSeparateFromInner() {
		// Three abutting same-height nodes at depths {0, 1, 2} with limit=2.
		// Without frontier split, mergeCoplanar would collapse them into one rect
		// with depth 0. With frontier split, the depth-2 node stays separate.
		List<StandableRect> nodes = List.of(
			new StandableRect(0, 0, 1, 1, 64.0),
			new StandableRect(1, 0, 2, 1, 64.0),
			new StandableRect(2, 0, 3, 1, 64.0));
		int[] depths = {0, 1, 2};
		int limit = 2;
		List<StandableRect> result = SurfaceSelection.mergeCoplanarSplitFrontier(
			nodes, depths, limit);

		// Must produce at least two rects: one inner, one frontier.
		assertTrue(result.size() >= 2,
			"expected inner + frontier, got " + result.size() + " rects");

		boolean hasInner = false;
		boolean hasFrontier = false;
		for (StandableRect r : result) {
			if (r.depth() < limit) {
				hasInner = true;
			}
			if (r.depth() >= limit) {
				hasFrontier = true;
			}
		}
		assertTrue(hasInner, "no inner rect found");
		assertTrue(hasFrontier, "no frontier rect found");
	}

	@Test
	void splitFrontierAbutsInner() {
		// Frontier and inner rects must share an edge so seam suppression applies.
		List<StandableRect> nodes = List.of(
			new StandableRect(0, 0, 1, 1, 64.0),
			new StandableRect(1, 0, 2, 1, 64.0),
			new StandableRect(2, 0, 3, 1, 64.0));
		int[] depths = {0, 1, 2};
		int limit = 2;
		List<StandableRect> result = SurfaceSelection.mergeCoplanarSplitFrontier(
			nodes, depths, limit);

		StandableRect inner = null;
		StandableRect frontier = null;
		for (StandableRect r : result) {
			if (r.depth() >= limit) {
				frontier = r;
			} else {
				// Pick the inner rect closest to the frontier.
				if (inner == null || r.maxX() > inner.maxX()) {
					inner = r;
				}
			}
		}
		assert inner != null && frontier != null;
		// They should abut: inner's maxX == frontier's minX (or vice versa).
		boolean abuts = Math.abs(inner.maxX() - frontier.minX()) < EPS
			|| Math.abs(frontier.maxX() - inner.minX()) < EPS
			|| Math.abs(inner.maxZ() - frontier.minZ()) < EPS
			|| Math.abs(frontier.maxZ() - inner.minZ()) < EPS;
		assertTrue(abuts, "frontier and inner rects should share an edge");
	}

	@Test
	void splitFrontierPreservesArea() {
		// The total area after frontier-split merge must equal the input area.
		List<StandableRect> nodes = List.of(
			new StandableRect(0, 0, 1, 1, 64.0),
			new StandableRect(1, 0, 2, 1, 64.0),
			new StandableRect(2, 0, 3, 1, 64.0),
			new StandableRect(3, 0, 4, 1, 64.0));
		int[] depths = {0, 1, 2, 3};
		int limit = 3;
		List<StandableRect> result = SurfaceSelection.mergeCoplanarSplitFrontier(
			nodes, depths, limit);

		double inputArea = 4.0;
		double outputArea = 0.0;
		for (StandableRect r : result) {
			outputArea += (r.maxX() - r.minX()) * (r.maxZ() - r.minZ());
		}
		assertEquals(inputArea, outputArea, EPS);
	}

	@Test
	void splitFrontierAllInnerNoFrontier() {
		// When no node is at the limit, all nodes merge into one inner rect.
		List<StandableRect> nodes = List.of(
			new StandableRect(0, 0, 1, 1, 64.0),
			new StandableRect(1, 0, 2, 1, 64.0));
		int[] depths = {0, 1};
		int limit = 5;
		List<StandableRect> result = SurfaceSelection.mergeCoplanarSplitFrontier(
			nodes, depths, limit);

		assertEquals(1, result.size());
		assertTrue(result.get(0).depth() < limit);
		assertEquals(0.0, result.get(0).minX(), EPS);
		assertEquals(2.0, result.get(0).maxX(), EPS);
	}
}
