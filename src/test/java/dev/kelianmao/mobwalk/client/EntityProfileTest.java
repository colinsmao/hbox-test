package dev.kelianmao.mobwalk.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the shipped {@link EntityProfile} values and the cycle / MaLiLib
 * {@link EntityProfile.Option} entries. Point keeps {@code height 0} as the
 * oracle baseline; settings default is Player.
 */
final class EntityProfileTest {
	private static final double EPS = 1.0e-9;

	@Test
	void shippedHeights() {
		assertEquals(0.0, EntityProfile.POINT.height(), EPS);
		assertEquals(1.8, EntityProfile.PLAYER.height(), EPS);
		assertEquals(2.2, EntityProfile.RAVAGER.height(), EPS);
		assertEquals(2.9, EntityProfile.WARDEN.height(), EPS);
		assertEquals(1.95, EntityProfile.ZOMBIE_WITCH.height(), EPS);
		assertEquals(1.99, EntityProfile.SKELETON.height(), EPS);
	}

	@Test
	void shippedWidths() {
		assertEquals(0.0, EntityProfile.POINT.width(), EPS);
		assertEquals(0.6, EntityProfile.PLAYER.width(), EPS);
		assertEquals(1.95, EntityProfile.RAVAGER.width(), EPS);
		assertEquals(0.9, EntityProfile.WARDEN.width(), EPS);
		assertEquals(0.6, EntityProfile.ZOMBIE_WITCH.width(), EPS);
		assertEquals(0.6, EntityProfile.SKELETON.width(), EPS);
	}

	@Test
	void shippedReach() {
		assertEquals(1.0, EntityProfile.POINT.reach(), EPS);
		assertEquals(EntityProfile.DEFAULT_JUMP_REACH, EntityProfile.PLAYER.reach(), EPS);
		assertEquals(EntityProfile.DEFAULT_JUMP_REACH, EntityProfile.RAVAGER.reach(), EPS);
		assertEquals(EntityProfile.DEFAULT_JUMP_REACH, EntityProfile.WARDEN.reach(), EPS);
		assertEquals(EntityProfile.DEFAULT_JUMP_REACH, EntityProfile.ZOMBIE_WITCH.reach(), EPS);
		assertEquals(EntityProfile.DEFAULT_JUMP_REACH, EntityProfile.SKELETON.reach(), EPS);
		assertEquals(1.2522, EntityProfile.DEFAULT_JUMP_REACH, EPS);
	}

	@Test
	void zombieWitchDisplayName() {
		assertEquals("Zombie/Witch", EntityProfile.ZOMBIE_WITCH.name());
	}

	@Test
	void cycleOrderWraps() {
		assertEquals(EntityProfile.PLAYER, EntityProfile.POINT.next());
		assertEquals(EntityProfile.RAVAGER, EntityProfile.PLAYER.next());
		assertEquals(EntityProfile.POINT, EntityProfile.RAVAGER.next());
	}

	@Test
	void optionCycleForward() {
		assertEquals(EntityProfile.Option.PLAYER, EntityProfile.Option.POINT.cycle(true));
		assertEquals(EntityProfile.Option.RAVAGER, EntityProfile.Option.PLAYER.cycle(true));
		assertEquals(EntityProfile.Option.POINT, EntityProfile.Option.RAVAGER.cycle(true));
	}

	@Test
	void optionCycleBackward() {
		assertEquals(EntityProfile.Option.RAVAGER, EntityProfile.Option.POINT.cycle(false));
		assertEquals(EntityProfile.Option.POINT, EntityProfile.Option.PLAYER.cycle(false));
		assertEquals(EntityProfile.Option.PLAYER, EntityProfile.Option.RAVAGER.cycle(false));
	}

	@Test
	void optionFromString() {
		assertEquals(EntityProfile.Option.POINT, EntityProfile.Option.PLAYER.fromString("point"));
		assertEquals(EntityProfile.Option.PLAYER, EntityProfile.Option.POINT.fromString("Player"));
		assertEquals(EntityProfile.Option.RAVAGER, EntityProfile.Option.POINT.fromString("ravager"));
		assertEquals(EntityProfile.Option.PLAYER, EntityProfile.Option.POINT.fromString("nope"));
		assertEquals(EntityProfile.Option.PLAYER, EntityProfile.Option.POINT.fromString(null));
	}

	@Test
	void optionOfMapsProfile() {
		assertEquals(EntityProfile.Option.POINT, EntityProfile.Option.of(EntityProfile.POINT));
		assertEquals(EntityProfile.Option.PLAYER, EntityProfile.Option.of(EntityProfile.PLAYER));
		assertEquals(EntityProfile.Option.RAVAGER, EntityProfile.Option.of(EntityProfile.RAVAGER));
		assertEquals(EntityProfile.Option.PLAYER, EntityProfile.Option.of(null));
	}
}
