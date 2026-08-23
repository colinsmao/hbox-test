package dev.kelianmao.mobwalk.client.config;

import dev.kelianmao.mobwalk.client.config.ProfileRoster.RawBuiltinRow;
import dev.kelianmao.mobwalk.client.config.ProfileRoster.RawCustomRow;
import dev.kelianmao.mobwalk.client.config.ProfileRoster.SanitizeResult;
import dev.kelianmao.mobwalk.client.surface.EntityProfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;


/**
 * Pins {@link ProfileRoster} defaults, enable/cycle/soft-disable, and sanitize.
 */
final class ProfileRosterTest {
  private static final double EPS = 1.0e-9;

  /** The {@code showPointProfile ? 0 : 1} seed skip is only correct while Point leads. */
  @Test
  void pointIsTheFirstBuiltinSeed() {
    assertEquals("point", ProfileRoster.BUILTIN_SEEDS.getFirst().id());
    assertFalse(ProfileRoster.BUILTIN_SEEDS.getFirst().defaultEnabled());
  }

  @Test
  void defaultsHidePointUnlessShown() {
    assertTrue(ProfileRoster.defaults(false).findById("point").isEmpty());
    assertEquals("point", ProfileRoster.defaults(true).builtins().getFirst().id());
    assertFalse(enabled(ProfileRoster.defaults(true), "point"));
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
    // Hidden Point, a disabled builtin, and an unknown id all fall back to first enabled.
    assertEquals(Optional.of("player"), roster.resolveActiveId("point"));
    assertEquals(Optional.of("player"), roster.resolveActiveId("skeleton"));
    assertEquals(Optional.of("player"), roster.resolveActiveId("nope"));
  }

  @Test
  void disableActiveFallsBackToFirstRemaining() {
    // Player off; others default → first remaining enabled is ravager
    ProfileRoster roster = withBuiltinEnables(
      false, true, true, true, false
    );
    assertEquals(Optional.of("ravager"), roster.resolveActiveId("player"));
  }

  @Test
  void emptyEnabledIsSoftDisabled() {
    ProfileRoster roster = withBuiltinEnables(
      false, false, false, false, false
    );
    assertFalse(roster.hasEnabledProfile());
    assertTrue(roster.enabledEntries().isEmpty());
    assertEquals(Optional.empty(), roster.resolveActiveId("player"));
    assertEquals(Optional.empty(), roster.cycle("player", true));
    assertEquals(Optional.empty(), roster.profileIfEnabled("player"));
  }

  @Test
  void cycleSkipsDisabledAndWraps() {
    ProfileRoster roster = ProfileRoster.defaults();
    assertEquals(Optional.of("ravager"), roster.cycle("player", true));
    assertEquals(Optional.of("warden"), roster.cycle("ravager", true));
    assertEquals(Optional.of("zombie"), roster.cycle("warden", true));
    assertEquals(Optional.of("player"), roster.cycle("zombie", true));
    // Disabled / unknown id → first enabled (same as resolve)
    assertEquals(Optional.of("player"), roster.cycle("point", true));
    assertEquals(Optional.of("player"), roster.cycle("nope", true));
  }

  @Test
  void cycleSingleEnabledIsNoOp() {
    ProfileRoster roster = withBuiltinEnables(
      true, false, false, false, false
    );
    assertEquals(Optional.of("player"), roster.cycle("player", true));
    assertEquals(Optional.of("player"), roster.cycle("ravager", true));
  }

  @Test
  void blankCustomNameRestoresPreviousOrFallback() {
    List<ProfileRoster.Entry> prior = ProfileRoster.sanitize(
      null,
      List.of(new RawCustomRow("Tall", 0.6, 1.8, EntityProfile.DEFAULT_JUMP_REACH, true)),
      "player"
    ).roster().customs();

    SanitizeResult restored = ProfileRoster.sanitize(
      null,
      List.of(new RawCustomRow("", 0.6, 1.8, EntityProfile.DEFAULT_JUMP_REACH, true)),
      "player",
      prior
    );
    assertTrue(restored.repaired());
    assertEquals("Tall", restored.roster().customs().getFirst().profile().name());

    SanitizeResult fallback = ProfileRoster.sanitize(
      null,
      List.of(new RawCustomRow("", 0.6, 1.8, EntityProfile.DEFAULT_JUMP_REACH, true)),
      "player"
    );
    assertTrue(fallback.repaired());
    assertEquals(
      ProfileRoster.FALLBACK_CUSTOM_NAME,
      fallback.roster().customs().getFirst().profile().name()
    );
  }

