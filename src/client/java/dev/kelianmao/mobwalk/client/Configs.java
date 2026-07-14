package dev.kelianmao.mobwalk.client;

import java.nio.file.Files;
import java.nio.file.Path;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.util.FileUtils;
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
	private static final String DEBUG_KEY = MobWalk.MOD_ID + ".config.debug";
	private static final int CONFIG_VERSION = 1;

	public static final class Generic {
		public static final ConfigBoolean ENABLED =
			new ConfigBoolean("enabled", true).apply(GENERIC_KEY);
		public static final ConfigOptionList PROFILE =
			new ConfigOptionList("profile", EntityProfile.Option.PLAYER).apply(GENERIC_KEY);
		public static final ConfigInteger DEFAULT_RADIUS =
			new ConfigInteger("defaultRadius", 20, 0, 30, true).apply(GENERIC_KEY);

		public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
			ENABLED,
			PROFILE,
			DEFAULT_RADIUS
		);

		static {
			// GUI labels use name.*; point prettyName at the same key so toggle
			// messages don't need a duplicate lang entry.
			ENABLED.setPrettyName(GENERIC_KEY + ".name." + ENABLED.getCleanName());
			PROFILE.setPrettyName(GENERIC_KEY + ".name." + PROFILE.getCleanName());
			DEFAULT_RADIUS.setPrettyName(GENERIC_KEY + ".name." + DEFAULT_RADIUS.getCleanName());
		}

		private Generic() {}
	}

	public static final class Debug {
		public static final ConfigBoolean CROUCH_SCROLL_RADIUS =
			new ConfigBoolean("crouchScrollRadius", true).apply(DEBUG_KEY);
		public static final ConfigBoolean CROUCH_SEE_THROUGH =
			new ConfigBoolean("crouchSeeThroughWalls", true).apply(DEBUG_KEY);
		public static final ConfigBoolean CROUCH_CYCLE_PROFILE =
			new ConfigBoolean("crouchCycleProfile", true).apply(DEBUG_KEY);

		public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
			CROUCH_SCROLL_RADIUS,
			CROUCH_SEE_THROUGH,
			CROUCH_CYCLE_PROFILE
		);

		static {
			CROUCH_SCROLL_RADIUS.setPrettyName(
				DEBUG_KEY + ".name." + CROUCH_SCROLL_RADIUS.getCleanName());
			CROUCH_SEE_THROUGH.setPrettyName(
				DEBUG_KEY + ".name." + CROUCH_SEE_THROUGH.getCleanName());
			CROUCH_CYCLE_PROFILE.setPrettyName(
				DEBUG_KEY + ".name." + CROUCH_CYCLE_PROFILE.getCleanName());
		}

		private Debug() {}
	}

	Configs() {}

	/** Wire live apply into the overlay (call once from init, before config load). */
	public static void initCallbacks() {
		Generic.DEFAULT_RADIUS.setValueChangeCallback(cfg -> {
			CollisionSurfaceOverlay collision = WorldOverlayManager.collisionSurface();
			if (collision != null) {
				collision.applyDefaultRadius(cfg.getIntegerValue());
			}
		});
		Generic.PROFILE.setValueChangeCallback(cfg -> {
			CollisionSurfaceOverlay collision = WorldOverlayManager.collisionSurface();
			if (collision != null) {
				collision.reselectWithCurrentProfile();
			}
		});
	}

	public static boolean isOverlayEnabled() {
		return Generic.ENABLED.getBooleanValue();
	}

	public static int defaultRadius() {
		return Generic.DEFAULT_RADIUS.getIntegerValue();
	}

	/** Active entity profile (settings source of truth). */
	public static EntityProfile entityProfile() {
		return ((EntityProfile.Option) Generic.PROFILE.getOptionListValue()).profile();
	}

	/** Advance the profile option one step forward (Point → Player → Ravager → Point). */
	public static EntityProfile cycleEntityProfile() {
		EntityProfile.Option next =
			(EntityProfile.Option) Generic.PROFILE.getOptionListValue().cycle(true);
		Generic.PROFILE.setOptionListValue(next);
		return next.profile();
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
			return;
		}
		JsonElement element = JsonUtils.parseJsonFile(configFile);
		if (element != null && element.isJsonObject()) {
			JsonObject root = element.getAsJsonObject();
			ConfigUtils.readConfigBase(root, "Generic", Generic.OPTIONS);
			ConfigUtils.readConfigBase(root, "Debug", Debug.OPTIONS);
		}
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
		JsonObject root = new JsonObject();
		ConfigUtils.writeConfigBase(root, "Generic", Generic.OPTIONS);
		ConfigUtils.writeConfigBase(root, "Debug", Debug.OPTIONS);
		root.add("config_version", new JsonPrimitive(CONFIG_VERSION));
		JsonUtils.writeJsonToFile(root, dir.resolve(CONFIG_FILE_NAME));
	}
}
