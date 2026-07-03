package com.example.overlay.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import com.example.overlay.client.SurfaceSelection.DropClass;
import com.example.overlay.client.SurfaceSelection.DropClassification;
import com.example.overlay.client.SurfaceSelection.Rect;
import com.example.overlay.client.SurfaceSelection.WorldBox;

import org.junit.jupiter.api.Test;

/**
 * Milestone 5 Step 1: the pure drop-edge classifier ({@code classifyDrop}) —
 * CUTOFF / HOLE / BENIGN for a drop sub-span, built from synthetic collision
 * boxes (no world). The mob leaves a surface at {@code T = 64} over
 * {@code [0,1]x[0,1]} across its {@code +X} edge (fixed X at {@code line = 1}, so
 * the fall footprint is the cell just beyond: {@code [1,2]x[0,1]}). The seed
 * center is {@code (0.5, 0.5)}, so the edge is {@code 0.5} off center along X;
 * {@code ringStart} is set large (100) so the hole/benign cases are never in the
 * grey ring, and small (0.4) for the explicit cutoff case.
 */
final class DropClassificationTest {
	private static final double EPS = 1.0e-6;

	// The surface being left and its +X edge / fall footprint.
	private static final double T = 64.0;
	private static final double EDGE_LINE = 1.0;   // +X edge x = 1
	private static final double PERP_CENTER = 0.5; // seed center X
	private static final Rect FOOTPRINT = new Rect(1, 0, 2, 1);
	private static final double RING_FAR = 100.0;  // not in the grey ring

	// A full-block collision box at block (x,y,z): footprint [x,x+1]x[z,z+1],
	// vertical [y, y+1] (top at y+1).
	private static WorldBox block(int x, int y, int z) {
		return new WorldBox(x, y, z, x, z, x + 1, z + 1, y, y + 1);
	}

	private static DropClassification classify(List<WorldBox> below, List<StandableRect> reached, double ringStart) {
		return SurfaceSelection.classifyDrop(FOOTPRINT, T, EDGE_LINE, PERP_CENTER, ringStart, below, reached);
	}

	@Test
	void voidDropIsHole() {
		// No collision below the fall footprint at all -> falls into the void.
		DropClassification c = classify(List.of(), List.of(new StandableRect(0, 0, 1, 1, T)), RING_FAR);
		assertEquals(DropClass.HOLE, c.kind());
	}

	@Test
	void deepButReachableLandingIsBenign() {
		// Lands on a deep floor (top at 59, a 5-block fall) that IS in the reached
		// set (escapable roundabout, e.g. via stairs elsewhere) -> benign, and the
		// fall distance is reported for Step 5.
		WorldBox landing = block(1, 58, 0); // top at 59
		List<StandableRect> reached = List.of(
			new StandableRect(0, 0, 1, 1, T),
			new StandableRect(1, 0, 2, 1, 59.0));
		DropClassification c = classify(List.of(landing), reached, RING_FAR);
		assertEquals(DropClass.BENIGN, c.kind());
		assertEquals(5.0, c.fallDistance(), EPS);
	}

	@Test
	void isolatedUnreachableLandingIsHole() {
		// Lands on a floor (top at 59) that is NOT in the reached set (an isolated
		// pit the flood never reached) -> trap.
		WorldBox landing = block(1, 58, 0);
		List<StandableRect> reached = List.of(new StandableRect(0, 0, 1, 1, T));
		DropClassification c = classify(List.of(landing), reached, RING_FAR);
		assertEquals(DropClass.HOLE, c.kind());
	}

	@Test
	void unescapableLedgeAboveReachedFloorIsHole() {
		// The TOPMOST landing is a ledge (top at 62) that is NOT reached; a reached
		// floor (top at 55) sits lower. The mob lands on and is stuck on the ledge,
		// so the topmost landing decides -> hole, despite a reached floor below it.
		WorldBox ledge = block(1, 61, 0);      // top at 62, not reached
		WorldBox lowerFloor = block(1, 54, 0); // top at 55, reached
		List<StandableRect> reached = List.of(
			new StandableRect(0, 0, 1, 1, T),
			new StandableRect(1, 0, 2, 1, 55.0));
		DropClassification c = classify(List.of(ledge, lowerFloor), reached, RING_FAR);
		assertEquals(DropClass.HOLE, c.kind());
	}

	@Test
	void shallowReachedStepIsBenign() {
		// A one-block step down onto a reached floor (top at 63) -> benign, fall 1.
		WorldBox landing = block(1, 62, 0); // top at 63
		List<StandableRect> reached = List.of(
			new StandableRect(0, 0, 1, 1, T),
			new StandableRect(1, 0, 2, 1, 63.0));
		DropClassification c = classify(List.of(landing), reached, RING_FAR);
		assertEquals(DropClass.BENIGN, c.kind());
		assertEquals(1.0, c.fallDistance(), EPS);
	}

	@Test
	void edgeInGreyRingIsCutoff() {
		// The edge is 0.5 off center; with ringStart = 0.4 it lies in the grey ring,
		// so it is CUTOFF regardless of what is (or isn't) below.
		DropClassification c = classify(List.of(), List.of(new StandableRect(0, 0, 1, 1, T)), 0.4);
		assertEquals(DropClass.CUTOFF, c.kind());
	}
}
