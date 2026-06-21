package mezz.jei.gui.search;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.core.search.CombinedSearchables;
import mezz.jei.core.search.ISearchStorage;
import mezz.jei.core.search.ISearchable;
import mezz.jei.core.search.PrefixInfo;
import mezz.jei.core.search.PrefixedSearchable;
import mezz.jei.core.search.SearchMode;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ElementSearch implements IElementSearch {
	private static final Logger LOGGER = LogManager.getLogger();

	private final Map<PrefixInfo<IListElementInfo<?>, IListElement<?>>, PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> prefixedSearchables = new IdentityHashMap<>();
	private final CombinedSearchables<IListElement<?>> combinedSearchables = new CombinedSearchables<>();
	private final Map<Object, IListElement<?>> allElements = new HashMap<>();
	private final Object deferredLock = new Object();
	private final List<IListElementInfo<?>> deferredInfos = new ArrayList<>();
	private @Nullable PrefixedSearchable<IListElementInfo<?>, IListElement<?>> tagSearchable;
	private @Nullable PrefixedSearchable<IListElementInfo<?>, IListElement<?>> tooltipSearchable;

	public ElementSearch(ElementPrefixParser elementPrefixParser) {
		for (PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo : elementPrefixParser.allPrefixInfos()) {
			ISearchStorage<IListElement<?>> storage = prefixInfo.createStorage();
			var prefixedSearchable = new PrefixedSearchable<>(storage, prefixInfo);
			this.prefixedSearchables.put(prefixInfo, prefixedSearchable);
			this.combinedSearchables.addSearchable(prefixedSearchable);
		}
		this.tagSearchable = this.prefixedSearchables.entrySet().stream()
			.filter(e -> e.getKey().getPrefix() == '#')
			.findFirst()
			.map(Map.Entry::getValue)
			.orElse(null);
		this.tooltipSearchable = this.prefixedSearchables.entrySet().stream()
			.filter(e -> e.getKey().getPrefix() == '$')
			.findFirst()
			.map(Map.Entry::getValue)
			.orElse(null);
	}

	@Override
	public Set<IListElement<?>> getSearchResults(ElementPrefixParser.TokenInfo tokenInfo) {
		String token = tokenInfo.token();
		if (token.isEmpty()) {
			return Set.of();
		}

		Set<IListElement<?>> results = Collections.newSetFromMap(new IdentityHashMap<>());

		PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo = tokenInfo.prefixInfo();
		if (prefixInfo == ElementPrefixParser.NO_PREFIX) {
			combinedSearchables.getSearchResults(token, results::addAll);
			return results;
		}
		final ISearchable<IListElement<?>> searchable = this.prefixedSearchables.get(prefixInfo);
		if (searchable == null || searchable.getMode() == SearchMode.DISABLED) {
			combinedSearchables.getSearchResults(token, results::addAll);
			return results;
		}
		searchable.getSearchResults(token, results::addAll);
		return results;
	}

	@Override
	public <T> void add(IListElementInfo<T> info, IIngredientManager ingredientManager) {
		IListElement<T> element = info.getElement();
		Object uid = getUid(element.getTypedIngredient(), ingredientManager);
		this.allElements.put(uid, element);

		for (PrefixedSearchable<IListElementInfo<?>, IListElement<?>> prefixedSearchable : this.prefixedSearchables.values()) {
			SearchMode searchMode = prefixedSearchable.getMode();
			if (searchMode != SearchMode.DISABLED) {
				Collection<String> strings = prefixedSearchable.getStrings(info);
				ISearchStorage<IListElement<?>> storage = prefixedSearchable.getSearchStorage();
				for (String string : strings) {
					storage.put(string, element);
				}
			}
		}
	}

	private static <T> Object getUid(ITypedIngredient<T> typedIngredient, IIngredientManager ingredientManager) {
		IIngredientHelper<T> ingredientHelper = ingredientManager.getIngredientHelper(typedIngredient.getType());
		return ingredientHelper.getUid(typedIngredient.getIngredient(), UidContext.Ingredient);
	}

	@Override
	public void addAll(Collection<IListElementInfo<?>> infos, IIngredientManager ingredientManager) {
		addAll(infos, ingredientManager, null);
	}

	public void addAll(Collection<IListElementInfo<?>> infos, IIngredientManager ingredientManager, @Nullable SearchStringCache cache) {
		if (isBackgroundThread()) {
			addAllBackground(infos, ingredientManager);
			return;
		}
		// Build UID map for runtime identity
		for (IListElementInfo<?> info : infos) {
			IListElement<?> element = info.getElement();
			Object uid = getUid(info.getTypedIngredient(), ingredientManager);
			this.allElements.put(uid, element);
		}

		boolean useCache = cache != null && cache.isCacheAvailable();
		boolean collectForCache = cache != null && !cache.isCacheAvailable();
		if (collectForCache) {
			cache.startCollecting();
		}

		for (Map.Entry<PrefixInfo<IListElementInfo<?>, IListElement<?>>, PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> entry : this.prefixedSearchables.entrySet()) {
			PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo = entry.getKey();
			PrefixedSearchable<IListElementInfo<?>, IListElement<?>> prefixedSearchable = entry.getValue();
			SearchMode searchMode = prefixedSearchable.getMode();
			if (searchMode == SearchMode.DISABLED) {
				continue;
			}

			char prefix = prefixInfo.getPrefix();
			ISearchStorage<IListElement<?>> storage = prefixedSearchable.getSearchStorage();
			int cacheHits = 0;
			int cacheMisses = 0;

			// Use list index as cache key - stable across JVM runs
			// (cache key hash already validates the ingredient list is identical)
			int index = 0;
			for (IListElementInfo<?> info : infos) {
				String cacheId = String.valueOf(index++);
				Collection<String> strings;

				if (useCache) {
					List<String> cached = cache.getCachedStrings(cacheId, prefix);
					if (cached != null) {
						strings = cached;
						cacheHits++;
					} else {
						strings = prefixedSearchable.getStrings(info);
						cacheMisses++;
					}
				} else {
					strings = prefixedSearchable.getStrings(info);
				}

				if (collectForCache) {
					cache.recordStrings(cacheId, prefix, strings);
				}

				for (String string : strings) {
					storage.put(string, info.getElement());
				}
			}

			if (useCache) {
				LOGGER.info("ElementSearch {}: cache hits={}, misses={}", prefixInfo, cacheHits, cacheMisses);
			}
		}

		if (collectForCache) {
			cache.saveAsync();
		}
	}

	private void addAllBackground(Collection<IListElementInfo<?>> infos, IIngredientManager ingredientManager) {
		infos.parallelStream().forEach(info -> {
			Object uid = getUid(info.getTypedIngredient(), ingredientManager);
			this.allElements.put(uid, info.getElement());
		});

		for (Map.Entry<PrefixInfo<IListElementInfo<?>, IListElement<?>>, PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> entry : this.prefixedSearchables.entrySet()) {
			PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo = entry.getKey();
			PrefixedSearchable<IListElementInfo<?>, IListElement<?>> prefixedSearchable = entry.getValue();
			if (prefixedSearchable.getMode() == SearchMode.DISABLED) {
				continue;
			}
			char prefix = prefixInfo.getPrefix();
			if (prefix == '#' || prefix == '$') {
				continue;
			}
			ISearchStorage<IListElement<?>> storage = prefixedSearchable.getSearchStorage();
			for (IListElementInfo<?> info : infos) {
				prefixedSearchable.getStrings(info).forEach(s -> storage.put(s, info.getElement()));
			}
		}

		synchronized (this.deferredLock) {
			this.deferredInfos.addAll(infos);
		}
	}

	@Override
	public void processDeferredTooltips() {
		List<IListElementInfo<?>> infos;
		synchronized (this.deferredLock) {
			if (this.deferredInfos.isEmpty()) {
				return;
			}
			infos = new ArrayList<>(this.deferredInfos);
			this.deferredInfos.clear();
		}

		if (isBackgroundThread()) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null) {
				mc.execute(this::processDeferredTooltips);
			}
			return;
		}

		if (this.tagSearchable != null && this.tagSearchable.getMode() != SearchMode.DISABLED) {
			ISearchStorage<IListElement<?>> storage = this.tagSearchable.getSearchStorage();
			for (IListElementInfo<?> info : infos) {
				try {
					this.tagSearchable.getStrings(info).forEach(s -> storage.put(s, info.getElement()));
				} catch (Exception e) {
					LOGGER.debug("Failed to process deferred tag search strings for ingredient", e);
				}
			}
		}

		if (this.tooltipSearchable != null && this.tooltipSearchable.getMode() != SearchMode.DISABLED) {
			ISearchStorage<IListElement<?>> storage = this.tooltipSearchable.getSearchStorage();
			for (IListElementInfo<?> info : infos) {
				try {
					this.tooltipSearchable.getStrings(info).forEach(s -> storage.put(s, info.getElement()));
				} catch (Exception e) {
					LOGGER.debug("Failed to process deferred tooltip search strings for ingredient", e);
				}
			}
		}
	}

	@Override
	public @Nullable <T> IListElement<T> findElement(ITypedIngredient<T> ingredient, IIngredientHelper<T> ingredientHelper) {
		Object ingredientUid = ingredientHelper.getUid(ingredient.getIngredient(), UidContext.Ingredient);
		IListElement<?> listElement = allElements.get(ingredientUid);
		if (listElement != null && listElement.getTypedIngredient().getType().equals(ingredient.getType())) {
			@SuppressWarnings("unchecked")
			IListElement<T> cast = (IListElement<T>) listElement;
			return cast;
		}
		return null;
	}

	@Override
	public Collection<IListElement<?>> getAllIngredients() {
		return Collections.unmodifiableCollection(allElements.values());
	}

	@Override
	public void clear() {
		this.allElements.clear();
		this.combinedSearchables.clear();
		this.prefixedSearchables.clear();
		synchronized (this.deferredLock) {
			this.deferredInfos.clear();
		}
		this.tagSearchable = null;
		this.tooltipSearchable = null;
	}

	@Override
	public void logStatistics() {
		this.prefixedSearchables.forEach((prefixInfo, value) -> {
			if (prefixInfo.getMode() != SearchMode.DISABLED) {
				ISearchStorage<IListElement<?>> storage = value.getSearchStorage();
				LOGGER.info("ElementSearch {} Storage Stats: {}", prefixInfo, storage.statistics());
			}
		});
	}

	private static boolean isBackgroundThread() {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null) {
				return false;
			}
			return !mc.isSameThread();
		} catch (Exception e) {
			return false;
		}
	}
}
