package dev.kelianmao.mobwalk.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.kelianmao.mobwalk.client.ProfileRoster.RawBuiltinRow;
import dev.kelianmao.mobwalk.client.ProfileRoster.RawCustomRow;
import dev.kelianmao.mobwalk.client.ProfileRoster.SanitizeResult;

/**
 * Pins {@link ProfileRoster} defaults, enable/cycle/soft-disable, and sanitize.
 */
final class ProfileRosterTest {
	private static final double EPS = 1.0e-9;

	@Test
	void defaultsSixBuiltinsDefaultEnables() {
		ProfileRoster roster = ProfileRoster.defaults();
		assertEquals(6, roster.builtins().size());
		assertEquals(0, roster.customs().size());
		assertFalse(enabled(roster, "point"));
		assertTrue(enabled(roster, "player"));
		assertTrue(enabled(roster, "ravager"));
		assertTrue(enabled(roster, "warden"));
		assertTrue(enabled(roster, "zombie"));
		assertFalse(enabled(roster, "skeleton"));
		assertEquals(
			List.of("player", "ravager", "warden", "zombie"),
			roster.enabledEntries().stream().map(ProfileRoster.Entry::id).toList()
		);
	}

	@Test
	void builtinGeometryMatchesSeeds() {
		ProfileRoster roster = ProfileRoster.defaults();
		assertEquals(EntityProfile.WARDEN, roster.findById("warden").orElseThrow().profile());
		assertEquals(EntityProfile.ZOMBIE_WITCH, roster.findById("zombie").orElseThrow().profile());
		assertEquals(0.6, EntityProfile.SKELETON.width(), EPS);
		assertEquals(1.99, EntityProfile.SKELETON.height(), EPS);
	}

	@Test
	void resolveActiveKeepsEnabled() {
		ProfileRoster roster = ProfileRoster.defaults();
		assertEquals(Optional.of("player"), roster.resolveActiveId("player"));
		assertEquals(Optional.of("warden"), roster.resolveActiveId("warden"));
	}

	@Test
	void resolveActiveFallsBackWhenDisabled() {
		ProfileRoster roster = ProfileRoster.defaults();
		// Point is off → first enabled is player
		assertEquals(Optional.of("player"), roster.resolveActiveId("point"));
		assertEquals(Optional.of("player"), roster.resolveActiveId("skeleton"));
		assertEquals(Optional.of("player"), roster.resolveActiveId("nope"));
	}

	@Test
	void disableActiveFallsBackToFirstRemaining() {
		// Player off; others default → first remaining enabled is ravager
		ProfileRoster roster = withBuiltinEnables(
			false, false, true, true, true, false
		);
		assertEquals(Optional.of("ravager"), roster.resolveActiveId("player"));
		assertEquals(Optional.of("ravager"), roster.fallbackActiveId());
	}

	@Test
	void emptyEnabledIsSoftDisabled() {
		ProfileRoster roster = withBuiltinEnables(
			false, false, false, false, false, false
		);
		assertFalse(roster.hasEnabledProfile());
		assertTrue(roster.enabledEntries().isEmpty());
		assertEquals(Optional.empty(), roster.resolveActiveId("player"));
		assertEquals(Optional.empty(), roster.cycle("player"));
		assertEquals(Optional.empty(), roster.fallbackActiveId());
		assertEquals(Optional.empty(), roster.profileIfEnabled("player"));
	}

	@Test
	void cycleSkipsDisabledAndWraps() {
		ProfileRoster roster = ProfileRoster.defaults();
		assertEquals(Optional.of("ravager"), roster.cycle("player"));
		assertEquals(Optional.of("warden"), roster.cycle("ravager"));
		assertEquals(Optional.of("zombie"), roster.cycle("warden"));
		assertEquals(Optional.of("player"), roster.cycle("zombie"));
		// Disabled / unknown id → first enabled (same as resolve)
		assertEquals(Optional.of("player"), roster.cycle("point"));
		assertEquals(Optional.of("player"), roster.cycle("nope"));
	}

