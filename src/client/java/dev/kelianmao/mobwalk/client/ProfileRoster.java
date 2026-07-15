package dev.kelianmao.mobwalk.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Builtin + custom profile roster: enable flags, cycle order, active-id resolve,
 * and sanitize of raw table snapshots. UI / MaLiLib wiring lands in a later step;
 * this type is pure logic (unit-tested).
 *
 * <p>Roster order: Point → Player → Ravager → Warden → Zombie/Witch → Skeleton →
 * custom0… Soft-disabled when no entry is enabled ({@link #hasEnabledProfile()}
 * is false); flood/cycle callers gate on that.
 */
public final class ProfileRoster {
	public static final int MAX_CUSTOMS = 3;

	/** Code-owned builtin seed (geometry fixed; enable has a default). */
	public record BuiltinSeed(String id, EntityProfile profile, boolean defaultEnabled) {
		public BuiltinSeed {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(profile, "profile");
		}
	}

	/**
	 * One roster row after sanitize. Builtin geometry always matches the seed;
	 * customs may be blank-name (kept in the table, ignored by
	 * {@link #enabledEntries()}).
	 */
	public record Entry(String id, EntityProfile profile, boolean enabled, boolean builtin) {
		public Entry {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(profile, "profile");
		}

		/** Blank custom names are unused slots. */
		public boolean participates() {
			if (builtin) {
				return true;
			}
			String name = profile.name();
			return name != null && !name.isBlank();
		}
	}

	/** Raw builtin row as loaded from JSON / a table before sanitize. */
	public record RawBuiltinRow(
		String name, double width, double height, double reach, boolean enabled
	) {}

	/** Raw custom row as loaded from JSON / a table before sanitize. */
	public record RawCustomRow(
		String name, double width, double height, double reach, boolean enabled
	) {}

	/**
	 * Result of {@link #sanitize}: repaired builtins/customs, whether anything
	 * changed, and the resolved active id (empty when soft-disabled).
	 */
	public record SanitizeResult(
		ProfileRoster roster, boolean repaired, Optional<String> activeId
	) {}

	public static final List<BuiltinSeed> BUILTIN_SEEDS = List.of(
		new BuiltinSeed("point", EntityProfile.POINT, false),
		new BuiltinSeed("player", EntityProfile.PLAYER, true),
		new BuiltinSeed("ravager", EntityProfile.RAVAGER, true),
		new BuiltinSeed("warden", EntityProfile.WARDEN, true),
		new BuiltinSeed("zombie", EntityProfile.ZOMBIE_WITCH, true),
		new BuiltinSeed("skeleton", EntityProfile.SKELETON, false)
	);

	private final List<Entry> builtins;
	private final List<Entry> customs;

	private ProfileRoster(List<Entry> builtins, List<Entry> customs) {
		this.builtins = List.copyOf(builtins);
		this.customs = List.copyOf(customs);
	}

	/** Fresh roster: six builtins with seed default enables, no customs. */
	public static ProfileRoster defaults() {
		List<Entry> builtins = new ArrayList<>(BUILTIN_SEEDS.size());
		for (BuiltinSeed seed : BUILTIN_SEEDS) {
			builtins.add(new Entry(seed.id(), seed.profile(), seed.defaultEnabled(), true));
		}
		return new ProfileRoster(builtins, List.of());
	}

	/**
	 * Player-sized custom row template (ADD / dummy): name {@code Player}, Player
	 * width/height/reach, enabled.
	 */
	public static EntityProfile playerDefaultCustomProfile() {
		return EntityProfile.PLAYER;
	}

	public List<Entry> builtins() {
		return builtins;
	}

	public List<Entry> customs() {
		return customs;
	}

	/** Builtins then customs (roster order). */
	public List<Entry> allEntries() {
		List<Entry> all = new ArrayList<>(builtins.size() + customs.size());
		all.addAll(builtins);
		all.addAll(customs);
		return List.copyOf(all);
	}

	/**
	 * Enabled rows that participate in cycle / flood (skips disabled and
	 * blank-name customs).
	 */
	public List<Entry> enabledEntries() {
		List<Entry> out = new ArrayList<>();
		for (Entry e : allEntries()) {
			if (e.enabled() && e.participates()) {
				out.add(e);
			}
		}
		return List.copyOf(out);
	}

	public boolean hasEnabledProfile() {
		return !enabledEntries().isEmpty();
	}

	public Optional<Entry> findById(String id) {
		if (id == null) {
			return Optional.empty();
		}
		for (Entry e : allEntries()) {
			if (e.id().equalsIgnoreCase(id)) {
				return Optional.of(e);
			}
		}
		return Optional.empty();
	}

	/**
	 * Profile for an id when that row is enabled and participates; otherwise
	 * empty (soft-disabled or unknown / disabled id).
	 */
	public Optional<EntityProfile> profileIfEnabled(String id) {
		return findById(id)
			.filter(e -> e.enabled() && e.participates())
			.map(Entry::profile);
	}

	/**
	 * Resolve an active id: keep it if enabled+participating; otherwise the first
	 * enabled entry’s id; empty when soft-disabled.
	 */
	public Optional<String> resolveActiveId(String activeId) {
		Optional<Entry> current = findById(activeId);
		if (current.isPresent() && current.get().enabled() && current.get().participates()) {
			return Optional.of(current.get().id());
		}
		List<Entry> enabled = enabledEntries();
		if (enabled.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(enabled.getFirst().id());
	}

	/**
	 * Next enabled id after {@code currentId} (wrap). Same id when fewer than two
	 * enabled entries; empty when soft-disabled. If {@code currentId} is missing
	 * or disabled, returns the first enabled id (same as
	 * {@link #resolveActiveId}).
	 */
	public Optional<String> cycle(String currentId) {
		List<Entry> enabled = enabledEntries();
		if (enabled.isEmpty()) {
			return Optional.empty();
		}
		if (enabled.size() == 1) {
			return Optional.of(enabled.getFirst().id());
		}
		int idx = -1;
		for (int i = 0; i < enabled.size(); i++) {
			if (enabled.get(i).id().equalsIgnoreCase(currentId)) {
				idx = i;
				break;
			}
		}
		if (idx < 0) {
			return Optional.of(enabled.getFirst().id());
		}
		return Optional.of(enabled.get((idx + 1) % enabled.size()).id());
	}

	/**
	 * After disabling / removing the active profile: first remaining enabled id,
	 * or empty when soft-disabled.
	 */
	public Optional<String> fallbackActiveId() {
		List<Entry> enabled = enabledEntries();
		if (enabled.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(enabled.getFirst().id());
	}

	/**
	 * Repair raw table snapshots into a valid roster. Builtin geometry is always
	 * overwritten from {@link #BUILTIN_SEEDS}; enables are preserved when a row
	 * matches a seed by id or display name. Customs are capped at
	 * {@link #MAX_CUSTOMS}; bad / non-finite sizes are clamped or dropped.
	 */
	public static SanitizeResult sanitize(
		List<RawBuiltinRow> rawBuiltins,
		List<RawCustomRow> rawCustoms,
		String activeId
	) {
		boolean repaired = false;
		List<Entry> builtins = new ArrayList<>(BUILTIN_SEEDS.size());

		if (rawBuiltins == null || rawBuiltins.size() != BUILTIN_SEEDS.size()) {
			repaired = true;
		}

		for (int i = 0; i < BUILTIN_SEEDS.size(); i++) {
			BuiltinSeed seed = BUILTIN_SEEDS.get(i);
			boolean enabled = seed.defaultEnabled();
			RawBuiltinRow matched = matchBuiltin(rawBuiltins, seed);
			if (matched != null) {
				enabled = matched.enabled();
				if (!geometryMatches(matched, seed.profile())) {
					repaired = true;
				}
				if (!namesMatch(matched.name(), seed)) {
					repaired = true;
				}
			} else {
				repaired = true;
			}
			builtins.add(new Entry(seed.id(), seed.profile(), enabled, true));
		}

		List<Entry> customs = new ArrayList<>();
		if (rawCustoms != null) {
			int kept = 0;
			for (RawCustomRow raw : rawCustoms) {
				if (raw == null) {
					repaired = true;
					continue;
				}
				if (kept >= MAX_CUSTOMS) {
					repaired = true;
					break;
				}
				CustomSanitize cs = sanitizeCustom(raw, kept);
				if (cs.dropped()) {
					repaired = true;
					continue;
				}
				if (cs.repaired()) {
					repaired = true;
				}
				customs.add(cs.entry());
				kept++;
			}
			if (rawCustoms.size() > MAX_CUSTOMS) {
				repaired = true;
			}
		}

		ProfileRoster roster = new ProfileRoster(builtins, customs);
		Optional<String> resolved = roster.resolveActiveId(activeId);
		if (activeId != null && !activeId.isBlank()) {
			Optional<String> kept = roster.findById(activeId)
				.filter(e -> e.enabled() && e.participates())
				.map(Entry::id);
			if (kept.isEmpty() && resolved.isPresent()) {
				repaired = true;
			}
			if (kept.isEmpty() && resolved.isEmpty()) {
				// Soft-disabled with a stale active id — treat as repair so save can clear it.
				repaired = true;
			}
		}
		return new SanitizeResult(roster, repaired, resolved);
	}

	private static RawBuiltinRow matchBuiltin(List<RawBuiltinRow> rawBuiltins, BuiltinSeed seed) {
		if (rawBuiltins == null) {
			return null;
		}
		for (RawBuiltinRow row : rawBuiltins) {
			if (row == null) {
				continue;
			}
			if (namesMatch(row.name(), seed)) {
				return row;
			}
		}
		return null;
	}

	private static boolean namesMatch(String name, BuiltinSeed seed) {
		if (name == null || name.isBlank()) {
			return false;
		}
		String n = name.trim();
		if (n.equalsIgnoreCase(seed.id()) || n.equalsIgnoreCase(seed.profile().name())) {
			return true;
		}
		// Accept "Zombie" / "Witch" as the combined seed.
		if ("zombie".equals(seed.id())) {
			return n.equalsIgnoreCase("Zombie")
				|| n.equalsIgnoreCase("Witch")
				|| n.equalsIgnoreCase("Zombie/Witch");
		}
		return false;
	}

	private static boolean geometryMatches(RawBuiltinRow row, EntityProfile seed) {
		return doubleEq(row.width(), seed.width())
			&& doubleEq(row.height(), seed.height())
			&& doubleEq(row.reach(), seed.reach());
	}

	private static boolean doubleEq(double a, double b) {
		return Double.compare(a, b) == 0 || Math.abs(a - b) < 1.0e-9;
	}

	private record CustomSanitize(Entry entry, boolean dropped, boolean repaired) {}

	private static CustomSanitize sanitizeCustom(RawCustomRow raw, int index) {
		String name = raw.name() == null ? "" : raw.name();
		double width = raw.width();
		double height = raw.height();
		double reach = raw.reach();
		boolean repaired = false;

		if (!Double.isFinite(width) || !Double.isFinite(height) || !Double.isFinite(reach)) {
			width = EntityProfile.PLAYER.width();
			height = EntityProfile.PLAYER.height();
			reach = EntityProfile.PLAYER.reach();
			repaired = true;
		}
		if (width < 0.0) {
			width = 0.0;
			repaired = true;
		}
		if (height < 0.0) {
			height = 0.0;
			repaired = true;
		}
		if (reach < 0.0) {
			reach = 0.0;
			repaired = true;
		}

		String id = "custom" + index;
		EntityProfile profile = new EntityProfile(name, width, height, reach);
		return new CustomSanitize(new Entry(id, profile, raw.enabled(), false), false, repaired);
	}

	/** Match a builtin seed id from a display name or id string. */
	public static Optional<String> builtinIdForName(String name) {
		if (name == null || name.isBlank()) {
			return Optional.empty();
		}
		for (BuiltinSeed seed : BUILTIN_SEEDS) {
			if (namesMatch(name, seed)) {
				return Optional.of(seed.id());
			}
		}
		return Optional.empty();
	}

	/** Lowercase id helper for customs (stable index ids preferred). */
	public static String customId(int index) {
		return "custom" + index;
	}

	@Override
	public String toString() {
		return "ProfileRoster{builtins=" + builtins + ", customs=" + customs + '}';
	}
}
