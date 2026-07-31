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

	/**
	 * Prefix whose strings can only be derived on the render thread, because generating a tooltip
	 * fires mod tooltip events that are not safe off-thread.
	 */
	private static final char TOOLTIP_PREFIX = '#';

	/** An ingredient whose tooltip strings still have to be derived on the render thread. */
	private record DeferredTooltip(IListElementInfo<?> info, String uid) {}

	private final Object tooltipLock = new Object();
	private final List<DeferredTooltip> deferredTooltipInfos = new ArrayList<>();
	@Nullable
	private PrefixedSearchable<IListElementInfo<?>, IListElement<?>> tooltipSearchable = null;
	@Nullable
	private volatile SearchStringCache searchStringCache = null;

	public ElementSearch(ElementPrefixParser elementPrefixParser) {
		for (PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo : elementPrefixParser.allPrefixInfos()) {
			ISearchStorage<IListElement<?>> storage = prefixInfo.createStorage();
			var prefixedSearchable = new PrefixedSearchable<>(storage, prefixInfo);
			this.prefixedSearchables.put(prefixInfo, prefixedSearchable);
			this.combinedSearchables.addSearchable(prefixedSearchable);
			if (prefixInfo.getPrefix() == TOOLTIP_PREFIX) {
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
		String uidString = uid.toString();
		boolean backgroundThread = isBackgroundThread();
		for (Map.Entry<PrefixInfo<IListElementInfo<?>, IListElement<?>>, PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> entry : this.prefixedSearchables.entrySet()) {
			PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo = entry.getKey();
			PrefixedSearchable<IListElementInfo<?>, IListElement<?>> prefixedSearchable = entry.getValue();
			if (prefixedSearchable.getMode() == SearchMode.DISABLED) {
				continue;
			}
			char prefix = prefixInfo.getPrefix();
			if (indexFromCache(prefixedSearchable, prefix, element, uidString)) {
				continue;
			}
			if (backgroundThread && prefix == TOOLTIP_PREFIX) {
				// Tooltip generation must happen on the render thread; defer it so it is
				// still indexed later instead of being dropped.
				synchronized (this.tooltipLock) {
					this.deferredTooltipInfos.add(new DeferredTooltip(info, uidString));
				}
				continue;
			}
			indexAndRecord(prefixedSearchable, prefix, info, element, uidString);
		}
	}

	/**
	 * Indexes an ingredient from previously cached search strings, if they are available.
	 * <p>
	 * This is what keeps tooltip indexing off the render thread: deriving a tooltip means firing
	 * the mod tooltip events for every single ingredient, which is by far the most expensive part
	 * of building the search index. A cache hit skips that entirely.
	 *
	 * @return true if the ingredient was indexed from the cache and needs no further work
	 */
	private boolean indexFromCache(
		PrefixedSearchable<IListElementInfo<?>, IListElement<?>> prefixedSearchable,
		char prefix,
		IListElement<?> element,
		String uidString
	) {
		SearchStringCache cache = this.searchStringCache;
		if (cache == null) {
			return false;
		}
		List<String> cached = cache.getCachedStrings(uidString, prefix);
		if (cached == null) {
			return false;
		}
		ISearchStorage<IListElement<?>> storage = prefixedSearchable.getSearchStorage();
		for (String string : cached) {
			storage.put(string, element);
		}
		return true;
	}

	/**
	 * Derives an ingredient's search strings, indexes them, and records them for the next launch.
	 */
	private void indexAndRecord(
		PrefixedSearchable<IListElementInfo<?>, IListElement<?>> prefixedSearchable,
		char prefix,
		IListElementInfo<?> info,
		IListElement<?> element,
		String uidString
	) {
		Collection<String> strings = prefixedSearchable.getStrings(info);
		ISearchStorage<IListElement<?>> storage = prefixedSearchable.getSearchStorage();
		for (String string : strings) {
			storage.put(string, element);
		}
		SearchStringCache cache = this.searchStringCache;
		if (cache != null) {
			cache.recordStrings(uidString, prefix, strings);
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
		this.searchStringCache = cache;
		if (isBackgroundThread()) {
			addAllBackground(infos, ingredientManager);
			return;
		}
		if (DebugConfig.isParallelSearchEnabled() && infos.size() >= 100) {
			addAllParallel(infos, ingredientManager);
		} else {
			addAllSequential(infos, ingredientManager);
		}
	}

	private void addAllSequential(Collection<IListElementInfo<?>> infos, IIngredientManager ingredientManager) {
		List<String> uids = registerElements(infos, ingredientManager, false);

		for (PrefixedSearchable<IListElementInfo<?>, IListElement<?>> prefixedSearchable : this.prefixedSearchables.values()) {
			if (prefixedSearchable.getMode() != SearchMode.DISABLED) {
				indexAll(prefixedSearchable, infos, uids, null);
			}
		}
	}

	private void addAllParallel(Collection<IListElementInfo<?>> infos, IIngredientManager ingredientManager) {
		List<String> uids = registerElements(infos, ingredientManager, true);

		// Note: getStrings() calls tooltip generation which fires Forge events and
		// touches Minecraft thread-local state. This MUST run on the calling thread,
		// NOT on ForkJoinPool threads, to avoid ClassNotFoundException and
		// ConcurrentModificationException in mod event handlers.
		for (PrefixedSearchable<IListElementInfo<?>, IListElement<?>> prefixedSearchable : this.prefixedSearchables.values()) {
			if (prefixedSearchable.getMode() == SearchMode.DISABLED) {
				continue;
			}
			indexAll(prefixedSearchable, infos, uids, null);
		}
	}

	private void addAllBackground(Collection<IListElementInfo<?>> infos, IIngredientManager ingredientManager) {
		List<String> uids = registerElements(infos, ingredientManager, true);
		List<DeferredTooltip> deferred = new ArrayList<>();

		for (Map.Entry<PrefixInfo<IListElementInfo<?>, IListElement<?>>, PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> entry : this.prefixedSearchables.entrySet()) {
			PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo = entry.getKey();
			PrefixedSearchable<IListElementInfo<?>, IListElement<?>> prefixedSearchable = entry.getValue();
			if (prefixedSearchable.getMode() == SearchMode.DISABLED) {
				continue;
			}
			// Tooltip strings that are already cached get indexed right here on the background
			// thread; only the ones we have never derived before need the render thread.
			indexAll(prefixedSearchable, infos, uids, prefixInfo.getPrefix() == TOOLTIP_PREFIX ? deferred : null);
		}

		if (!deferred.isEmpty()) {
			synchronized (this.tooltipLock) {
				this.deferredTooltipInfos.addAll(deferred);
			}
		}
	}

	/**
	 * Puts every element into the uid lookup and returns their uid strings, positionally matching
	 * {@code infos}, so the cache can be keyed by ingredient rather than by position in this list.
	 * The previous cache lookup used the list index as the key, which is not stable across
	 * launches, so it could never produce a usable hit.
	 */
	private List<String> registerElements(Collection<IListElementInfo<?>> infos, IIngredientManager ingredientManager, boolean parallel) {
		List<IListElementInfo<?>> ordered = infos instanceof List<IListElementInfo<?>> list ? list : new ArrayList<>(infos);
		String[] uids = new String[ordered.size()];
		java.util.stream.IntStream indices = parallel
			? java.util.stream.IntStream.range(0, ordered.size()).parallel()
			: java.util.stream.IntStream.range(0, ordered.size());
		indices.forEach(i -> {
			IListElementInfo<?> info = ordered.get(i);
			Object uid = getUid(info.getTypedIngredient(), ingredientManager);
			this.allElements.put(uid, info.getElement());
			uids[i] = uid.toString();
		});
		return java.util.Arrays.asList(uids);
	}

	/**
	 * Indexes one prefix across every ingredient, preferring cached strings.
	 *
	 * @param deferTo if non-null, ingredients with no cached strings are collected here instead of
	 *                having their strings derived now
	 */
	private void indexAll(
		PrefixedSearchable<IListElementInfo<?>, IListElement<?>> prefixedSearchable,
		Collection<IListElementInfo<?>> infos,
		List<String> uids,
		@Nullable List<DeferredTooltip> deferTo
	) {
		char prefix = prefixedSearchable.getPrefixInfo().getPrefix();
		int index = 0;
		for (IListElementInfo<?> info : infos) {
			String uidString = uids.get(index++);
			IListElement<?> element = info.getElement();
			if (indexFromCache(prefixedSearchable, prefix, element, uidString)) {
				continue;
			}
			if (deferTo != null) {
				deferTo.add(new DeferredTooltip(info, uidString));
				continue;
			}
			indexAndRecord(prefixedSearchable, prefix, info, element, uidString);
		}
	}

	@Override
	public void processDeferredTooltips() {
		// Tooltip generation must run on the render thread. If we're still on the
		// background loading thread, reschedule onto the main thread WITHOUT draining
		// the deferred list first, otherwise the rescheduled call would find it empty
		// and the tooltip search index (used to find e.g. enchanted books by enchantment)
		// would never be built.
		if (isBackgroundThread()) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null) {
				mc.execute(this::processDeferredTooltips);
			}
			return;
		}

		List<DeferredTooltip> deferred;
		synchronized (this.tooltipLock) {
			deferred = new ArrayList<>(this.deferredTooltipInfos);
			this.deferredTooltipInfos.clear();
		}

		PrefixedSearchable<IListElementInfo<?>, IListElement<?>> searchable = this.tooltipSearchable;
		if (searchable != null && searchable.getMode() != SearchMode.DISABLED && !deferred.isEmpty()) {
			LOGGER.info("Deriving tooltip search strings for {} uncached ingredients", deferred.size());
			for (DeferredTooltip entry : deferred) {
				try {
					// Records into the cache as it goes, so this only ever has to happen once for
					// a given set of mods rather than on every launch.
					indexAndRecord(searchable, TOOLTIP_PREFIX, entry.info(), entry.info().getElement(), entry.uid());
				} catch (Exception e) {
					LOGGER.debug("Failed to process deferred tooltip search strings for ingredient", e);
				}
			}
		}

		finishCache();
	}

	/**
	 * Persists whatever had to be derived this launch and drops the in-memory copies. Runs only
	 * once the deferred tooltips are done, which is the last thing to be indexed.
	 */
	private void finishCache() {
		SearchStringCache cache = this.searchStringCache;
		if (cache == null) {
			return;
		}
		this.searchStringCache = null;
		cache.saveAsync();
		cache.release();
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