	@Test
	void cycleSingleEnabledIsNoOp() {
		ProfileRoster roster = withBuiltinEnables(
			false, true, false, false, false, false
		);
		assertEquals(Optional.of("player"), roster.cycle("player"));
		assertEquals(Optional.of("player"), roster.cycle("ravager"));
	}

	@Test
	void blankCustomNameDoesNotParticipate() {
		ProfileRoster roster = ProfileRoster.sanitize(
			null,
			List.of(new RawCustomRow("", 0.6, 1.8, EntityProfile.DEFAULT_JUMP_REACH, true)),
			"player"
		).roster();
		assertEquals(1, roster.customs().size());
		assertTrue(roster.customs().getFirst().enabled());
		assertFalse(roster.customs().getFirst().participates());
		assertEquals(
			List.of("player", "ravager", "warden", "zombie"),
			roster.enabledEntries().stream().map(ProfileRoster.Entry::id).toList()
		);
	}

	@Test
	void customParticipatesInCycle() {
		SanitizeResult result = ProfileRoster.sanitize(
			null,
			List.of(new RawCustomRow("Tall", 0.6, 2.5, EntityProfile.DEFAULT_JUMP_REACH, true)),
			"zombie"
		);
		ProfileRoster roster = result.roster();
		assertEquals(Optional.of("custom0"), roster.cycle("zombie"));
		assertEquals(Optional.of("player"), roster.cycle("custom0"));
		assertEquals(2.5, roster.findById("custom0").orElseThrow().profile().height(), EPS);
	}

	@Test
	void sanitizeResetsBuiltinGeometryPreservesEnable() {
		List<RawBuiltinRow> broken = List.of(
			new RawBuiltinRow("Point", 9, 9, 9, true),
			new RawBuiltinRow("Player", 0, 0, 0, false),
			new RawBuiltinRow("Ravager", 1, 1, 1, true),
			new RawBuiltinRow("Warden", 0.9, 2.9, EntityProfile.DEFAULT_JUMP_REACH, false),
			new RawBuiltinRow("Zombie/Witch", 0.6, 1.95, EntityProfile.DEFAULT_JUMP_REACH, true),
			new RawBuiltinRow("Skeleton", 0.6, 1.99, EntityProfile.DEFAULT_JUMP_REACH, true)
		);
		SanitizeResult result = ProfileRoster.sanitize(broken, List.of(), "player");
		assertTrue(result.repaired());
		ProfileRoster roster = result.roster();
		assertEquals(EntityProfile.POINT, roster.findById("point").orElseThrow().profile());
		assertTrue(enabled(roster, "point"));
		assertFalse(enabled(roster, "player"));
		assertFalse(enabled(roster, "warden"));
		assertTrue(enabled(roster, "skeleton"));
		// Active player is disabled → first enabled is point
		assertEquals(Optional.of("point"), result.activeId());
	}

	@Test
	void sanitizeWrongBuiltinCountAppendsMissingInSeedOrder() {
		SanitizeResult result = ProfileRoster.sanitize(
			List.of(new RawBuiltinRow("Player", 0.6, 1.8, EntityProfile.DEFAULT_JUMP_REACH, true)),
			List.of(),
			"player"
		);
		assertTrue(result.repaired());
		assertEquals(6, result.roster().builtins().size());
		assertEquals(
			List.of("player", "point", "ravager", "warden", "zombie", "skeleton"),
			result.roster().builtins().stream().map(ProfileRoster.Entry::id).toList()
		);
		assertTrue(enabled(result.roster(), "player"));
		assertFalse(enabled(result.roster(), "point"));
	}

