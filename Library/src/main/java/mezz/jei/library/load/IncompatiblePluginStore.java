package mezz.jei.library.load;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import mezz.jei.api.IModPlugin;
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
import java.util.concurrent.ConcurrentHashMap;

public class IncompatiblePluginStore {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int FORMAT_VERSION = 2;

	private final Path filePath;
	// Read on every plugin, in every phase, from every loading thread. Concurrent collections keep
	// those reads lock-free; the previous `synchronized` methods meant every thread queued behind
	// whichever one happened to be writing the file to disk.
	private final Map<String, Set<String>> phaseIncompatible;
	private final Set<String> globalIncompatible;
	private final Object saveLock = new Object();

	public IncompatiblePluginStore(Path configDir) {
		this.filePath = configDir.resolve("async_incompatible_plugins.json");
		this.phaseIncompatible = new ConcurrentHashMap<>();
		this.globalIncompatible = ConcurrentHashMap.newKeySet();
		loadFromFile();
	}

	public boolean isIncompatible(IModPlugin plugin, String phase) {
		Set<String> phaseSet = phaseIncompatible.get(phase);
		if (phaseSet == null && globalIncompatible.isEmpty()) {
			return false;
		}
		String uid = plugin.getPluginUid().toString();
		if (globalIncompatible.contains(uid)) {
			return true;
		}
		return phaseSet != null && phaseSet.contains(uid);
	}

	public void markIncompatible(IModPlugin plugin, String phase) {
		String uid = plugin.getPluginUid().toString();
		Set<String> phaseSet = phaseIncompatible.computeIfAbsent(phase, k -> ConcurrentHashMap.newKeySet());
		if (phaseSet.add(uid)) {
			LOGGER.warn("Marking plugin as async-incompatible for phase '{}': {}", phase, uid);
			// Outside any lock that readers use, so a slow disk cannot stall loading threads.
			saveToFile();
		}
	}

	private void loadFromFile() {
		if (!Files.exists(filePath)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(filePath)) {
			StoreFile storeFile = GSON.fromJson(reader, StoreFile.class);
			if (storeFile != null && storeFile.formatVersion == FORMAT_VERSION && storeFile.phaseIncompatible != null) {
				int total = 0;
				for (Map.Entry<String, Set<String>> entry : storeFile.phaseIncompatible.entrySet()) {
					Set<String> phaseSet = ConcurrentHashMap.newKeySet();
					phaseSet.addAll(entry.getValue());
					phaseIncompatible.put(entry.getKey(), phaseSet);
					total += entry.getValue().size();
				}
				LOGGER.info("Loaded {} phase-specific async-incompatible plugin entries from {}", total, filePath);
				return;
			}
		} catch (IOException | com.google.gson.JsonSyntaxException e) {
		}

		try (Reader reader = Files.newBufferedReader(filePath)) {
			Type legacyType = new TypeToken<HashSet<String>>() {}.getType();
			Set<String> legacy = GSON.fromJson(reader, legacyType);
			if (legacy != null && !legacy.isEmpty()) {
				globalIncompatible.addAll(legacy);
				LOGGER.info("Migrated {} plugins from legacy incompatible list (will re-evaluate per phase)", legacy.size(), filePath);
			}
		} catch (IOException | com.google.gson.JsonSyntaxException e) {
			LOGGER.error("Failed to load incompatible plugins file: {}", filePath, e);
		}
	}

	private void saveToFile() {
		StoreFile storeFile = new StoreFile();
		storeFile.formatVersion = FORMAT_VERSION;
		// Copy the map to avoid ConcurrentModificationException during GSON serialization
		storeFile.phaseIncompatible = new HashMap<>();
		for (Map.Entry<String, Set<String>> entry : phaseIncompatible.entrySet()) {
			storeFile.phaseIncompatible.put(entry.getKey(), new HashSet<>(entry.getValue()));
		}

		// Only serialise writers against each other, never against readers.
		synchronized (saveLock) {
			try {
				Files.createDirectories(filePath.getParent());
				try (Writer writer = Files.newBufferedWriter(filePath)) {
					GSON.toJson(storeFile, writer);
				}
			} catch (IOException e) {
				LOGGER.error("Failed to save incompatible plugins file: {}", filePath, e);
			}
		}
	}

	private static class StoreFile {
		int formatVersion;
		Map<String, Set<String>> phaseIncompatible;
	}
}