  @Test
  void blankCustomNameRestoresThenUniquifiesAgainstBuiltin() {
    List<ProfileRoster.Entry> prior = ProfileRoster.sanitize(
      null,
      List.of(new RawCustomRow("Ravager (1)", 0.6, 1.8, EntityProfile.DEFAULT_JUMP_REACH, true)),
      "player"
    ).roster().customs();

    SanitizeResult restored = ProfileRoster.sanitize(
      null,
      List.of(new RawCustomRow("", 0.6, 1.8, EntityProfile.DEFAULT_JUMP_REACH, true)),
      "player",
      prior
    );
    assertTrue(restored.repaired());
    assertEquals("Ravager (1)", restored.roster().customs().getFirst().profile().name());
  }

  @Test
  void customParticipatesInCycle() {
    SanitizeResult result = ProfileRoster.sanitize(
      null,
      List.of(new RawCustomRow("Tall", 0.6, 2.5, EntityProfile.DEFAULT_JUMP_REACH, true)),
      "zombie"
    );
    ProfileRoster roster = result.roster();
    assertEquals(Optional.of("custom0"), roster.cycle("zombie", true));
    assertEquals(Optional.of("player"), roster.cycle("custom0", true));
    assertEquals(2.5, roster.findById("custom0").orElseThrow().profile().height(), EPS);
  }

  @Test
  void addBeforeExistingDoesNotReindexStableName() {
    List<ProfileRoster.Entry> prior = ProfileRoster.sanitize(
      defaultRawBuiltins(),
      List.of(new RawCustomRow("Player (1)", 0.6, 1.8, EntityProfile.DEFAULT_JUMP_REACH, true)),
      "player"
    ).roster().customs();
    assertEquals("Player (1)", prior.getFirst().profile().name());

    // MaLiLib ADD inserts before the row: new Player clone, then existing Player (1).
    SanitizeResult result = ProfileRoster.sanitize(
      defaultRawBuiltins(),
      List.of(
        new RawCustomRow("Player", 0.6, 1.8, EntityProfile.DEFAULT_JUMP_REACH, true),
        new RawCustomRow("Player (1)", 0.6, 1.8, EntityProfile.DEFAULT_JUMP_REACH, true)
      ),
      "player",
      prior
    );
    assertTrue(result.repaired());
    assertEquals("Player (2)", result.roster().customs().get(0).profile().name());
    assertEquals("Player (1)", result.roster().customs().get(1).profile().name());
  }

  @Test
  void renameOneOfTwoDoesNotTouchSibling() {
    List<ProfileRoster.Entry> prior = ProfileRoster.sanitize(
      defaultRawBuiltins(),
      List.of(
        new RawCustomRow("Ravager (1)", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true),
        new RawCustomRow("Ravager (2)", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true)
      ),
      "player"
    ).roster().customs();

    SanitizeResult result = ProfileRoster.sanitize(
      defaultRawBuiltins(),
      List.of(
        new RawCustomRow("Ravager (1)", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true),
        new RawCustomRow("Ravager", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true)
      ),
      "player",
      prior
    );
    assertTrue(result.repaired());
    assertEquals("Ravager (1)", result.roster().customs().get(0).profile().name());
    assertEquals("Ravager (2)", result.roster().customs().get(1).profile().name());
  }

