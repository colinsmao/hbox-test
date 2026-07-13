package dev.kelianmao.mobwalk.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.kelianmao.mobwalk.client.SurfaceSelection.ColKey;
import dev.kelianmao.mobwalk.client.SurfaceSelection.WorldBox;

import org.junit.jupiter.api.Test;

/**
 * Milestone 6 Step 2a: the draw-only {@code visualTopY} raise in {@code exposeBox}.
 * A box that IS its block's topmost collision surface and whose block renders taller
 * than it collides (soul sand, mud) exposes the visible/outline top; everything else
 * keeps {@code visualTopY == topY}. The walkability math is unaffected (all keyed on
 * the collision {@code topY}); these assert only the carried visible top.
 */
final class VisualTopTest {
	private static final double EPS = 1.0e-9;

	// A single-box column (no occluders) exposed at Point (halfW=0, height=0), so the
	// box's full top survives and its visualTopY is the raise rule's output.
	private static StandableRect expose(double yMin, double yMax,
			double blockCollisionTop, double blockOutlineTop) {
		WorldBox box = new WorldBox(0, (int) Math.floor(yMin), 0,
			0, 0, 1, 1, yMin, yMax, blockCollisionTop, blockOutlineTop);
		Map<ColKey, List<WorldBox>> index = new HashMap<>();
		List<WorldBox> column = new ArrayList<>();
		column.add(box);
		index.put(new ColKey(0, 0), column);
		List<StandableRect> out = new ArrayList<>();
		SurfaceSelection.exposeBox(box, index, 0.0, 0.0, out);
		assertEquals(1, out.size());
		return out.get(0);
	}

	@Test
	void soulSandRaisesToVisibleTop() {
		// Collides at 0.875, renders as a full cube: draw on the visible face (1.0).
		StandableRect r = expose(0.0, 0.875, 0.875, 1.0);
		assertEquals(0.875, r.topY(), EPS);
		assertEquals(1.0, r.visualTopY(), EPS);
	}

	@Test
	void fullBlockVisualEqualsCollision() {
		StandableRect r = expose(0.0, 1.0, 1.0, 1.0);
		assertEquals(1.0, r.visualTopY(), EPS);
	}

	@Test
	void fenceTopNotLoweredByShorterOutline() {
		// Fence collides at 1.5 but its outline maxes at 1.0; the raise only ever
		// lifts (outline > collision), so the fence top stays at its collision top.
		StandableRect r = expose(0.0, 1.5, 1.5, 1.0);
		assertEquals(1.5, r.visualTopY(), EPS);
	}

	@Test
	void stairLowerTreadNotLifted() {
		// The lower tread's top (0.5) is NOT the block's topmost collision surface
		// (1.0), so it is not lifted even though the block outlines to 1.0.
		StandableRect r = expose(0.0, 0.5, 1.0, 1.0);
		assertEquals(0.5, r.visualTopY(), EPS);
	}

	@Test
	void mergeCarriesMaxVisualTop() {
		// Two abutting soul-sand-like patches (collision 0.875, visible 1.0) merge to
		// one rect that keeps the visible top; a mixed group takes the max (raised).
		List<StandableRect> merged = SurfaceSelection.mergeCoplanar(List.of(
			new StandableRect(0, 0, 1, 1, 0.875, 1.0),
			new StandableRect(1, 0, 2, 1, 0.875, 0.875)));
		assertEquals(1, merged.size());
		assertEquals(0.875, merged.get(0).topY(), EPS);
		assertEquals(1.0, merged.get(0).visualTopY(), EPS);
	}

	@Test
	void auxiliaryConstructorDefaultsVisualToCollision() {
		StandableRect r = new StandableRect(0, 0, 1, 1, 64.0);
		assertEquals(64.0, r.visualTopY(), EPS);
	}
}
