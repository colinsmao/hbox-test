package dev.kelianmao.mobwalk.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigColor;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.table.ConfigTable;
import fi.dy.masa.malilib.config.options.table.Label;
import fi.dy.masa.malilib.config.options.table.TableRow;
import fi.dy.masa.malilib.config.options.table.type.BooleanEntry;
import fi.dy.masa.malilib.config.options.table.type.EntryTypes;
import fi.dy.masa.malilib.config.options.table.type.LabelEntry;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import fi.dy.masa.malilib.util.data.json.JsonUtils;

import dev.kelianmao.mobwalk.MobWalk;
import dev.kelianmao.mobwalk.client.widgets.CollisionSurfaceOverlay;

/**
 * MaLiLib-backed settings. Widgets write options immediately; JSON is written on
 * config-screen close via {@link IConfigHandler#save()}.
 */
public final class Configs implements IConfigHandler {
	private static final String CONFIG_FILE_NAME = MobWalk.MOD_ID + ".json";
	private static final String GENERIC_KEY = MobWalk.MOD_ID + ".config.generic";
	private static final String PROFILES_KEY = MobWalk.MOD_ID + ".config.profiles";
	private static final String APPEARANCE_KEY = MobWalk.MOD_ID + ".config.appearance";
	private static final String DEBUG_KEY = MobWalk.MOD_ID + ".config.debug";
	private static final int CONFIG_VERSION = 3;

	/** Cached roster rebuilt from the builtins table (customs empty until part 3). */
	private static ProfileRoster cachedRoster = ProfileRoster.defaults();

	public static final class Generic {
		public static final ConfigBoolean ENABLE_RENDERING =
			new ConfigBoolean("enableRendering", true).apply(GENERIC_KEY);
		public static final ConfigOptionList MOB_PROFILE =
			new ConfigOptionList("mobProfile", RosterProfileOption.player()).apply(GENERIC_KEY);
		/** Same instance as {@link Profiles#BUILTIN_PROFILES}; shown on General. */
		public static final ConfigTable BUILTIN_PROFILES = Profiles.BUILTIN_PROFILES;
		public static final ConfigInteger FLOOD_RADIUS =
			new ConfigInteger("floodRadius", 20, 0, 30, true).apply(GENERIC_KEY);

		/** GUI order (includes tables). File I/O uses {@link #FILE_OPTIONS}. */
		public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
			ENABLE_RENDERING,
			MOB_PROFILE,
			BUILTIN_PROFILES,
			FLOOD_RADIUS
		);

		/** Generic JSON category — table lives under Profiles. */
		static final ImmutableList<IConfigBase> FILE_OPTIONS = ImmutableList.of(
			ENABLE_RENDERING,
			MOB_PROFILE,
			FLOOD_RADIUS
		);