  @Test
  void collidingCustomNamesGetStoredSuffixes() {
    SanitizeResult result = ProfileRoster.sanitize(
      null,
      List.of(
        new RawCustomRow("Ravager", 1.95, 2.2, EntityProfile.DEFAULT_JUMP_REACH, true),
        new RawCustomRow("Ravager", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true)
      ),
      "zombie"
    );
    assertTrue(result.repaired());
    ProfileRoster roster = result.roster();
    assertEquals(Optional.of("custom0"), roster.cycle("zombie", true));
    assertEquals(Optional.of("custom1"), roster.cycle("custom0", true));
    assertEquals(Optional.of("player"), roster.cycle("custom1", true));
    // Builtin Ravager taken → customs are Ravager (1) and Ravager (2).
    assertEquals("Ravager (1)", roster.customs().get(0).profile().name());
    assertEquals("Ravager (2)", roster.customs().get(1).profile().name());
    assertEquals("Ravager (1)", roster.displayLabel("custom0"));
    assertEquals("Ravager (2)", roster.displayLabel("custom1"));
    assertEquals("Player", roster.displayLabel("player"));
  }

  @Test
  void disabledBuiltinStillForcesCustomSuffix() {
    List<RawBuiltinRow> builtins = defaultRawBuiltins();
    EntityProfile ravager = EntityProfile.RAVAGER;
    builtins.set(1, new RawBuiltinRow(
      ravager.name(), ravager.width(), ravager.height(), ravager.reach(), false
    ));
    SanitizeResult result = ProfileRoster.sanitize(
      builtins,
      List.of(new RawCustomRow("Ravager", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true)),
      "player"
    );
    assertTrue(result.repaired());
    ProfileRoster roster = result.roster();
    assertFalse(enabled(roster, "ravager"));
    assertEquals("Ravager (1)", roster.customs().getFirst().profile().name());
    assertEquals("Ravager (1)", roster.displayLabel("custom0"));
    assertEquals("Ravager", roster.displayLabel("ravager"));
  }

  @Test
  void stemAwareUniqueWhenCopyingSuffixedName() {
    SanitizeResult result = ProfileRoster.sanitize(
      null,
      List.of(
        new RawCustomRow("Ravager (1)", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true),
        new RawCustomRow("Ravager (1)", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true)
      ),
      "player"
    );
    assertTrue(result.repaired());
    assertEquals("Ravager (1)", result.roster().customs().get(0).profile().name());
    assertEquals("Ravager (2)", result.roster().customs().get(1).profile().name());
  }

  @Test
  void typedRavagerAfterRavager1BecomesRavager2() {
    SanitizeResult result = ProfileRoster.sanitize(
      null,
      List.of(
        new RawCustomRow("Ravager (1)", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true),
        new RawCustomRow("Ravager", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true)
      ),
      "player"
    );
    assertTrue(result.repaired());
    assertEquals("Ravager (1)", result.roster().customs().get(0).profile().name());
    assertEquals("Ravager (2)", result.roster().customs().get(1).profile().name());
  }

  @Test
  void twoFoosWithoutBuiltinFoo() {
    SanitizeResult result = ProfileRoster.sanitize(
      null,
      List.of(
        new RawCustomRow("Foo", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true),
        new RawCustomRow("Foo", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true)
      ),
      "player"
    );
    assertTrue(result.repaired());
    assertEquals("Foo", result.roster().customs().get(0).profile().name());
    assertEquals("Foo (1)", result.roster().customs().get(1).profile().name());
  }

  @Test
  void bareFooFreeWhenOnlyFoo1Taken() {
    SanitizeResult result = ProfileRoster.sanitize(
      defaultRawBuiltins(),
      List.of(
        new RawCustomRow("Foo (1)", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true),
        new RawCustomRow("Foo", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true)
      ),
      "player"
    );
    assertFalse(result.repaired());
    assertEquals("Foo (1)", result.roster().customs().get(0).profile().name());
    assertEquals("Foo", result.roster().customs().get(1).profile().name());
  }

  @Test
  void freeFoo1StaysWhenNoCollision() {
    SanitizeResult result = ProfileRoster.sanitize(
      defaultRawBuiltins(),
      List.of(new RawCustomRow("Foo (1)", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true)),
      "player"
    );
    assertFalse(result.repaired());
    assertEquals("Foo (1)", result.roster().customs().getFirst().profile().name());
  }

