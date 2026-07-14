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
	private static final int CONFIG_VERSION = 1;

	public static final class Generic {
		public static final ConfigBoolean ENABLED =
			new ConfigBoolean("enabled", true).apply(GENERIC_KEY);
		public static final ConfigInteger DEFAULT_RADIUS =
			new ConfigInteger("defaultRadius", 20, 0, 30, true).apply(GENERIC_KEY);

		public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
			ENABLED,
			DEFAULT_RADIUS
		);

		static {
			// GUI labels use name.*; point prettyName at the same key so toggle
			// messages don't need a duplicate lang entry.
			ENABLED.setPrettyName(GENERIC_KEY + ".name." + ENABLED.getCleanName());
			DEFAULT_RADIUS.setPrettyName(GENERIC_KEY + ".name." + DEFAULT_RADIUS.getCleanName());
		}

		private Generic() {}
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
	}

	public static boolean isOverlayEnabled() {
		return Generic.ENABLED.getBooleanValue();
	}

	public static int defaultRadius() {
		return Generic.DEFAULT_RADIUS.getIntegerValue();
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
			ConfigUtils.readConfigBase(element.getAsJsonObject(), "Generic", Generic.OPTIONS);
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
		root.add("config_version", new JsonPrimitive(CONFIG_VERSION));
		JsonUtils.writeJsonToFile(root, dir.resolve(CONFIG_FILE_NAME));
	}
}
