package mezz.jei.gui.ingredients;

import mezz.jei.common.config.IngredientSortStage;
import mezz.jei.core.QuantifiedIntegration.QuantifiedIntegration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public class IngredientSortCache {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String QAPI_CACHE_NAME = "jei.ingredient-sort-cache.v1";
	private static final Duration QAPI_CACHE_TTL = Duration.ofDays(30);
	private static final long QAPI_CACHE_MAX_SIZE = 4L;

	private final String cacheKey;
	@Nullable
	private List<Integer> cachedSortedCreatedIndexes;

	public IngredientSortCache(String cacheKey) {
		this.cacheKey = cacheKey;
	}

	public boolean load() {
		List<Integer> cached = QuantifiedIntegration.getCached(
			QAPI_CACHE_NAME,
			cacheKey,
			() -> null,
			QAPI_CACHE_TTL,
			QAPI_CACHE_MAX_SIZE,
			true
		);
		if (cached == null || cached.isEmpty()) {
			LOGGER.info("No ingredient sort cache found, will rebuild sort order");
			return false;
		}
		this.cachedSortedCreatedIndexes = List.copyOf(cached);
		LOGGER.info("Loaded ingredient sort cache with {} entries", cached.size());
		return true;
	}

	public boolean apply(List<IListElementInfo<?>> ingredients) {
		List<Integer> cached = this.cachedSortedCreatedIndexes;
		if (cached == null || cached.size() != ingredients.size()) {
			return false;
		}

		Map<Integer, Integer> positions = new HashMap<>(cached.size());
		for (int i = 0; i < cached.size(); i++) {
			Integer previous = positions.put(cached.get(i), i);
			if (previous != null) {
				LOGGER.info("Ingredient sort cache had duplicate created indexes, rebuilding");
				return false;
			}
		}

		for (IListElementInfo<?> ingredient : ingredients) {
			Integer sortedIndex = positions.get(ingredient.getCreatedIndex());
			if (sortedIndex == null) {
				LOGGER.info("Ingredient sort cache did not match current ingredients, rebuilding");
				return false;
			}
			ingredient.getElement().setSortedIndex(sortedIndex);
		}

		ingredients.sort(Comparator.comparingInt(info -> info.getElement().getSortedIndex()));
		LOGGER.info("Applied cached ingredient sort order for {} ingredients", ingredients.size());
		return true;
	}

	public void saveAsync(List<IListElementInfo<?>> ingredients) {
		List<Integer> sortedCreatedIndexes = ingredients.stream()
			.map(IListElementInfo::getCreatedIndex)
			.toList();

		QuantifiedIntegration.runAsync("jei-ingredient-sort-cache-save", () -> {
			try {
				QuantifiedIntegration.putCached(QAPI_CACHE_NAME, cacheKey, sortedCreatedIndexes, QAPI_CACHE_TTL, QAPI_CACHE_MAX_SIZE, true);
				LOGGER.info("Saved ingredient sort cache with {} entries", sortedCreatedIndexes.size());
			} catch (Exception e) {
				LOGGER.warn("Failed to save ingredient sort cache", e);
			}
		});
	}

	public static String computeCacheKey(
		Collection<IListElementInfo<?>> ingredients,
		String locale,
		List<IngredientSortStage> ingredientSorterStages,
		List<Path> configPaths
	) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (IListElementInfo<?> ingredient : ingredients) {
				updateDigest(digest, ingredient.getResourceLocation().toString());
			}
			updateDigest(digest, locale);
			for (IngredientSortStage ingredientSortStage : ingredientSorterStages) {
				updateDigest(digest, ingredientSortStage.name());
			}
			for (Path configPath : configPaths) {
				updateDigest(digest, configPath.getFileName().toString());
				if (Files.exists(configPath)) {
					digest.update(Files.readAllBytes(configPath));
				}
				digest.update((byte) '\n');
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-256 not available", e);
		} catch (IOException e) {
			throw new RuntimeException("Failed to read ingredient sort cache config", e);
		}
	}

	private static void updateDigest(MessageDigest digest, String value) {
		digest.update(value.getBytes(StandardCharsets.UTF_8));
		digest.update((byte) '\n');
	}
}