  @Test
  void noSpaceSuffixIsSeparateSeries() {
    SanitizeResult result = ProfileRoster.sanitize(
      null,
      List.of(
        new RawCustomRow("Ravager(1)", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true),
        new RawCustomRow("Ravager(1)", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true)
      ),
      "player"
    );
    assertTrue(result.repaired());
    assertEquals("Ravager(1)", result.roster().customs().get(0).profile().name());
    assertEquals("Ravager(1) (1)", result.roster().customs().get(1).profile().name());
  }

  @Test
  void uniqueNamesAreCaseSensitive() {
    SanitizeResult result = ProfileRoster.sanitize(
      defaultRawBuiltins(),
      List.of(new RawCustomRow("ravager", 1.0, 1.0, EntityProfile.DEFAULT_JUMP_REACH, true)),
      "player"
    );
    assertFalse(result.repaired());
    assertEquals("ravager", result.roster().customs().getFirst().profile().name());
  }

  @Test
  void trailingSpacesStrippedFromCustomName() {
    SanitizeResult result = ProfileRoster.sanitize(
      null,
      List.of(new RawCustomRow("Tall  ", 0.6, 1.8, EntityProfile.DEFAULT_JUMP_REACH, true)),
      "player"
    );
    assertTrue(result.repaired());
    assertEquals("Tall", result.roster().customs().getFirst().profile().name());
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
    SanitizeResult result = ProfileRoster.sanitize(broken, List.of(), "player", null, true);
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
  void sanitizePreservesRawBuiltinOrderForCycle() {
    List<RawBuiltinRow> reordered = defaultRawBuiltins();
    // Ravager then Player … (swap first two)
    RawBuiltinRow player = reordered.get(0);
    RawBuiltinRow ravager = reordered.get(1);
    reordered.set(0, ravager);
    reordered.set(1, player);
    SanitizeResult result = ProfileRoster.sanitize(reordered, List.of(), "player");
    assertFalse(result.repaired());
    ProfileRoster roster = result.roster();
    assertEquals("ravager", roster.builtins().get(0).id());
    assertEquals("player", roster.builtins().get(1).id());
    assertEquals(
      List.of("ravager", "player", "warden", "zombie"),
      roster.enabledEntries().stream().map(ProfileRoster.Entry::id).toList()
    );
    // Cycle follows the swapped table order, not seed order.
    assertEquals(Optional.of("warden"), roster.cycle("player", true));
  }

  @Test
  void sanitizeClampsNonFiniteCustomsUncapped() {
    List<RawCustomRow> many = List.of(
      new RawCustomRow("A", 0.6, 1.8, EntityProfile.DEFAULT_JUMP_REACH, true),
      new RawCustomRow("B", -1.0, 1.0, 1.0, true),
      new RawCustomRow("C", Double.NaN, 1.0, 1.0, true),
      new RawCustomRow("D", 0.6, 1.8, EntityProfile.DEFAULT_JUMP_REACH, true),
      new RawCustomRow("E", 10.0, 1.8, EntityProfile.DEFAULT_JUMP_REACH, true)
    );
    SanitizeResult result = ProfileRoster.sanitize(null, many, "player");
    assertTrue(result.repaired());
    assertEquals(5, result.roster().customs().size());
    assertEquals(0.0, result.roster().customs().get(1).profile().width(), EPS);
    assertEquals(EntityProfile.PLAYER.width(), result.roster().customs().get(2).profile().width(), EPS);
    assertEquals("C", result.roster().customs().get(2).profile().name());
    assertEquals(ProfileRoster.MAX_CUSTOM_WIDTH, result.roster().customs().get(4).profile().width(), EPS);
    assertEquals("E", result.roster().customs().get(4).profile().name());
  }

  @Test
  void sanitizeAcceptsZombieAlias() {
    List<RawBuiltinRow> rows = defaultRawBuiltins();
    rows.set(3, new RawBuiltinRow("Zombie", 0.6, 1.95, EntityProfile.DEFAULT_JUMP_REACH, false));
    SanitizeResult result = ProfileRoster.sanitize(rows, List.of(), "ravager");
    assertFalse(enabled(result.roster(), "zombie"));
    assertEquals(EntityProfile.ZOMBIE_WITCH, result.roster().findById("zombie").orElseThrow().profile());
  }

  /** Config saved while the debug toggle was on, loaded with it off. */
  @Test
  void hiddenPointIsDroppedAndNeverAppended() {
    List<RawBuiltinRow> rows = defaultRawBuiltins();
    rows.addFirst(pointRow(true));
    SanitizeResult result =
      ProfileRoster.sanitize(rows, List.of(), "point", null, false);
    ProfileRoster roster = result.roster();
    assertTrue(result.repaired());
    assertEquals(Optional.empty(), roster.findById("point"));
    assertEquals(Optional.of("player"), roster.resolveActiveId("point"));
    assertEquals(Optional.of("player"), result.activeId());
  }

  @Test
  void shownPointIsAppendedFirstAndOff() {
    SanitizeResult result =
      ProfileRoster.sanitize(defaultRawBuiltins(), List.of(), "player", null, true);
    ProfileRoster roster = result.roster();
    assertTrue(result.repaired());
    assertEquals("point", roster.builtins().getFirst().id());
    assertFalse(enabled(roster, "point"));
  }

  private static boolean enabled(ProfileRoster roster, String id) {
    return roster.findById(id).orElseThrow().enabled();
  }

  /** Enables in builtin seed order (hot path, no Point); omitted trailing flags stay Off. */
  private static ProfileRoster withBuiltinEnables(
    boolean player, boolean ravager, boolean warden, boolean zombie, boolean skeleton
  ) {
    boolean[] flags = new boolean[hotPathSeeds().size()];
    boolean[] given = {player, ravager, warden, zombie, skeleton};
    System.arraycopy(given, 0, flags, 0, given.length);
    List<RawBuiltinRow> rows = rawBuiltins(flags);
    return ProfileRoster.sanitize(rows, List.of(), "player").roster();
  }

  /**
   * Seeds a player sees: everything after the debug Point, whose index 0 slot is pinned
   * by {@link #pointIsTheFirstBuiltinSeed}. Spelled out rather than read from
   * {@code firstSeed} so these fixtures stay independent of the code under test.
   */
  private static List<ProfileRoster.BuiltinSeed> hotPathSeeds() {
    return ProfileRoster.BUILTIN_SEEDS.subList(1, ProfileRoster.BUILTIN_SEEDS.size());
  }

  /** Canonical rows for the hot-path seeds with their default enables. */
  private static List<RawBuiltinRow> defaultRawBuiltins() {
    List<RawBuiltinRow> rows = new java.util.ArrayList<>();
    for (ProfileRoster.BuiltinSeed seed : hotPathSeeds()) {
      EntityProfile p = seed.profile();
      rows.add(new RawBuiltinRow(
        p.name(), p.width(), p.height(), p.reach(), seed.defaultEnabled()
      ));
    }
    return rows;
  }

  /** Canonical rows for the hot-path seeds with the given enables. */
  private static List<RawBuiltinRow> rawBuiltins(boolean[] enables) {
    List<ProfileRoster.BuiltinSeed> seeds = hotPathSeeds();
    List<RawBuiltinRow> rows = new java.util.ArrayList<>(seeds.size());
    for (int i = 0; i < seeds.size(); i++) {
      EntityProfile p = seeds.get(i).profile();
      rows.add(new RawBuiltinRow(p.name(), p.width(), p.height(), p.reach(), enables[i]));
    }
    return rows;
  }

  /** A canonical row for the debug Point seed at index 0. */
  private static RawBuiltinRow pointRow(boolean enabled) {
    EntityProfile p = ProfileRoster.BUILTIN_SEEDS.getFirst().profile();
    return new RawBuiltinRow(p.name(), p.width(), p.height(), p.reach(), enabled);
  }
}
