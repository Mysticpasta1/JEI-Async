package mezz.jei.gui.search;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import mezz.jei.common.platform.Services;
import mezz.jei.core.QuantifiedIntegration.QuantifiedIntegration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.GZIPInputStream;
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
	private static final String QAPI_CACHE_NAME = "jei.search-string-cache.v1";
	private static final Duration QAPI_CACHE_TTL = Duration.ofDays(30);
	private static final long QAPI_CACHE_MAX_SIZE = 8L;
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
		Map<String, Map<String, List<String>>> cache = QuantifiedIntegration.getCached(
			QAPI_CACHE_NAME,
			cacheKey,
			this::loadFromLegacyDiskCache,
			QAPI_CACHE_TTL,
			QAPI_CACHE_MAX_SIZE,
			true
		);
		if (cache == null || cache.isEmpty()) {
			LOGGER.info("No search string cache found, will build from scratch");
			return false;
		}
		this.cachedData = cache;
		LOGGER.info("Loaded search string cache with {} ingredients", cachedData.size());
		return true;
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

		QuantifiedIntegration.runAsync("jei-search-cache-save", () -> {
			try {
				QuantifiedIntegration.putCached(QAPI_CACHE_NAME, cacheKey, dataToSave, QAPI_CACHE_TTL, QAPI_CACHE_MAX_SIZE, true);
				deleteMigratedCacheFile();
				LOGGER.info("Saved search string cache with {} ingredients", dataToSave.size());
			} catch (Exception e) {
				LOGGER.warn("Failed to save search string cache", e);
			}
		});
	}

	@Nullable
	private Map<String, Map<String, List<String>>> loadFromLegacyDiskCache() {
		if (!Files.exists(cacheFile)) {
			return null;
		}

		try (Reader reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(cacheFile)), StandardCharsets.UTF_8)) {
			CacheFile cache = GSON.fromJson(reader, CacheFile.class);
			if (cache == null || cache.formatVersion != FORMAT_VERSION) {
				LOGGER.info("Search string cache format version mismatch, rebuilding");
				deleteMigratedCacheFile();
				return null;
			}
			if (!cacheKey.equals(cache.cacheKey)) {
				LOGGER.info("Search string cache key mismatch (mods/config changed), rebuilding");
				return null;
			}
			if (cache.data == null || cache.data.isEmpty()) {
				LOGGER.info("Search string cache is empty, rebuilding");
				deleteMigratedCacheFile();
				return null;
			}
			deleteMigratedCacheFile();
			LOGGER.info("Migrated legacy search string cache with {} ingredients into Quantified cache", cache.data.size());
			return cache.data;
		} catch (Exception e) {
			LOGGER.warn("Failed to load legacy search string cache, rebuilding", e);
			deleteMigratedCacheFile();
			return null;
		}
	}

	private void deleteMigratedCacheFile() {
		try {
			Files.deleteIfExists(cacheFile);
		} catch (IOException ignored) {}
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