	@Test
	void sanitizePreservesRawBuiltinOrderForCycle() {
		List<RawBuiltinRow> reordered = defaultRawBuiltins();
		// Player then Point … (swap first two)
		RawBuiltinRow point = reordered.get(0);
		RawBuiltinRow player = reordered.get(1);
		reordered.set(0, player);
		reordered.set(1, point);
		SanitizeResult result = ProfileRoster.sanitize(reordered, List.of(), "player");
		assertFalse(result.repaired());
		ProfileRoster roster = result.roster();
		assertEquals(
			List.of("player", "point", "ravager", "warden", "zombie", "skeleton"),
			roster.builtins().stream().map(ProfileRoster.Entry::id).toList()
		);
		assertEquals(
			List.of("player", "ravager", "warden", "zombie"),
			roster.enabledEntries().stream().map(ProfileRoster.Entry::id).toList()
		);
		assertEquals(Optional.of("ravager"), roster.cycle("player"));
	}

	@Test
	void sanitizeCapsCustomsAndClamps() {
		List<RawCustomRow> many = List.of(
			new RawCustomRow("A", 0.6, 1.8, EntityProfile.DEFAULT_JUMP_REACH, true),
			new RawCustomRow("B", -1.0, 1.0, 1.0, true),
			new RawCustomRow("C", Double.NaN, 1.0, 1.0, true),
			new RawCustomRow("D", 0.6, 1.8, EntityProfile.DEFAULT_JUMP_REACH, true),
			new RawCustomRow("E", 0.6, 1.8, EntityProfile.DEFAULT_JUMP_REACH, true)
		);
		SanitizeResult result = ProfileRoster.sanitize(null, many, "player");
		assertTrue(result.repaired());
		assertEquals(3, result.roster().customs().size());
		assertEquals(0.0, result.roster().customs().get(1).profile().width(), EPS);
		assertEquals(EntityProfile.PLAYER.width(), result.roster().customs().get(2).profile().width(), EPS);
		assertEquals("C", result.roster().customs().get(2).profile().name());
	}

	@Test
	void sanitizeAcceptsZombieAlias() {
		List<RawBuiltinRow> rows = defaultRawBuiltins();
		rows.set(4, new RawBuiltinRow("Zombie", 0.6, 1.95, EntityProfile.DEFAULT_JUMP_REACH, false));
		SanitizeResult result = ProfileRoster.sanitize(rows, List.of(), "ravager");
		assertFalse(enabled(result.roster(), "zombie"));
		assertEquals(EntityProfile.ZOMBIE_WITCH, result.roster().findById("zombie").orElseThrow().profile());
	}

	@Test
	void playerDefaultCustomProfile() {
		assertEquals(EntityProfile.PLAYER, ProfileRoster.playerDefaultCustomProfile());
	}

	private static boolean enabled(ProfileRoster roster, String id) {
		return roster.findById(id).orElseThrow().enabled();
	}

	/** Enables in builtin seed order. */
	private static ProfileRoster withBuiltinEnables(
		boolean point, boolean player, boolean ravager,
		boolean warden, boolean zombie, boolean skeleton
	) {
		boolean[] flags = {point, player, ravager, warden, zombie, skeleton};
		List<RawBuiltinRow> rows = new ArrayListRows(flags);
		return ProfileRoster.sanitize(rows, List.of(), "player").roster();
	}

	private static List<RawBuiltinRow> defaultRawBuiltins() {
		List<RawBuiltinRow> rows = new java.util.ArrayList<>();
		for (ProfileRoster.BuiltinSeed seed : ProfileRoster.BUILTIN_SEEDS) {
			EntityProfile p = seed.profile();
			rows.add(new RawBuiltinRow(
				p.name(), p.width(), p.height(), p.reach(), seed.defaultEnabled()
			));
		}
		return rows;
	}

	private static final class ArrayListRows extends java.util.ArrayList<RawBuiltinRow> {
		ArrayListRows(boolean[] flags) {
			for (int i = 0; i < ProfileRoster.BUILTIN_SEEDS.size(); i++) {
				ProfileRoster.BuiltinSeed seed = ProfileRoster.BUILTIN_SEEDS.get(i);
				EntityProfile p = seed.profile();
				add(new RawBuiltinRow(p.name(), p.width(), p.height(), p.reach(), flags[i]));
			}
		}
	}
}
