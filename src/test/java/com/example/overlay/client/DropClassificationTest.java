package com.example.overlay.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import com.example.overlay.client.SurfaceSelection.DropClass;
import com.example.overlay.client.SurfaceSelection.DropClassification;
import com.example.overlay.client.SurfaceSelection.Rect;

import org.junit.jupiter.api.Test;

/**
 * The pure drop-edge classifier. A mob leaves a surface at {@code T = 64} over
 * the fall footprint {@code [1,2]x[0,1]}. It is given the flood's reached set.
 * Verdict: BENIGN if a reached surface lies strictly below T under the
 * footprint; HOLE otherwise (void / unreached ground).
 */
final class DropClassificationTest {
	private static final double EPS = 1.0e-6;

	private static final double T = 64.0;
	private static final Rect FOOTPRINT = new Rect(1, 0, 2, 1);

	private static DropClassification classify(List<StandableRect> reached) {
		return SurfaceSelection.classifyDrop(FOOTPRINT, T, reached);
	}

	@Test
	void noReachedSurfaceBelowIsHole() {
		// Nothing reached below the footprint -> void / unreachable.
		DropClassification c = classify(List.of());
		assertEquals(DropClass.HOLE, c.kind());
	}

	@Test
	void reachedSurfaceAtSameHeightIsHole() {
		// A reached surface at T (not below) doesn't count.
		DropClassification c = classify(List.of(new StandableRect(1, 0, 2, 1, T)));
		assertEquals(DropClass.HOLE, c.kind());
	}

	@Test
	void reachedSurfaceBelowIsBenign() {
		// Reached floor at Y=59 below the footprint -> benign, fall 5.
		DropClassification c = classify(List.of(new StandableRect(1, 0, 2, 1, 59.0)));
		assertEquals(DropClass.BENIGN, c.kind());
		assertEquals(5.0, c.fallDistance(), EPS);
	}

	@Test
	void topmostReachedSurfaceWins() {
		// Two reached surfaces below; the topmost (Y=62) determines fall distance.
		DropClassification c = classify(List.of(
			new StandableRect(1, 0, 2, 1, 55.0),
			new StandableRect(1, 0, 2, 1, 62.0)));
		assertEquals(DropClass.BENIGN, c.kind());
		assertEquals(2.0, c.fallDistance(), EPS);
	}

	@Test
	void reachedSurfaceNotOverlappingFootprintIsIgnored() {
		// A reached surface below T but at x=[3,4] (no XZ overlap with [1,2]x[0,1]).
		DropClassification c = classify(List.of(new StandableRect(3, 0, 4, 1, 60.0)));
		assertEquals(DropClass.HOLE, c.kind());
	}

	@Test
	void twoDeepIsolatedPitIsHole() {
		// Floor at Y=62 exists but is NOT in the reached set -> HOLE.
		// (The reached set is empty below the footprint.)
		DropClassification c = classify(List.of());
		assertEquals(DropClass.HOLE, c.kind());
	}
}
