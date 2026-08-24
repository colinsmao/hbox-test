package dev.kelianmao.mobwalk.client.config;

import dev.kelianmao.mobwalk.client.overlay.WorldOverlayManager;
import dev.kelianmao.mobwalk.client.surface.CollisionSurfaceOverlay;
import dev.kelianmao.mobwalk.client.surface.EntityProfile;
import dev.kelianmao.mobwalk.client.surface.SurfaceSelection;

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

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigColor;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.config.options.table.ConfigTable;
import fi.dy.masa.malilib.config.options.table.Label;
import fi.dy.masa.malilib.config.options.table.TableRow;
import fi.dy.masa.malilib.config.options.table.type.BooleanEntry;
import fi.dy.masa.malilib.config.options.table.type.DoubleEntry;
import fi.dy.masa.malilib.config.options.table.type.EntryTypes;
import fi.dy.masa.malilib.config.options.table.type.LabelEntry;
import fi.dy.masa.malilib.config.options.table.type.StringEntry;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import fi.dy.masa.malilib.util.data.json.JsonUtils;

import dev.kelianmao.mobwalk.MobWalk;

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

  /** Cached roster rebuilt from builtin slim state + custom table. */
  private static ProfileRoster cachedRoster = ProfileRoster.defaults(false);
  /** Current wand item resolved from {@link Generic#WAND_ITEM}; refreshed on change/load. */
  private static Item cachedWandItem = Items.STICK;
  /** Guards re-entrant setTable while seeding a newly ADDed custom row. */
  private static boolean seedingCustomAdd;
  /**
   * Prior custom-table row identities. MaLiLib ADD inserts a dummy
   * <em>before</em> the clicked row (not append), so new rows are detected by
   * reference rather than by trailing index.
   */
  private static List<TableRow> lastCustomRows = List.of();

  /**
   * When standable surfaces are drawn. MaLiLib cycle values for Generic
   * {@code showSurfaces}. Display labels live in lang
   * {@code mobwalk.config.generic.showSurfaces.*}.
   */
  public enum ShowSurfaces implements IConfigOptionListEntry {
    NEVER("never"),
    WHILE_HOLDING_WAND("whileHoldingWand"),
    ALWAYS("always");

    private final String id;
    private final String translationKey;

    ShowSurfaces(String id) {
      this.id = id;
      this.translationKey = GENERIC_KEY + ".showSurfaces." + id;
    }

    @Override
    public String getStringValue() {
      return id;
    }

    @Override
    public String getDisplayName() {
      return StringUtils.translate(translationKey);
    }

    @Override
    public IConfigOptionListEntry cycle(boolean forward) {
      ShowSurfaces[] values = values();
      int i = ordinal();
      return values[forward
        ? (i + 1) % values.length
        : (i - 1 + values.length) % values.length];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
      if (value != null) {
        for (ShowSurfaces mode : values()) {
          if (mode.id.equalsIgnoreCase(value)) {
            return mode;
          }
        }
      }
      return WHILE_HOLDING_WAND;
    }
  }

  public static final class Generic {
    public static final ConfigOptionList SHOW_SURFACES =
      new ConfigOptionList("showSurfaces", ShowSurfaces.WHILE_HOLDING_WAND).apply(GENERIC_KEY);
    public static final ConfigString WAND_ITEM =
      new ConfigString("wandItem", WandItem.DEFAULT_ID).apply(GENERIC_KEY);
    public static final ConfigOptionList MOB_PROFILE =
      new ConfigOptionList("mobProfile", RosterProfileOption.player()).apply(GENERIC_KEY);
    /** Same instance as {@link Profiles#BUILTIN_PROFILES}; shown on General. */
    public static final ConfigTable BUILTIN_PROFILES = Profiles.BUILTIN_PROFILES;
    /** Same instance as {@link Profiles#CUSTOM_PROFILES}; shown on General. */
    public static final ConfigTable CUSTOM_PROFILES = Profiles.CUSTOM_PROFILES;
    public static final ConfigInteger FLOOD_RADIUS =
      new ConfigInteger("floodRadius", 20, 0, 30, true).apply(GENERIC_KEY);
    public static final ConfigBoolean SWIMMABLE_FLUIDS =
      new ConfigBoolean("swimmableFluids", true).apply(GENERIC_KEY);
    public static final ConfigDouble FLUID_ESCAPE_HEIGHT =
      new ConfigDouble("fluidEscapeHeight", 0.375, 0.0, 2.0, true).apply(GENERIC_KEY);

    /** GUI order (includes tables). File I/O uses {@link #FILE_OPTIONS}. */
    public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
      SHOW_SURFACES,
      WAND_ITEM,
      MOB_PROFILE,
      BUILTIN_PROFILES,
      CUSTOM_PROFILES,
      FLOOD_RADIUS,
      SWIMMABLE_FLUIDS,
      FLUID_ESCAPE_HEIGHT
    );

    /** Generic JSON category — profile tables live under Profiles. */
    static final ImmutableList<IConfigBase> FILE_OPTIONS = ImmutableList.of(
      SHOW_SURFACES,
      WAND_ITEM,
      MOB_PROFILE,
      FLOOD_RADIUS,
      SWIMMABLE_FLUIDS,
      FLUID_ESCAPE_HEIGHT
    );

    private Generic() {}
  }

  public static final class Profiles {
    private static final String BUILTIN_BUTTON_KEY = PROFILES_KEY + ".button.builtinProfiles";
    private static final String CUSTOM_BUTTON_KEY = PROFILES_KEY + ".button.customProfiles";
    private static final String[] COLUMN_LABEL_KEYS = {
      PROFILES_KEY + ".table.enabled",
      PROFILES_KEY + ".table.name",
      PROFILES_KEY + ".table.width",
      PROFILES_KEY + ".table.height",
      PROFILES_KEY + ".table.verticalReach"
    };

    public static final ConfigTable BUILTIN_PROFILES = buildBuiltinTable();
    public static final ConfigTable CUSTOM_PROFILES = buildCustomTable();

    /**
     * GUI / display-name refresh. Builtin enables+order are slim JSON; customs
     * use the full {@link ConfigTable} dump under the same Profiles category.
     */
    public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
      BUILTIN_PROFILES,
      CUSTOM_PROFILES
    );

    private Profiles() {}

    private static ConfigTable buildBuiltinTable() {
      List<TableRow> rows = defaultBuiltinRows(false);
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
        .setDisplayString(StringUtils.translate(BUILTIN_BUTTON_KEY))
        .setLabels(translatedColumnLabels())
        .setDefaultValue(rows.toArray(TableRow[]::new))
        .build()
        .apply(PROFILES_KEY);
    }

    static List<TableRow> defaultBuiltinRows(boolean showPointProfile) {
      List<ProfileRoster.BuiltinSeed> seeds = ProfileRoster.BUILTIN_SEEDS;
      int first = ProfileRoster.firstSeed(showPointProfile);
      List<TableRow> rows = new ArrayList<>(seeds.size() - first);
      for (int i = first; i < seeds.size(); i++) {
        rows.add(builtinRow(seeds.get(i)));
      }
      return rows;
    }

    private static ConfigTable buildCustomTable() {
      return new ConfigTable.Builder(
        "customProfiles",
        EntryTypes.BOOLEAN,
        EntryTypes.STRING,
        EntryTypes.DOUBLE,
        EntryTypes.DOUBLE,
        EntryTypes.DOUBLE
      )
        .setAllowAddNewEntry(true)
        .setShowEntryNumbers(false)
        .setDisplayString(StringUtils.translate(CUSTOM_BUTTON_KEY))
        .setLabels(translatedColumnLabels())
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

    static TableRow customRow(EntityProfile p, boolean enabled) {
      return CustomProfileTableRows.customRow(p, enabled);
    }
  }

  public static final class Appearance {
    public static final ConfigColor WALKABLE_COLOR =
      new ConfigColor("walkableColor", "#7F55AA55").apply(APPEARANCE_KEY);
    public static final ConfigBoolean SHOW_WATER_HAZARD =
      new ConfigBoolean("showWaterHazard", true).apply(APPEARANCE_KEY);
    public static final ConfigColor WATER_HAZARD_COLOR =
      new ConfigColor("waterHazardColor", "#7F3A9AE0").apply(APPEARANCE_KEY);
    public static final ConfigBoolean SHOW_LAVA_HAZARD =
      new ConfigBoolean("showLavaHazard", true).apply(APPEARANCE_KEY);
    public static final ConfigColor LAVA_HAZARD_COLOR =
      new ConfigColor("lavaHazardColor", "#7FE07020").apply(APPEARANCE_KEY);
    public static final ConfigBoolean SHOW_SOUL_SAND_HAZARD =
      new ConfigBoolean("showSoulSandHazard", true).apply(APPEARANCE_KEY);
    public static final ConfigColor SOUL_SAND_HAZARD_COLOR =
      new ConfigColor("soulSandHazardColor", "#7F8B5A2B").apply(APPEARANCE_KEY);
    public static final ConfigBoolean SHOW_MAGMA_HAZARD =
      new ConfigBoolean("showMagmaHazard", true).apply(APPEARANCE_KEY);
    public static final ConfigColor MAGMA_HAZARD_COLOR =
      new ConfigColor("magmaHazardColor", "#7FE0C028").apply(APPEARANCE_KEY);
    public static final ConfigBoolean DRAW_ON_VISIBLE_FACE =
      new ConfigBoolean("drawOnVisibleFace", true).apply(APPEARANCE_KEY);
    public static final ConfigBoolean SHOW_BEAMS_THROUGH_WALLS =
      new ConfigBoolean("showBeamsThroughWalls", true).apply(APPEARANCE_KEY);
    public static final ConfigBoolean SHOW_HOLE_BEAMS =
      new ConfigBoolean("showHoleBeams", true).apply(APPEARANCE_KEY);
    public static final ConfigColor HOLE_BEAM_COLOR =
      new ConfigColor("holeBeamColor", "#7FF2261A").apply(APPEARANCE_KEY);
    public static final ConfigDouble DOWN_SKIRT_HEIGHT =
      new ConfigDouble("downSkirtHeight", 2.0, 0.0, 4.0, true).apply(APPEARANCE_KEY);
    public static final ConfigDouble UPWARD_SKIRT_HEIGHT =
      new ConfigDouble("upwardSkirtHeight", 0.25, 0.0, 4.0, true).apply(APPEARANCE_KEY);

    public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
      WALKABLE_COLOR,
      SHOW_BEAMS_THROUGH_WALLS,
      SHOW_HOLE_BEAMS,
      HOLE_BEAM_COLOR,
      SHOW_WATER_HAZARD,
      WATER_HAZARD_COLOR,
      SHOW_LAVA_HAZARD,
      LAVA_HAZARD_COLOR,
      SHOW_SOUL_SAND_HAZARD,
      SOUL_SAND_HAZARD_COLOR,
      SHOW_MAGMA_HAZARD,
      MAGMA_HAZARD_COLOR,
      DOWN_SKIRT_HEIGHT,
      UPWARD_SKIRT_HEIGHT,
      DRAW_ON_VISIBLE_FACE
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
    public static final ConfigBoolean SHOW_POINT_PROFILE =
      new ConfigBoolean("showPointProfile", false).apply(DEBUG_KEY);

    public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
      CROUCH_SEE_THROUGH,
      CROUCH_SCROLL_RADIUS,
      CROUCH_CYCLE_PROFILE,
      SHADE_BY_DEPTH,
      SHOW_CUTOFF_RING,
      SHOW_POINT_PROFILE
    );

    private Debug() {}
  }

  public Configs() {}

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
    refreshProfileTableStrings();
  }

  /**
   * ConfigTable {@code displayString} / {@code labels} are final; update them
   * reflectively once language is available. Missing keys surface as the key
   * itself (no hardcoded fallback).
   */
  private static void refreshProfileTableStrings() {
    refreshOneTableStrings(
      Profiles.BUILTIN_PROFILES,
      StringUtils.translate(Profiles.BUILTIN_BUTTON_KEY)
    );
    refreshOneTableStrings(
      Profiles.CUSTOM_PROFILES,
      StringUtils.translate(Profiles.CUSTOM_BUTTON_KEY)
    );
  }

  private static void refreshOneTableStrings(ConfigTable table, String buttonLabel) {
    List<Label> columnLabels = new ArrayList<>(Profiles.COLUMN_LABEL_KEYS.length);
    for (Object label : Profiles.translatedColumnLabels()) {
      columnLabels.add(Label.of((String) label));
    }
    try {
      var displayField = ConfigTable.class.getDeclaredField("displayString");
      displayField.setAccessible(true);
      displayField.set(table, buttonLabel);

      var labelsField = ConfigTable.class.getDeclaredField("labels");
      labelsField.setAccessible(true);
      labelsField.set(table, List.copyOf(columnLabels));
    } catch (ReflectiveOperationException e) {
      MobWalk.LOGGER.warn(
        "Could not refresh {} table strings (button='{}')",
        table.getName(),
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
    Generic.WAND_ITEM.setValueChangeCallback(cfg -> refreshWandItem());
    Generic.FLOOD_RADIUS.setValueChangeCallback(cfg -> {
      CollisionSurfaceOverlay collision = WorldOverlayManager.collisionSurface();
      if (collision != null) {
        collision.reselectWithMobProfile();
      }
    });
    Generic.MOB_PROFILE.setValueChangeCallback(cfg -> {
      clampMobProfileToEnabled();
      CollisionSurfaceOverlay collision = WorldOverlayManager.collisionSurface();
      if (collision != null) {
        collision.reselectWithMobProfile();
      }
    });
    // drawOnVisibleFace gates the visible-top read compute-side (see
    // WorldGeometry.visibleTop), so flipping it must re-flood from the last seed.
    Appearance.DRAW_ON_VISIBLE_FACE.setValueChangeCallback(cfg -> {
      CollisionSurfaceOverlay collision = WorldOverlayManager.collisionSurface();
      if (collision != null) {
        collision.reselectWithMobProfile();
      }
    });
    Generic.SWIMMABLE_FLUIDS.setValueChangeCallback(cfg -> {
      CollisionSurfaceOverlay collision = WorldOverlayManager.collisionSurface();
      if (collision != null) {
        collision.reselectWithMobProfile();
      }
    });
    Generic.FLUID_ESCAPE_HEIGHT.setValueChangeCallback(cfg -> {
      CollisionSurfaceOverlay collision = WorldOverlayManager.collisionSurface();
      if (collision != null) {
        collision.reselectWithMobProfile();
      }
    });
    Profiles.BUILTIN_PROFILES.setValueChangeCallback(cfg -> onProfilesChanged());
    Profiles.CUSTOM_PROFILES.setValueChangeCallback(cfg -> onCustomProfilesChanged());
    Debug.SHOW_POINT_PROFILE.setValueChangeCallback(cfg -> onShowPointProfileChanged());
  }

  private static void refreshWandItem() {
    cachedWandItem = WandItem.resolve(Generic.WAND_ITEM.getStringValue());
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

  /** Snapshot custom-table row identities after load/sync/seed. */
  private static void rememberCustomRows() {
    lastCustomRows = List.copyOf(Profiles.CUSTOM_PROFILES.getTable());
  }

  /**
   * After ADD: replace newly inserted dummies by cloning the clicked row
   * (MaLiLib inserts <em>before</em> it), then swap so the clone sits below.
   * No source row (empty table / trailing dummy) → active profile On.
   */
  private static void onCustomProfilesChanged() {
    if (seedingCustomAdd) {
      return;
    }
    List<TableRow> table = Profiles.CUSTOM_PROFILES.getTable();
    if (table.size() > lastCustomRows.size()) {
      EntityProfile fallback = mobProfile().orElse(EntityProfile.PLAYER);
      seedingCustomAdd = true;
      try {
        CustomProfileTableRows.seedNewCustomRows(table, lastCustomRows, fallback);
      } finally {
        seedingCustomAdd = false;
      }
    }
    rememberCustomRows();
    onProfilesChanged();
  }

  private static void onShowPointProfileChanged() {
    refreshBuiltinTableDefaults();
    onProfilesChanged();
    // Hiding Point clamps mobProfile, whose widget caches its label and sits on the same
    // open screen as this toggle (a table popup instead re-inits the screen on close).
    if (GuiUtils.getCurrentScreen() instanceof GuiConfigs gui) {
      gui.refreshOptionWidgets();
    }
  }

  /**
   * Builtin RESET compares live rows to {@code defaultTable}. Point belongs in
   * that default only while Debug {@code showPointProfile} is on.
   */
  private static void refreshBuiltinTableDefaults() {
    List<TableRow> rows = Profiles.defaultBuiltinRows(showPointProfile());
    try {
      var defaultField = ConfigTable.class.getDeclaredField("defaultTable");
      defaultField.setAccessible(true);
      defaultField.set(Profiles.BUILTIN_PROFILES, ImmutableList.copyOf(rows));
    } catch (ReflectiveOperationException e) {
      MobWalk.LOGGER.warn("Could not refresh builtinProfiles default rows", e);
    }
  }

  private static void onProfilesChanged() {
    syncRosterFromTables();
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
   * Call after a profiles-table RESET confirm so roster / cycle match the tables.
   */
  static void syncAfterProfilesTableReset() {
    rememberCustomRows();
    onProfilesChanged();
  }

  /**
   * MaLiLib {@link ConfigTable#isModified()} compares defaults to {@code lastTable},
   * which popup edits leave stale. Compare live rows to defaults instead — use this
   * for any {@link ConfigTable} RESET enable state.
   */
  static boolean configTableIsModified(ConfigTable table) {
    return !table.getTable().equals(table.getDefaultTable());
  }

  /** Live roster (builtins + customs). */
  public static ProfileRoster roster() {
    return cachedRoster;
  }

  public static boolean hasEnabledProfile() {
    return cachedRoster.hasEnabledProfile();
  }

  /** When standable surfaces are drawn ({@link ShowSurfaces}). */
  public static ShowSurfaces showSurfaces() {
    Object value = Generic.SHOW_SURFACES.getOptionListValue();
    if (value instanceof ShowSurfaces mode) {
      return mode;
    }
    return ShowSurfaces.WHILE_HOLDING_WAND;
  }

  /** Current wand item (malformed/unknown ids → stick). Refreshed on change/load. */
  public static Item wandItem() {
    return cachedWandItem;
  }

  public static int floodRadius() {
    return Generic.FLOOD_RADIUS.getIntegerValue();
  }

  /**
   * Write the flood-radius option (shift+scroll). Clamps via MaLiLib; fires the
   * live-apply callback when the value changes. Disk flush is on disconnect /
   * config-screen close — not here.
   */
  public static void setFloodRadius(int radius) {
    Generic.FLOOD_RADIUS.setIntegerValue(radius);
  }

  /** Flush live options to {@code config/mobwalk.json}. */
  public static void saveToDisk() {
    saveToFile();
  }

  /**
   * Player-facing label for a roster id (stored profile name; sanitize keeps
   * custom names unique). Ids {@code custom0}/{@code custom1} already differ.
   */
  public static String profileDisplayLabel(String id) {
    return cachedRoster.displayLabel(id);
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

  public static boolean showWaterHazard() {
    return Appearance.SHOW_WATER_HAZARD.getBooleanValue();
  }

  public static Color4f waterHazardColor() {
    return Appearance.WATER_HAZARD_COLOR.getColor();
  }

  public static boolean showLavaHazard() {
    return Appearance.SHOW_LAVA_HAZARD.getBooleanValue();
  }

  public static Color4f lavaHazardColor() {
    return Appearance.LAVA_HAZARD_COLOR.getColor();
  }

  public static boolean showSoulSandHazard() {
    return Appearance.SHOW_SOUL_SAND_HAZARD.getBooleanValue();
  }

  public static Color4f soulSandHazardColor() {
    return Appearance.SOUL_SAND_HAZARD_COLOR.getColor();
  }

  public static boolean showMagmaHazard() {
    return Appearance.SHOW_MAGMA_HAZARD.getBooleanValue();
  }

  public static Color4f magmaHazardColor() {
    return Appearance.MAGMA_HAZARD_COLOR.getColor();
  }

  public static boolean drawOnVisibleFace() {
    return Appearance.DRAW_ON_VISIBLE_FACE.getBooleanValue();
  }

  /** Whether water/lava emit swim planes for {@link SurfaceSelection#select}. */
  public static boolean swimmableFluids() {
    return Generic.SWIMMABLE_FLUIDS.getBooleanValue();
  }

  /**
   * Rim height above the fluid block top that a fluid→solid climb may clear
   * (Generic {@code fluidEscapeHeight}; fed into
   * {@link dev.kelianmao.mobwalk.client.surface.ClimbRule}).
   */
  public static double fluidEscapeHeight() {
    return Generic.FLUID_ESCAPE_HEIGHT.getDoubleValue();
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

  public static double downSkirtHeight() {
    return Appearance.DOWN_SKIRT_HEIGHT.getDoubleValue();
  }

  public static double upwardSkirtHeight() {
    return Appearance.UPWARD_SKIRT_HEIGHT.getDoubleValue();
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

  public static boolean showPointProfile() {
    return Debug.SHOW_POINT_PROFILE.getBooleanValue();
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
      applyRoster(ProfileRoster.defaults(showPointProfile()));
      refreshWandItem();
      return;
    }
    JsonElement element = JsonUtils.parseJsonFile(configFile);
    if (element != null && element.isJsonObject()) {
      JsonObject root = element.getAsJsonObject();
      ConfigUtils.readConfigBase(root, "Generic", Generic.FILE_OPTIONS);
      ConfigUtils.readConfigBase(root, "Appearance", Appearance.OPTIONS);
      ConfigUtils.readConfigBase(root, "Debug", Debug.OPTIONS);
      refreshBuiltinTableDefaults();
      loadProfiles(root);
    } else {
      applyRoster(ProfileRoster.defaults(showPointProfile()));
    }
    refreshWandItem();
  }

  /**
   * Rebuild {@link #cachedRoster} from both profile tables; rewrite tables when
   * sanitize repaired (preserves user row order when clean).
   */
  private static void syncRosterFromTables() {
    List<ProfileRoster.RawBuiltinRow> rawBuiltins = readRawBuiltinsFromTable();
    List<ProfileRoster.RawCustomRow> rawCustoms = readRawCustomsFromTable();
    ProfileRoster.SanitizeResult result =
      ProfileRoster.sanitize(
        rawBuiltins, rawCustoms, activeProfileId(), cachedRoster.customs(),
        showPointProfile()
      );
    cachedRoster = result.roster();
    if (result.repaired()) {
      writeBuiltinTableFromRoster(cachedRoster);
      writeCustomTableFromRoster(cachedRoster);
    }
    rememberCustomRows();
    clampMobProfileToEnabled();
  }

  private static void applyRoster(ProfileRoster roster) {
    cachedRoster = roster;
    writeBuiltinTableFromRoster(roster);
    writeCustomTableFromRoster(roster);
    rememberCustomRows();
    clampMobProfileToEnabled();
  }

  private static void loadProfiles(JsonObject root) {
    if (!root.has("Profiles") || !root.get("Profiles").isJsonObject()) {
      applyRoster(ProfileRoster.defaults(showPointProfile()));
      return;
    }
    JsonObject profiles = root.getAsJsonObject("Profiles");
    if (profiles.has("customProfiles")) {
      Profiles.CUSTOM_PROFILES.setValueFromJsonElement(profiles.get("customProfiles"));
    } else {
      Profiles.CUSTOM_PROFILES.setTable(List.of());
    }
    List<ProfileRoster.RawBuiltinRow> rawBuiltins = parseSlimBuiltins(profiles);
    List<ProfileRoster.RawCustomRow> rawCustoms = readRawCustomsFromTable();
    ProfileRoster.SanitizeResult result =
      ProfileRoster.sanitize(
        rawBuiltins, rawCustoms, activeProfileId(), cachedRoster.customs(),
        showPointProfile()
      );
    applyRoster(result.roster());
  }

  private static List<ProfileRoster.RawBuiltinRow> parseSlimBuiltins(JsonObject profiles) {
    List<ProfileRoster.RawBuiltinRow> raw = new ArrayList<>();
    if (!profiles.has("builtinProfiles") || !profiles.get("builtinProfiles").isJsonArray()) {
      return raw;
    }
    for (JsonElement el : profiles.getAsJsonArray("builtinProfiles")) {
      if (!el.isJsonObject()) {
        continue;
      }
      JsonObject row = el.getAsJsonObject();
      if (!row.has("id") || !row.has("enabled")) {
        continue;
      }
      ProfileRoster.BuiltinSeed seed = seedById(row.get("id").getAsString());
      if (seed == null) {
        continue;
      }
      EntityProfile p = seed.profile();
      raw.add(new ProfileRoster.RawBuiltinRow(
        p.name(), p.width(), p.height(), p.reach(), row.get("enabled").getAsBoolean()
      ));
    }
    return raw;
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

  private static void writeProfilesCategory(JsonObject root) {
    JsonObject profiles = new JsonObject();
    JsonArray arr = new JsonArray();
    for (ProfileRoster.Entry entry : cachedRoster.builtins()) {
      JsonObject row = new JsonObject();
      row.addProperty("id", entry.id());
      row.addProperty("enabled", entry.enabled());
      arr.add(row);
    }
    profiles.add("builtinProfiles", arr);
    profiles.add("customProfiles", Profiles.CUSTOM_PROFILES.getAsJsonElement());
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

  private static List<ProfileRoster.RawCustomRow> readRawCustomsFromTable() {
    List<ProfileRoster.RawCustomRow> raw = new ArrayList<>();
    for (TableRow row : Profiles.CUSTOM_PROFILES.getTable()) {
      try {
        boolean enabled = Boolean.TRUE.equals(row.getBoolean(0));
        String name = row.getString(1);
        double width = row.getDouble(2);
        double height = row.getDouble(3);
        double reach = row.getDouble(4);
        raw.add(new ProfileRoster.RawCustomRow(name, width, height, reach, enabled));
      } catch (RuntimeException ignored) {
        // Sanitize repairs or drops corrupt rows.
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

  private static void writeCustomTableFromRoster(ProfileRoster roster) {
    List<TableRow> rows = new ArrayList<>(roster.customs().size());
    for (ProfileRoster.Entry entry : roster.customs()) {
      rows.add(Profiles.customRow(entry.profile(), entry.enabled()));
    }
    seedingCustomAdd = true;
    try {
      Profiles.CUSTOM_PROFILES.setTable(rows);
    } finally {
      seedingCustomAdd = false;
    }
    // MaLiLib GuiTableEdit keeps text fields; rebuild so clamps / name restore show.
    refreshOpenCustomProfilesEditor();
  }

  /**
   * After {@link #writeCustomTableFromRoster}, rebuild an open customs editor so
   * sanitized values (width cap, blank-name restore) appear without closing it.
   * Deferred to the next tick so we are outside MaLiLib's applyPending stack.
   */
  private static void refreshOpenCustomProfilesEditor() {
    Minecraft client = Minecraft.getInstance();
    if (client == null) {
      return;
    }
    client.execute(() -> {
      if (client.gui.screen() instanceof CustomProfilesTableEdit edit) {
        edit.initGui();
      }
    });
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
    syncRosterFromTables();
    JsonObject root = new JsonObject();
    ConfigUtils.writeConfigBase(root, "Generic", Generic.FILE_OPTIONS);
    writeProfilesCategory(root);
    ConfigUtils.writeConfigBase(root, "Appearance", Appearance.OPTIONS);
    ConfigUtils.writeConfigBase(root, "Debug", Debug.OPTIONS);
    root.add("config_version", new JsonPrimitive(CONFIG_VERSION));
    JsonUtils.writeJsonToFile(root, dir.resolve(CONFIG_FILE_NAME));
  }
}
