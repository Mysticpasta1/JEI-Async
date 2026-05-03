package mezz.jei.gui.search;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import mezz.jei.common.platform.Services;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SearchStringCache {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String CACHE_FILE_NAME = "search_string_cache.json.gz";
	private static final String LEGACY_CACHE_FILE_NAME = "search_string_cache.json";
	private static final int FORMAT_VERSION = 1;
	private static final Gson GSON = new GsonBuilder().create();
	private static final Type DATA_TYPE = new TypeToken<Map<String, Map<String, List<String>>>>() {}.getType();

	private final Path cacheFile;
	private final String cacheKey;

	@Nullable
	private Map<String, Map<String, List<String>>> cachedData;
	@Nullable
	private Map<String, Map<String, List<String>>> collectedData;

	public SearchStringCache(String cacheKey) {
		Path configDir = Services.PLATFORM.getConfigHelper().createJeiConfigDir();
		this.cacheFile = configDir.resolve(CACHE_FILE_NAME);
		this.cacheKey = cacheKey;
		deleteLegacyCacheFile(configDir);
	}

	private static void deleteLegacyCacheFile(Path configDir) {
		try {
			Files.deleteIfExists(configDir.resolve(LEGACY_CACHE_FILE_NAME));
		} catch (IOException ignored) {}
	}

	public boolean load() {
		if (!Files.exists(cacheFile)) {
			LOGGER.info("No search string cache found, will build from scratch");
			return false;
		}

		try (Reader reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(cacheFile)), StandardCharsets.UTF_8)) {
			CacheFile cache = GSON.fromJson(reader, CacheFile.class);
			if (cache == null || cache.formatVersion != FORMAT_VERSION) {
				LOGGER.info("Search string cache format version mismatch, rebuilding");
				return false;
			}
			if (!cacheKey.equals(cache.cacheKey)) {
				LOGGER.info("Search string cache key mismatch (mods/config changed), rebuilding");
				return false;
			}
			if (cache.data == null || cache.data.isEmpty()) {
				LOGGER.info("Search string cache is empty, rebuilding");
				return false;
			}
			this.cachedData = cache.data;
			LOGGER.info("Loaded search string cache with {} ingredients", cachedData.size());
			return true;
		} catch (Exception e) {
			LOGGER.warn("Failed to load search string cache, rebuilding", e);
			try {
				Files.deleteIfExists(cacheFile);
			} catch (IOException ignored) {}
			return false;
		}
	}

	@Nullable
	public List<String> getCachedStrings(String ingredientUid, char prefixChar) {
		if (cachedData == null) {
			return null;
		}
		Map<String, List<String>> prefixMap = cachedData.get(ingredientUid);
		if (prefixMap == null) {
			return null;
		}
		return prefixMap.get(String.valueOf(prefixChar));
	}

	public boolean isCacheAvailable() {
		return cachedData != null;
	}

	public void startCollecting() {
		this.collectedData = new HashMap<>();
	}

	public void recordStrings(String ingredientUid, char prefixChar, Collection<String> strings) {
		if (collectedData == null) {
			return;
		}
		Map<String, List<String>> prefixMap = collectedData.computeIfAbsent(ingredientUid, k -> new HashMap<>());
		prefixMap.put(String.valueOf(prefixChar), List.copyOf(strings));
	}

	public void saveAsync() {
		if (collectedData == null || collectedData.isEmpty()) {
			return;
		}

		Map<String, Map<String, List<String>>> dataToSave = collectedData;
		this.collectedData = null;

		CompletableFuture.runAsync(() -> {
			CacheFile cache = new CacheFile();
			cache.formatVersion = FORMAT_VERSION;
			cache.cacheKey = cacheKey;
			cache.data = dataToSave;

			try {
				Path parent = cacheFile.getParent();
				if (parent != null) {
					Files.createDirectories(parent);
				}
				try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(cacheFile)), StandardCharsets.UTF_8)) {
					GSON.toJson(cache, writer);
				}
				LOGGER.info("Saved search string cache with {} ingredients", dataToSave.size());
			} catch (Exception e) {
				LOGGER.warn("Failed to save search string cache", e);
			}
		});
	}

	public static String computeCacheKey(Collection<String> ingredientUids, String locale) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			List<String> sorted = new ArrayList<>(ingredientUids);
			sorted.sort(String::compareTo);
			for (String uid : sorted) {
				digest.update(uid.getBytes(StandardCharsets.UTF_8));
				digest.update((byte) '\n');
			}
			digest.update(locale.getBytes(StandardCharsets.UTF_8));
			byte[] hash = digest.digest();
			return HexFormat.of().formatHex(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-256 not available", e);
		}
	}

	private static class CacheFile {
		int formatVersion;
		String cacheKey;
		Map<String, Map<String, List<String>>> data;
	}
}