		private Generic() {}
	}

	public static final class Profiles {
		private static final String BUTTON_LABEL_KEY = PROFILES_KEY + ".button.builtinProfiles";
		private static final String[] COLUMN_LABEL_KEYS = {
			PROFILES_KEY + ".table.enabled",
			PROFILES_KEY + ".table.name",
			PROFILES_KEY + ".table.width",
			PROFILES_KEY + ".table.height",
			PROFILES_KEY + ".table.verticalReach"
		};

		public static final ConfigTable BUILTIN_PROFILES = buildBuiltinTable();

		/**
		 * GUI / display-name refresh only. Builtin enables+order are read/written
		 * as slim JSON (not via {@link ConfigUtils} / full {@link ConfigTable} dump).
		 */
		public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
			BUILTIN_PROFILES
		);

		private Profiles() {}

		private static ConfigTable buildBuiltinTable() {
			TableRow[] rows = new TableRow[ProfileRoster.BUILTIN_SEEDS.size()];
			for (int i = 0; i < ProfileRoster.BUILTIN_SEEDS.size(); i++) {
				rows[i] = builtinRow(ProfileRoster.BUILTIN_SEEDS.get(i));
			}
			return new ConfigTable.Builder(
				"builtinProfiles",
				EntryTypes.BOOLEAN,
				EntryTypes.LABEL,
				EntryTypes.LABEL,
				EntryTypes.LABEL,
				EntryTypes.LABEL
			)
				.setAllowAddNewEntry(false)
				.setShowEntryNumbers(false)
				// Baked once at class load; refreshDisplayNames() re-applies after language loads.
				.setDisplayString(StringUtils.translate(BUTTON_LABEL_KEY))
				.setLabels(translatedColumnLabels())
				.setDefaultValue(rows)
				.build()
				.apply(PROFILES_KEY);
		}

		/** Translate column header keys; missing keys surface as the key itself. */
		private static Object[] translatedColumnLabels() {
			Object[] labels = new Object[COLUMN_LABEL_KEYS.length];
			for (int i = 0; i < COLUMN_LABEL_KEYS.length; i++) {
				labels[i] = StringUtils.translate(COLUMN_LABEL_KEYS[i]);
			}
			return labels;
		}

		private static TableRow builtinRow(ProfileRoster.BuiltinSeed seed) {
			EntityProfile p = seed.profile();
			return TableRow.of(
				BooleanEntry.of(seed.defaultEnabled()),
				LabelEntry.of(p.name()),
				LabelEntry.of(formatDouble(p.width())),
				LabelEntry.of(formatDouble(p.height())),
				LabelEntry.of(formatDouble(p.reach()))
			);
		}
	}

	public static final class Appearance {
		public static final ConfigColor WALKABLE_COLOR =
			new ConfigColor("walkableColor", "#8066CC66").apply(APPEARANCE_KEY);
		public static final ConfigBoolean SHOW_BEAMS_THROUGH_WALLS =
			new ConfigBoolean("showBeamsThroughWalls", true).apply(APPEARANCE_KEY);
		public static final ConfigBoolean SHOW_HOLE_BEAMS =
			new ConfigBoolean("showHoleBeams", true).apply(APPEARANCE_KEY);
		public static final ConfigColor HOLE_BEAM_COLOR =
			new ConfigColor("holeBeamColor", "#80F2261A").apply(APPEARANCE_KEY);

		public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
			WALKABLE_COLOR,
			SHOW_BEAMS_THROUGH_WALLS,
			SHOW_HOLE_BEAMS,
			HOLE_BEAM_COLOR
		);

		private Appearance() {}
	}

	public static final class Debug {
		public static final ConfigBoolean CROUCH_SEE_THROUGH =
			new ConfigBoolean("crouchSeeThroughWalls", true).apply(DEBUG_KEY);
		public static final ConfigBoolean CROUCH_SCROLL_RADIUS =
			new ConfigBoolean("crouchScrollRadius", true).apply(DEBUG_KEY);
		public static final ConfigBoolean CROUCH_CYCLE_PROFILE =
			new ConfigBoolean("crouchCycleProfile", true).apply(DEBUG_KEY);
		public static final ConfigBoolean SHADE_BY_DEPTH =
			new ConfigBoolean("shadeByDepth", false).apply(DEBUG_KEY);
		public static final ConfigBoolean SHOW_CUTOFF_RING =
			new ConfigBoolean("showCutoffRing", true).apply(DEBUG_KEY);

		public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
			CROUCH_SEE_THROUGH,
			CROUCH_SCROLL_RADIUS,
			CROUCH_CYCLE_PROFILE,
			SHADE_BY_DEPTH,
			SHOW_CUTOFF_RING
		);

		private Debug() {}
	}

	Configs() {}

	private static String formatDouble(double value) {
		if (value == Math.rint(value) && !Double.isInfinite(value)) {
			return String.format(Locale.ROOT, "%.0f", value);
		}
		String s = String.format(Locale.ROOT, "%.4f", value);
		int end = s.length();
		while (end > 1 && s.charAt(end - 1) == '0') {
			end--;
		}
		if (end > 1 && s.charAt(end - 1) == '.') {
			end--;
		}
		return s.substring(0, end);
	}

	/**
	 * If {@code name.*} is untranslated, show the option id (last segment of the
	 * apply() key) instead of the raw translation key. Call once language is
	 * loaded (e.g. config screen open). Optional {@code name.*} entries still win.
	 * Also re-applies Profiles table button + column labels from lang keys (MaLiLib
	 * bakes those strings at construct time).
	 */
	public static void refreshDisplayNames() {
		fallbackNameToOptionId(Generic.OPTIONS, GENERIC_KEY);
		fallbackNameToOptionId(Profiles.OPTIONS, PROFILES_KEY);
		fallbackNameToOptionId(Appearance.OPTIONS, APPEARANCE_KEY);
		fallbackNameToOptionId(Debug.OPTIONS, DEBUG_KEY);
		refreshBuiltinProfilesTableStrings();
	}

	/**
	 * ConfigTable {@code displayString} / {@code labels} are final; update them
	 * reflectively once language is available. Missing keys surface as the key
	 * itself (no hardcoded fallback).
	 */
	private static void refreshBuiltinProfilesTableStrings() {
		String buttonLabel = StringUtils.translate(Profiles.BUTTON_LABEL_KEY);
		List<Label> columnLabels = new ArrayList<>(Profiles.COLUMN_LABEL_KEYS.length);
		for (Object label : Profiles.translatedColumnLabels()) {
			columnLabels.add(Label.of((String) label));
		}
		try {
			var displayField = ConfigTable.class.getDeclaredField("displayString");
			displayField.setAccessible(true);
			displayField.set(Profiles.BUILTIN_PROFILES, buttonLabel);

			var labelsField = ConfigTable.class.getDeclaredField("labels");
			labelsField.setAccessible(true);
			labelsField.set(Profiles.BUILTIN_PROFILES, List.copyOf(columnLabels));
		} catch (ReflectiveOperationException e) {
			MobWalk.LOGGER.warn(
				"Could not refresh builtinProfiles table strings (button='{}')",
				buttonLabel,
				e
			);
		}
	}

	private static void fallbackNameToOptionId(
		ImmutableList<IConfigBase> options, String prefix
	) {
		for (IConfigBase option : options) {
			String nameKey = prefix + ".name." + option.getCleanName();
			if (StringUtils.hasTranslation(nameKey)) {
				option.setTranslatedName(nameKey);
			} else {
				option.setTranslatedName(option.getName());
			}
		}
	}

	/** Wire live apply into the overlay (call once from init, before config load). */
	public static void initCallbacks() {
		Generic.FLOOD_RADIUS.setValueChangeCallback(cfg -> {
			CollisionSurfaceOverlay collision = WorldOverlayManager.collisionSurface();
			if (collision != null) {
				collision.applyFloodRadius(cfg.getIntegerValue());
			}
		});
		Generic.MOB_PROFILE.setValueChangeCallback(cfg -> {
			clampMobProfileToEnabled();
			CollisionSurfaceOverlay collision = WorldOverlayManager.collisionSurface();
			if (collision != null) {
				collision.reselectWithMobProfile();
			}
		});
		Profiles.BUILTIN_PROFILES.setValueChangeCallback(cfg -> onBuiltinProfilesChanged());
	}

	/**
	 * Snap mobProfile to an enabled roster id (e.g. after RESET to default Player
	 * while Player is disabled). Soft-disabled leaves the option value unchanged.
	 */
	private static void clampMobProfileToEnabled() {
		String id = activeProfileId();
		var resolved = cachedRoster.resolveActiveId(id);
		if (resolved.isEmpty()) {
			return;
		}
		String want = resolved.get();
		if (!want.equalsIgnoreCase(id)) {
			Generic.MOB_PROFILE.setOptionListValue(new RosterProfileOption(want));
		}
	}

	private static void onBuiltinProfilesChanged() {
		syncRosterFromTable(true);
		CollisionSurfaceOverlay collision = WorldOverlayManager.collisionSurface();
		if (collision == null) {
			return;
		}
		if (!hasEnabledProfile()) {
			collision.clearSelectionForSoftDisable();
		} else {
			collision.reselectWithMobProfile();
		}
	}

	/**
	 * MaLiLib {@code ConfigTable.resetToDefault()} does not fire the value-change
	 * callback (setTable skips it when the new rows equal the defaults argument).
	 * Call after "Edit Built-in Profiles" RESET confirm so roster / cycle match the table.
	 */
	static void syncAfterBuiltinProfilesReset() {
		onBuiltinProfilesChanged();
	}

	/**
	 * MaLiLib {@link ConfigTable#isModified()} compares defaults to {@code lastTable},
	 * which popup edits leave stale. Compare live rows to defaults instead — use this
	 * for any {@link ConfigTable} RESET enable state.
	 */
	static boolean configTableIsModified(ConfigTable table) {
		return !table.getTable().equals(table.getDefaultTable());
	}

	/** Live roster (builtins from the Profiles table; customs empty until part 3). */
	public static ProfileRoster roster() {
		return cachedRoster;
	}

	public static boolean hasEnabledProfile() {
		return cachedRoster.hasEnabledProfile();
	}

	public static boolean isRenderingEnabled() {
		return Generic.ENABLE_RENDERING.getBooleanValue();
	}

	/** @deprecated use {@link #isRenderingEnabled()} */
	@Deprecated
	public static boolean isOverlayEnabled() {
		return isRenderingEnabled();
	}

	public static int floodRadius() {
		return Generic.FLOOD_RADIUS.getIntegerValue();
	}

	/** Active mob profile when the roster has an enabled entry. */
	public static Optional<EntityProfile> mobProfile() {
		String id = activeProfileId();
		return cachedRoster.profileIfEnabled(id);
	}

	public static String activeProfileId() {
		Object value = Generic.MOB_PROFILE.getOptionListValue();
		if (value instanceof RosterProfileOption option) {
			return option.id();
		}
		return "player";
	}

	/**
	 * Advance the active profile among enabled roster entries. Empty when
	 * soft-disabled.
	 */
	public static Optional<EntityProfile> cycleMobProfile() {
		if (!hasEnabledProfile()) {
			return Optional.empty();
		}
		RosterProfileOption current = new RosterProfileOption(activeProfileId());
		RosterProfileOption next = (RosterProfileOption) current.cycle(true);
		Generic.MOB_PROFILE.setOptionListValue(next);
		return mobProfile();
	}

	public static Color4f walkableColor() {
		return Appearance.WALKABLE_COLOR.getColor();
	}

	public static boolean showBeamsThroughWalls() {
		return Appearance.SHOW_BEAMS_THROUGH_WALLS.getBooleanValue();
	}

	public static boolean showHoleBeams() {
		return Appearance.SHOW_HOLE_BEAMS.getBooleanValue();
	}

	public static Color4f holeBeamColor() {
		return Appearance.HOLE_BEAM_COLOR.getColor();
	}

	public static boolean crouchScrollRadius() {
		return Debug.CROUCH_SCROLL_RADIUS.getBooleanValue();
	}

	public static boolean crouchSeeThroughWalls() {
		return Debug.CROUCH_SEE_THROUGH.getBooleanValue();
	}

	public static boolean crouchCycleProfile() {
		return Debug.CROUCH_CYCLE_PROFILE.getBooleanValue();
	}

	public static boolean shadeByDepth() {
		return Debug.SHADE_BY_DEPTH.getBooleanValue();
	}

	public static boolean showCutoffRing() {
		return Debug.SHOW_CUTOFF_RING.getBooleanValue();
	}

	@Override
	public void load() {
		loadFromFile();
	}

	@Override
	public void save() {
		saveToFile();
	}

	private static void loadFromFile() {
		Path configFile = FileUtils.getConfigDirectory().resolve(CONFIG_FILE_NAME);
		if (!Files.isRegularFile(configFile)) {
			applyBuiltinRoster(ProfileRoster.defaults());
			return;
		}
		JsonElement element = JsonUtils.parseJsonFile(configFile);
		if (element != null && element.isJsonObject()) {
			JsonObject root = element.getAsJsonObject();
			ConfigUtils.readConfigBase(root, "Generic", Generic.FILE_OPTIONS);
			ConfigUtils.readConfigBase(root, "Appearance", Appearance.OPTIONS);
			ConfigUtils.readConfigBase(root, "Debug", Debug.OPTIONS);
			loadBuiltinProfilesSlim(root);
		} else {
			applyBuiltinRoster(ProfileRoster.defaults());
		}
	}

	/**
	 * Rebuild {@link #cachedRoster} from the builtins table; rewrite the table
	 * only when sanitize repaired geometry/row-set (preserves user row order).
	 */
	private static void syncRosterFromTable(boolean writeBackIfRepaired) {
		List<ProfileRoster.RawBuiltinRow> raw = readRawBuiltinsFromTable();
		String activeId = activeProfileId();
		ProfileRoster.SanitizeResult result = ProfileRoster.sanitize(raw, List.of(), activeId);
		cachedRoster = result.roster();
		if (result.repaired() && writeBackIfRepaired) {
			writeBuiltinTableFromRoster(cachedRoster);
		} else if (result.repaired()) {
			writeBuiltinTableFromRoster(cachedRoster);
		}
		clampMobProfileToEnabled();
	}

	private static void applyBuiltinRoster(ProfileRoster roster) {
		cachedRoster = roster;
		writeBuiltinTableFromRoster(roster);
		clampMobProfileToEnabled();
	}

	/**
	 * Load ordered {@code {id, enabled}} under {@code Profiles.builtinProfiles}.
	 * Geometry always comes from {@link ProfileRoster#BUILTIN_SEEDS}.
	 */
	private static void loadBuiltinProfilesSlim(JsonObject root) {
		if (!root.has("Profiles") || !root.get("Profiles").isJsonObject()) {
			applyBuiltinRoster(ProfileRoster.defaults());
			return;
		}
		JsonObject profiles = root.getAsJsonObject("Profiles");
		if (!profiles.has("builtinProfiles") || !profiles.get("builtinProfiles").isJsonArray()) {
			applyBuiltinRoster(ProfileRoster.defaults());
			return;
		}
		List<ProfileRoster.RawBuiltinRow> raw = new ArrayList<>();
		for (JsonElement el : profiles.getAsJsonArray("builtinProfiles")) {
			if (!el.isJsonObject()) {
				continue;
			}
			JsonObject row = el.getAsJsonObject();
			if (!row.has("id") || !row.has("enabled")) {
				continue;
			}
			String id = row.get("id").getAsString();
			boolean enabled = row.get("enabled").getAsBoolean();
			ProfileRoster.BuiltinSeed seed = seedById(id);
			if (seed == null) {
				continue;
			}
			EntityProfile p = seed.profile();
			raw.add(new ProfileRoster.RawBuiltinRow(
				p.name(), p.width(), p.height(), p.reach(), enabled
			));
		}
		ProfileRoster.SanitizeResult result =
			ProfileRoster.sanitize(raw, List.of(), activeProfileId());
		applyBuiltinRoster(result.roster());
	}

	private static ProfileRoster.BuiltinSeed seedById(String id) {
		if (id == null || id.isBlank()) {
			return null;
		}
		for (ProfileRoster.BuiltinSeed seed : ProfileRoster.BUILTIN_SEEDS) {
			if (seed.id().equalsIgnoreCase(id.trim())) {
				return seed;
			}
		}
		return null;
	}

	private static void writeBuiltinProfilesSlim(JsonObject root) {
		JsonObject profiles = new JsonObject();
		JsonArray arr = new JsonArray();
		for (ProfileRoster.Entry entry : cachedRoster.builtins()) {
			JsonObject row = new JsonObject();
			row.addProperty("id", entry.id());
			row.addProperty("enabled", entry.enabled());
			arr.add(row);
		}
		profiles.add("builtinProfiles", arr);
		root.add("Profiles", profiles);
	}

	private static List<ProfileRoster.RawBuiltinRow> readRawBuiltinsFromTable() {
		List<ProfileRoster.RawBuiltinRow> raw = new ArrayList<>();
		for (TableRow row : Profiles.BUILTIN_PROFILES.getTable()) {
			try {
				boolean enabled = Boolean.TRUE.equals(row.getBoolean(0));
				String name = row.getLabel(1).label();
				double width = Double.parseDouble(row.getLabel(2).label());
				double height = Double.parseDouble(row.getLabel(3).label());
				double reach = Double.parseDouble(row.getLabel(4).label());
				raw.add(new ProfileRoster.RawBuiltinRow(name, width, height, reach, enabled));
			} catch (RuntimeException ignored) {
				// Sanitize treats missing/corrupt rows by reseeding.
			}
		}
		return raw;
	}

	private static void writeBuiltinTableFromRoster(ProfileRoster roster) {
		List<TableRow> rows = new ArrayList<>(roster.builtins().size());
		for (ProfileRoster.Entry entry : roster.builtins()) {
			EntityProfile p = entry.profile();
			rows.add(TableRow.of(
				BooleanEntry.of(entry.enabled()),
				LabelEntry.of(p.name()),
				LabelEntry.of(formatDouble(p.width())),
				LabelEntry.of(formatDouble(p.height())),
				LabelEntry.of(formatDouble(p.reach()))
			));
		}
		Profiles.BUILTIN_PROFILES.setTable(rows);
	}

	private static void saveToFile() {
		Path dir = FileUtils.getConfigDirectory();
		if (!Files.exists(dir)) {
			FileUtils.createDirectoriesIfMissing(dir);
		}
		if (!Files.isDirectory(dir)) {
			MobWalk.LOGGER.error("Config folder '{}' is missing", dir.toAbsolutePath());
			return;
		}
		syncRosterFromTable(true);
		JsonObject root = new JsonObject();
		ConfigUtils.writeConfigBase(root, "Generic", Generic.FILE_OPTIONS);
		writeBuiltinProfilesSlim(root);
		ConfigUtils.writeConfigBase(root, "Appearance", Appearance.OPTIONS);
		ConfigUtils.writeConfigBase(root, "Debug", Debug.OPTIONS);
		root.add("config_version", new JsonPrimitive(CONFIG_VERSION));
		JsonUtils.writeJsonToFile(root, dir.resolve(CONFIG_FILE_NAME));
	}
}
