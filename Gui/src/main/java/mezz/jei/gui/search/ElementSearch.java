package mezz.jei.gui.search;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.config.DebugConfig;
import mezz.jei.core.search.*;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ElementSearch implements IElementSearch {
	private static final Logger LOGGER = LogManager.getLogger();

	private final Map<PrefixInfo<IListElementInfo<?>, IListElement<?>>, PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> prefixedSearchables = new IdentityHashMap<>();
	private final CombinedSearchables<IListElement<?>> combinedSearchables = new CombinedSearchables<>();
	private final Map<Object, IListElement<?>> allElements = new ConcurrentHashMap<>();

	private final Object tooltipLock = new Object();
	private final List<IListElementInfo<?>> deferredTooltipInfos = new ArrayList<>();
	@Nullable
	private PrefixedSearchable<IListElementInfo<?>, IListElement<?>> tooltipSearchable = null;

	public ElementSearch(ElementPrefixParser elementPrefixParser) {
		for (PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo : elementPrefixParser.allPrefixInfos()) {
			ISearchStorage<IListElement<?>> storage = prefixInfo.createStorage();
			var prefixedSearchable = new PrefixedSearchable<>(storage, prefixInfo);
			this.prefixedSearchables.put(prefixInfo, prefixedSearchable);
			this.combinedSearchables.addSearchable(prefixedSearchable);
			if (prefixInfo.getPrefix() == '#') {
				this.tooltipSearchable = prefixedSearchable;
			}
		}
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
		for (Map.Entry<PrefixInfo<IListElementInfo<?>, IListElement<?>>, PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> entry : this.prefixedSearchables.entrySet()) {
			PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo = entry.getKey();
			PrefixedSearchable<IListElementInfo<?>, IListElement<?>> prefixedSearchable = entry.getValue();
			if (prefixedSearchable.getMode() == SearchMode.DISABLED) {
				continue;
			}
			if (isBackgroundThread() && prefixInfo.getPrefix() == '#') {
				continue;
			}
			Collection<String> strings = prefixedSearchable.getStrings(info);
			ISearchStorage<IListElement<?>> storage = prefixedSearchable.getSearchStorage();
			for (String string : strings) {
				storage.put(string, element);
			}
		}
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

	private static <T> Object getUid(ITypedIngredient<T> typedIngredient, IIngredientManager ingredientManager) {
		IIngredientHelper<T> ingredientHelper = ingredientManager.getIngredientHelper(typedIngredient.getType());
		return ingredientHelper.getUniqueId(typedIngredient.getIngredient(), UidContext.Ingredient);
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
		if (cache != null && cache.isCacheAvailable()) {
			addAllWithCache(infos, ingredientManager, cache);
		} else if (DebugConfig.isParallelSearchEnabled() && infos.size() >= 100) {
			addAllParallel(infos, ingredientManager);
		} else {
			addAllSequential(infos, ingredientManager);
		}
	}

	private void addAllWithCache(Collection<IListElementInfo<?>> infos, IIngredientManager ingredientManager, SearchStringCache cache) {
		for (IListElementInfo<?> info : infos) {
			IListElement<?> element = info.getElement();
			Object uid = getUid(info.getTypedIngredient(), ingredientManager);
			this.allElements.put(uid, element);
		}

		for (var entry : prefixedSearchables.entrySet()) {
			PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo = entry.getKey();
			PrefixedSearchable<IListElementInfo<?>, IListElement<?>> prefixedSearchable = entry.getValue();
			if (prefixedSearchable.getMode() == SearchMode.DISABLED) {
				continue;
			}

			ISearchStorage<IListElement<?>> storage = prefixedSearchable.getSearchStorage();
			char prefix = prefixInfo.getPrefix();
			int index = 0;

			for (IListElementInfo<?> info : infos) {
				String cacheId = String.valueOf(index++);
				List<String> cached = cache.getCachedStrings(cacheId, prefix);
				IListElement<?> element = info.getElement();

				if (cached != null) {
					cached.forEach(s -> storage.put(s, element));
				} else {
					prefixedSearchable.getStrings(info).forEach(s -> storage.put(s, element));
				}
			}
		}
	}

	private void addAllSequential(Collection<IListElementInfo<?>> infos, IIngredientManager ingredientManager) {
		for (IListElementInfo<?> info : infos) {
			IListElement<?> element = info.getElement();
			Object uid = getUid(info.getTypedIngredient(), ingredientManager);
			this.allElements.put(uid, element);
		}

		for (PrefixedSearchable<IListElementInfo<?>, IListElement<?>> prefixedSearchable : this.prefixedSearchables.values()) {
			if (prefixedSearchable.getMode() != SearchMode.DISABLED) {
				ISearchStorage<IListElement<?>> storage = prefixedSearchable.getSearchStorage();
				for (IListElementInfo<?> info : infos) {
					prefixedSearchable.getStrings(info).forEach(s -> storage.put(s, info.getElement()));
				}
			}
		}
	}

	private void addAllParallel(Collection<IListElementInfo<?>> infos, IIngredientManager ingredientManager) {
		infos.parallelStream().forEach(info -> {
			Object uid = getUid(info.getTypedIngredient(), ingredientManager);
			this.allElements.put(uid, info.getElement());
		});

		// Note: getStrings() calls tooltip generation which fires Forge events and
		// touches Minecraft thread-local state. This MUST run on the calling thread,
		// NOT on ForkJoinPool threads, to avoid ClassNotFoundException and
		// ConcurrentModificationException in mod event handlers.
		for (PrefixedSearchable<IListElementInfo<?>, IListElement<?>> prefixedSearchable : this.prefixedSearchables.values()) {
			if (prefixedSearchable.getMode() == SearchMode.DISABLED) {
				continue;
			}
			ISearchStorage<IListElement<?>> storage = prefixedSearchable.getSearchStorage();
			for (IListElementInfo<?> info : infos) {
				prefixedSearchable.getStrings(info).forEach(s -> storage.put(s, info.getElement()));
			}
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
			if (prefixInfo.getPrefix() == '#') {
				continue;
			}
			ISearchStorage<IListElement<?>> storage = prefixedSearchable.getSearchStorage();
			for (IListElementInfo<?> info : infos) {
				prefixedSearchable.getStrings(info).forEach(s -> storage.put(s, info.getElement()));
			}
		}

		synchronized (this.tooltipLock) {
			this.deferredTooltipInfos.addAll(infos);
		}
	}

	@Override
	public void processDeferredTooltips() {
		List<IListElementInfo<?>> infos;
		synchronized (this.tooltipLock) {
			if (this.deferredTooltipInfos.isEmpty()) {
				return;
			}
			infos = new ArrayList<>(this.deferredTooltipInfos);
			this.deferredTooltipInfos.clear();
		}

		if (isBackgroundThread()) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null) {
				mc.execute(this::processDeferredTooltips);
			}
			return;
		}

		if (this.tooltipSearchable == null || this.tooltipSearchable.getMode() == SearchMode.DISABLED) {
			return;
		}
		ISearchStorage<IListElement<?>> storage = this.tooltipSearchable.getSearchStorage();
		for (IListElementInfo<?> info : infos) {
			try {
				this.tooltipSearchable.getStrings(info).forEach(s -> storage.put(s, info.getElement()));
			} catch (Exception e) {
				LOGGER.debug("Failed to process deferred tooltip search strings for ingredient", e);
			}
		}
	}

	@Override
	public <T> Optional<IListElement<T>> findElement(ITypedIngredient<T> ingredient, IIngredientHelper<T> ingredientHelper) {
		Object ingredientUid = ingredientHelper.getUniqueId(ingredient.getIngredient(), UidContext.Ingredient);

		IListElement<?> listElement = allElements.get(ingredientUid);
		if (listElement != null && listElement.getTypedIngredient().getType().equals(ingredient.getType())) {
			@SuppressWarnings("unchecked")
			IListElement<T> cast = (IListElement<T>) listElement;
			return Optional.of(cast);
		}

		return Optional.empty();
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
		synchronized (this.tooltipLock) {
			this.deferredTooltipInfos.clear();
		}
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
}
