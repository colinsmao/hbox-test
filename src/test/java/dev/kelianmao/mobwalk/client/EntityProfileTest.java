package dev.kelianmao.mobwalk.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Stage B1: pins the shipped {@link EntityProfile} values (incl. the new
 * {@code height}) and the cycle order. Point keeps {@code height 0} so it stays the
 * pure point-walker / oracle baseline.
 */
final class EntityProfileTest {
	private static final double EPS = 1.0e-9;

	@Test
	void shippedHeights() {
		assertEquals(0.0, EntityProfile.POINT.height(), EPS);
		assertEquals(1.8, EntityProfile.PLAYER.height(), EPS);
		assertEquals(2.2, EntityProfile.RAVAGER.height(), EPS);
	}

	@Test
	void shippedWidths() {
		assertEquals(0.0, EntityProfile.POINT.width(), EPS);
		assertEquals(0.6, EntityProfile.PLAYER.width(), EPS);
		assertEquals(1.95, EntityProfile.RAVAGER.width(), EPS);
	}

	@Test
	void cycleOrderWraps() {
		assertEquals(EntityProfile.PLAYER, EntityProfile.POINT.next());
		assertEquals(EntityProfile.RAVAGER, EntityProfile.PLAYER.next());
		assertEquals(EntityProfile.POINT, EntityProfile.RAVAGER.next());
	}
}
