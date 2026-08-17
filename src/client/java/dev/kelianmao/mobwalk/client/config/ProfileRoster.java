package dev.kelianmao.mobwalk.client.config;

import dev.kelianmao.mobwalk.client.surface.EntityProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builtin + custom profile roster: enable flags, cycle order, active-id resolve,
 * and sanitize of raw table snapshots.
 *
 * <p>Default builtin order is {@link #BUILTIN_SEEDS}; after load/edit, builtin
 * order follows the Profiles table (reorder is real for cycle). Soft-disabled when
 * no entry is enabled ({@link #hasEnabledProfile()} is false).
 */
public final class ProfileRoster {
  /** Code-owned builtin seed (geometry fixed; enable has a default). */
  public record BuiltinSeed(String id, EntityProfile profile, boolean defaultEnabled) {
    public BuiltinSeed {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(profile, "profile");
    }
  }

  /**
   * One roster row after sanitize. Builtin geometry always matches the seed;
   * blank custom names still cycle when enabled (label {@code <empty>} until
   * sanitize restores a name).
   */
  public record Entry(String id, EntityProfile profile, boolean enabled, boolean builtin) {
    public Entry {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(profile, "profile");
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
    new BuiltinSeed("skeleton", EntityProfile.SKELETON, false),
    new BuiltinSeed("cow", EntityProfile.COW, false),
    new BuiltinSeed("sheep", EntityProfile.SHEEP, false),
    new BuiltinSeed("pig", EntityProfile.PIG, false)
  );

  /** Ghast-scale cap: flood neighbour search is {@code floor(W)+1}. */
  public static final double MAX_CUSTOM_WIDTH = 4.0;

  /** Fallback when a blank custom name has no prior non-empty name at that index. */
  public static final String FALLBACK_CUSTOM_NAME = "Custom";

  private final List<Entry> builtins;
  private final List<Entry> customs;

  private ProfileRoster(List<Entry> builtins, List<Entry> customs) {
    this.builtins = List.copyOf(builtins);
    this.customs = List.copyOf(customs);
  }

  /** Fresh roster: seed builtins with default enables, no customs. */
  public static ProfileRoster defaults() {
    List<Entry> builtins = new ArrayList<>(BUILTIN_SEEDS.size());
    for (BuiltinSeed seed : BUILTIN_SEEDS) {
      builtins.add(new Entry(seed.id(), seed.profile(), seed.defaultEnabled(), true));
    }
    return new ProfileRoster(builtins, List.of());
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
   * Enabled rows (cycle / flood eligibility).
   */
  public List<Entry> enabledEntries() {
    List<Entry> out = new ArrayList<>();
    for (Entry e : allEntries()) {
      if (e.enabled()) {
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
   * Profile for an id when that row is enabled; otherwise empty (soft-disabled
   * or unknown / disabled id).
   */
  public Optional<EntityProfile> profileIfEnabled(String id) {
    return findById(id)
      .filter(Entry::enabled)
      .map(Entry::profile);
  }

  /**
   * Resolve an active id: keep it if enabled; otherwise the first enabled
   * entry’s id; empty when soft-disabled.
   */
  public Optional<String> resolveActiveId(String activeId) {
    Optional<Entry> current = findById(activeId);
    if (current.isPresent() && current.get().enabled()) {
      return Optional.of(current.get().id());
    }
    List<Entry> enabled = enabledEntries();
    if (enabled.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(enabled.getFirst().id());
  }

  /**
   * Cycle enabled ids. {@code forward == false} walks backward. Empty when
   * soft-disabled; single enabled entry returns that id. If {@code currentId}
   * is missing or disabled, returns the first enabled id (same as
   * {@link #resolveActiveId}).
   */
  public Optional<String> cycle(String currentId, boolean forward) {
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
    int next = forward
      ? (idx + 1) % enabled.size()
      : (idx - 1 + enabled.size()) % enabled.size();
    return Optional.of(enabled.get(next).id());
  }

  /**
   * Label for cycle HUD / settings: the stored profile name (sanitize keeps
   * custom names unique with {@code (n)} suffixes). Blank → id.
   */
  public String displayLabel(String id) {
    Optional<Entry> found = findById(id);
    if (found.isEmpty()) {
      return id == null ? "" : id;
    }
    String name = found.get().profile().name();
    if (name == null || name.isBlank()) {
      return found.get().id();
    }
    return name;
  }

  /**
   * Repair raw table snapshots into a valid roster. Builtin geometry is always
   * taken from {@link #BUILTIN_SEEDS}; enables and <b>row order</b> follow the
   * raw builtin list (unknown/duplicate rows dropped; missing seeds appended in
   * seed order). Customs are uncapped in count; sizes clamp non-finite / negative
   * values, and width is capped at {@link #MAX_CUSTOM_WIDTH}. Blank custom names
   * restore the previous name at the same index when provided, else {@code "Custom"}.
   * Trailing spaces are stripped; new or renamed customs that collide are rewritten
   * to {@code Name (1)}, {@code Name (2)}, … (builtins count, including disabled).
   * Existing custom names that still appear are left unchanged.
   */
  public static SanitizeResult sanitize(
    List<RawBuiltinRow> rawBuiltins,
    List<RawCustomRow> rawCustoms,
    String activeId
  ) {
    return sanitize(rawBuiltins, rawCustoms, activeId, null);
  }

  /**
   * Like {@link #sanitize(List, List, String)} with prior custom rows for blank-name
   * restore (by index) and to keep existing names unique without reindexing siblings.
   */
  public static SanitizeResult sanitize(
    List<RawBuiltinRow> rawBuiltins,
    List<RawCustomRow> rawCustoms,
    String activeId,
    List<Entry> previousCustoms
  ) {
    boolean repaired = false;
    List<Entry> builtins = new ArrayList<>(BUILTIN_SEEDS.size());
    java.util.LinkedHashSet<String> seenIds = new java.util.LinkedHashSet<>();

    if (rawBuiltins == null) {
      repaired = true;
    } else {
      for (RawBuiltinRow row : rawBuiltins) {
        if (row == null) {
          repaired = true;
          continue;
        }
        BuiltinSeed seed = findSeedByName(row.name());
        if (seed == null) {
          repaired = true;
          continue;
        }
        if (!seenIds.add(seed.id())) {
          repaired = true;
          continue;
        }
        if (!geometryMatches(row, seed.profile())) {
          repaired = true;
        }
        if (!canonicalName(row.name(), seed)) {
          repaired = true;
        }
        builtins.add(new Entry(seed.id(), seed.profile(), row.enabled(), true));
      }
    }

    for (BuiltinSeed seed : BUILTIN_SEEDS) {
      if (seenIds.add(seed.id())) {
        repaired = true;
        builtins.add(new Entry(seed.id(), seed.profile(), seed.defaultEnabled(), true));
      }
    }

    List<Entry> customs = new ArrayList<>();
    if (rawCustoms != null) {
      List<CustomSanitize> proposed = new ArrayList<>();
      int kept = 0;
      for (RawCustomRow raw : rawCustoms) {
        if (raw == null) {
          repaired = true;
          continue;
        }
        String previousName = null;
        if (previousCustoms != null && kept < previousCustoms.size()) {
          previousName = previousCustoms.get(kept).profile().name();
        }
        CustomSanitize cs = sanitizeCustom(raw, kept, previousName);
        if (cs.dropped()) {
          repaired = true;
          continue;
        }
        proposed.add(cs);
        kept++;
      }

      Map<String, Integer> priorNameCounts = new HashMap<>();
      if (previousCustoms != null) {
        for (Entry prev : previousCustoms) {
          priorNameCounts.merge(prev.profile().name(), 1, Integer::sum);
        }
      }

      Set<String> takenNames = new HashSet<>();
      for (Entry b : builtins) {
        takenNames.add(b.profile().name());
      }

      String[] finalNames = new String[proposed.size()];
      boolean[] keptPrior = new boolean[proposed.size()];
      for (int i = 0; i < proposed.size(); i++) {
        String name = proposed.get(i).entry().profile().name();
        int count = priorNameCounts.getOrDefault(name, 0);
        if (count > 0 && !takenNames.contains(name)) {
          finalNames[i] = name;
          priorNameCounts.put(name, count - 1);
          takenNames.add(name);
          keptPrior[i] = true;
        }
      }
      for (int i = 0; i < proposed.size(); i++) {
        CustomSanitize cs = proposed.get(i);
        Entry entry = cs.entry();
        String name;
        if (keptPrior[i]) {
          name = finalNames[i];
        } else {
          name = nextUniqueName(entry.profile().name(), takenNames);
          takenNames.add(name);
        }
        if (!name.equals(entry.profile().name()) || cs.repaired()) {
          repaired = true;
        }
        EntityProfile p = entry.profile();
        customs.add(new Entry(
          entry.id(),
          new EntityProfile(name, p.width(), p.height(), p.reach()),
          entry.enabled(),
          false
        ));
      }
    }

    ProfileRoster roster = new ProfileRoster(builtins, customs);
    Optional<String> resolved = roster.resolveActiveId(activeId);
    if (activeId != null && !activeId.isBlank()) {
      Optional<String> kept = roster.findById(activeId)
        .filter(Entry::enabled)
        .map(Entry::id);
      if (kept.isEmpty() && resolved.isPresent()) {
        repaired = true;
      }
      if (kept.isEmpty() && resolved.isEmpty()) {
        repaired = true;
      }
    }
    return new SanitizeResult(roster, repaired, resolved);
  }

  private static BuiltinSeed findSeedByName(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    for (BuiltinSeed seed : BUILTIN_SEEDS) {
      if (namesMatch(name, seed)) {
        return seed;
      }
    }
    return null;
  }

  /** True when the raw name is already the canonical display name. */
  private static boolean canonicalName(String name, BuiltinSeed seed) {
    return name != null && name.trim().equals(seed.profile().name());
  }

  private static boolean namesMatch(String name, BuiltinSeed seed) {
    if (name == null || name.isBlank()) {
      return false;
    }
    String n = name.trim();
    if (n.equalsIgnoreCase(seed.id()) || n.equalsIgnoreCase(seed.profile().name())) {
      return true;
    }
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

  /** Trailing {@code (digits)} with a space before {@code (} — the format we emit. */
  private static final Pattern UNIQUE_SUFFIX = Pattern.compile("^(.*) \\((\\d+)\\)$");

  /**
   * First free {@code stem (1)}, {@code stem (2)}, … when {@code name} is taken.
   * Stem strips an existing {@code (n)} suffix with a space; {@code Ravager(1)}
   * (no space) is treated as a whole stem.
   */
  static String nextUniqueName(String name, Set<String> taken) {
    if (!taken.contains(name)) {
      return name;
    }
    String stem = uniqueNameStem(name);
    for (int n = 1; ; n++) {
      String candidate = stem + " (" + n + ")";
      if (!taken.contains(candidate)) {
        return candidate;
      }
    }
  }

  static String uniqueNameStem(String name) {
    Matcher m = UNIQUE_SUFFIX.matcher(name);
    if (m.matches()) {
      return m.group(1);
    }
    return name;
  }

  private static CustomSanitize sanitizeCustom(
    RawCustomRow raw, int index, String previousName
  ) {
    String name = raw.name() == null ? "" : raw.name();
    double width = raw.width();
    double height = raw.height();
    double reach = raw.reach();
    boolean repaired = false;

    String trimmed = name.stripTrailing();
    if (!trimmed.equals(name)) {
      name = trimmed;
      repaired = true;
    }

    if (name.isBlank()) {
      if (previousName != null && !previousName.isBlank()) {
        name = previousName;
      } else {
        name = FALLBACK_CUSTOM_NAME;
      }
      repaired = true;
    }

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
    if (width > MAX_CUSTOM_WIDTH) {
      width = MAX_CUSTOM_WIDTH;
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

  @Override
  public String toString() {
    return "ProfileRoster{builtins=" + builtins + ", customs=" + customs + '}';
  }
}
