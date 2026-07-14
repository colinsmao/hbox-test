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
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.data.json.JsonUtils;

import dev.kelianmao.mobwalk.MobWalk;

/**
 * MaLiLib config handler scaffold. Generic options land in Step 2; load/save of
 * {@code config/mobwalk.json} is already wired for screen-close persistence.
 */
public final class Configs implements IConfigHandler {
	private static final String CONFIG_FILE_NAME = MobWalk.MOD_ID + ".json";
	private static final int CONFIG_VERSION = 1;

	public static final class Generic {
		public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of();

		private Generic() {}
	}

	Configs() {}

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
