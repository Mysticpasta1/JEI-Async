package mezz.jei.library.load;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import mezz.jei.api.IModPlugin;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks which plugins are incompatible with async execution, per phase.
 * A plugin that crashes during "Registering item subtypes" will only be
 * forced to main-thread for that specific phase, not for all phases.
 *
 * File format (v2):
 * <pre>
 * {
 *   "formatVersion": 2,
 *   "phaseIncompatible": {
 *     "Registering item subtypes": ["farmersdelight:jei_plugin", ...],
 *     "Registering recipes": ["create:jei_plugin", ...]
 *   }
 * }
 * </pre>
 *
 * Also reads legacy format (v1, a plain JSON array of plugin IDs)
 * and treats those plugins as incompatible for all phases.
 */
public class IncompatiblePluginStore {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int FORMAT_VERSION = 2;

	private final Path filePath;
	// phase -> set of incompatible plugin UIDs
	private final Map<String, Set<String>> phaseIncompatible;
	// plugins incompatible for ALL phases (from legacy format)
	private final Set<String> globalIncompatible;

	public IncompatiblePluginStore(Path configDir) {
		this.filePath = configDir.resolve("async_incompatible_plugins.json");
		this.phaseIncompatible = new HashMap<>();
		this.globalIncompatible = new HashSet<>();
		loadFromFile();
	}

	public boolean isIncompatible(IModPlugin plugin, String phase) {
		String uid = plugin.getPluginUid().toString();
		if (globalIncompatible.contains(uid)) {
			return true;
		}
		Set<String> phaseSet = phaseIncompatible.get(phase);
		return phaseSet != null && phaseSet.contains(uid);
	}

	public void markIncompatible(IModPlugin plugin, String phase) {
		String uid = plugin.getPluginUid().toString();
		Set<String> phaseSet = phaseIncompatible.computeIfAbsent(phase, k -> new HashSet<>());
		if (phaseSet.add(uid)) {
			LOGGER.warn("Marking plugin as async-incompatible for phase '{}': {}", phase, uid);
			saveToFile();
		}
	}

	private void loadFromFile() {
		if (!Files.exists(filePath)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(filePath)) {
			// Try v2 format first
			StoreFile storeFile = GSON.fromJson(reader, StoreFile.class);
			if (storeFile != null && storeFile.formatVersion == FORMAT_VERSION && storeFile.phaseIncompatible != null) {
				int total = 0;
				for (Map.Entry<String, Set<String>> entry : storeFile.phaseIncompatible.entrySet()) {
					phaseIncompatible.put(entry.getKey(), new HashSet<>(entry.getValue()));
					total += entry.getValue().size();
				}
				LOGGER.info("Loaded {} phase-specific async-incompatible plugin entries from {}", total, filePath);
				return;
			}
		} catch (IOException | com.google.gson.JsonSyntaxException e) {
			// Fall through to try legacy format
		}

		// Try legacy format (plain array of plugin IDs)
		try (Reader reader = Files.newBufferedReader(filePath)) {
			Type legacyType = new TypeToken<HashSet<String>>() {}.getType();
			Set<String> legacy = GSON.fromJson(reader, legacyType);
			if (legacy != null && !legacy.isEmpty()) {
				globalIncompatible.addAll(legacy);
				LOGGER.info("Migrated {} plugins from legacy incompatible list (will re-evaluate per phase)", legacy.size(), filePath);
				// Don't save yet - let the plugins re-crash per phase to build accurate data
			}
		} catch (IOException | com.google.gson.JsonSyntaxException e) {
			LOGGER.error("Failed to load incompatible plugins file: {}", filePath, e);
		}
	}

	private void saveToFile() {
		try {
			Files.createDirectories(filePath.getParent());
			StoreFile storeFile = new StoreFile();
			storeFile.formatVersion = FORMAT_VERSION;
			storeFile.phaseIncompatible = phaseIncompatible;
			try (Writer writer = Files.newBufferedWriter(filePath)) {
				GSON.toJson(storeFile, writer);
			}
		} catch (IOException e) {
			LOGGER.error("Failed to save incompatible plugins file: {}", filePath, e);
		}
	}

	private static class StoreFile {
		int formatVersion;
		Map<String, Set<String>> phaseIncompatible;
	}
}
