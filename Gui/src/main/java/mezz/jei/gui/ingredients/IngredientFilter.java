package mezz.jei.gui.ingredients;

import mezz.jei.api.helpers.IColorHelper;
import mezz.jei.api.helpers.IModIdHelper;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IIngredientVisibility;
import mezz.jei.api.search.ISearchStorageBuilderFactory;
import mezz.jei.common.config.DebugConfig;
import mezz.jei.common.config.IClientConfig;
import mezz.jei.common.config.IClientToggleState;
import mezz.jei.common.config.IIngredientFilterConfig;
import mezz.jei.gui.filter.IFilterTextSource;
import mezz.jei.gui.overlay.elements.IElement;
import mezz.jei.gui.overlay.ingredients.IIngredientGridSource;
import mezz.jei.gui.overlay.elements.IngredientElement;
import mezz.jei.gui.search.ElementPrefixParser;
import mezz.jei.gui.search.ElementSearch;
import mezz.jei.gui.search.ElementSearchLowMem;
import mezz.jei.gui.search.IElementSearch;
import mezz.jei.gui.search.SearchStringCache;
import mezz.jei.gui.search.SearchTokenizer;
import mezz.jei.gui.search.Token;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import mezz.jei.core.util.RegistryLock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;

public class IngredientFilter implements
		IIngredientGridSource,
		IIngredientManager.IIngredientListener,
		IIngredientVisibility.IListener,
		IClientToggleState.IEditModeListener,
		AutoCloseable {
	private static final Logger LOGGER = LogManager.getLogger();
	private final SearchTokenizer searchTokenizer = new SearchTokenizer();

	private final IClientConfig clientConfig;
	private final IFilterTextSource filterTextSource;
	private final IIngredientManager ingredientManager;
	private Comparator<IListElement<?>> ingredientComparator;
	private final IModIdHelper modIdHelper;
	private final IIngredientVisibility ingredientVisibility;
	private final Function<List<IListElementInfo<?>>, Comparator<IListElement<?>>> sortIndexUpdater;

	private final ElementPrefixParser elementPrefixParser;
	private IElementSearch elementSearch;

	@Nullable
	private volatile List<IElement<?>> ingredientListCached;
	private final List<SourceListChangedListener> listeners = new ArrayList<>();
	private final List<CompletableFuture<?>> tasks = Collections.synchronizedList(new ArrayList<>());
	private volatile boolean closed = false;
	private boolean searchIndexDirty;
	private boolean sortIndexesDirty;

	public IngredientFilter(
		IFilterTextSource filterTextSource,
		IClientConfig clientConfig,
		IIngredientFilterConfig config,
		IIngredientManager ingredientManager,
		Function<List<IListElementInfo<?>>, Comparator<IListElement<?>>> sortIndexUpdater,
		List<IListElementInfo<?>> ingredients,
		IModIdHelper modIdHelper,
		IIngredientVisibility ingredientVisibility,
		IColorHelper colorHelper,
		ISearchStorageBuilderFactory searchStorageBuilderFactory,
		IClientToggleState clientToggleState,
		@Nullable SearchStringCache searchStringCache
	) {
		this.filterTextSource = filterTextSource;
		this.clientConfig = clientConfig;
		this.ingredientManager = ingredientManager;
		this.sortIndexUpdater = sortIndexUpdater;
		this.ingredientComparator = sortIndexUpdater.apply(ingredients);
		this.modIdHelper = modIdHelper;
		this.ingredientVisibility = ingredientVisibility;
		this.elementPrefixParser = new ElementPrefixParser(ingredientManager, config, colorHelper, modIdHelper, searchStorageBuilderFactory);

		this.elementSearch = createElementSearch(clientConfig, elementPrefixParser, ingredients, ingredientManager, searchStringCache);
		addConfigListeners(clientConfig, config);

		LOGGER.info("Adding {} ingredients", ingredients.size());
		for (IListElementInfo<?> ingredient : ingredients) {
			updateHiddenState(ingredient.getElement());
		}
		invalidateCache();
		LOGGER.info("Added {} ingredients", ingredients.size());
		if (DebugConfig.isLogSuffixTreeStatsEnabled()) {
			this.elementSearch.logStatistics();
		}

		// Saving and releasing the cache happens inside processDeferredTooltips, because when this
		// runs on the background loader that call defers to the render thread and returns before
		// the tooltip strings actually exist.
		this.elementSearch.processDeferredTooltips();

		this.filterTextSource.addListener(filterText -> {
			invalidateCache();
			notifyListenersOfChange();
		});

		clientToggleState.addEditModeToggleListener(this);

		// Pre-build the sorted ingredient list cache on the current thread (background thread during async loading)
		// to avoid a main-thread freeze when the user first opens their inventory.
		getElements();
	}

	@Override
	public void close() {
		LOGGER.info("Closing IngredientFilter, cancelling {} tasks", tasks.size());
		this.closed = true;
		synchronized (tasks) {
			for (CompletableFuture<?> task : tasks) {
				task.cancel(true);
			}
			tasks.clear();
		}
		this.elementSearch.clear();
		this.listeners.clear();
		this.ingredientListCached = null;
	}

	private void addConfigListeners(IClientConfig clientConfig, IIngredientFilterConfig config) {
		clientConfig.addLowMemorySlowSearchEnabledListener(v -> markSearchIndexDirty());
		clientConfig.addIngredientSorterStagesListener(v -> markSortIndexesDirty());
		config.addSearchConfigListener(this::markSearchIndexDirty);
	}

	private static IElementSearch createElementSearch(
		IClientConfig clientConfig,
		ElementPrefixParser elementPrefixParser,
		List<IListElementInfo<?>> elementInfos,
		IIngredientManager ingredientManager
	) {
		return createElementSearch(clientConfig, elementPrefixParser, elementInfos, ingredientManager, null);
	}

	private static IElementSearch createElementSearch(
		IClientConfig clientConfig,
		ElementPrefixParser elementPrefixParser,
		List<IListElementInfo<?>> elementInfos,
		IIngredientManager ingredientManager,
		@Nullable SearchStringCache searchStringCache
	) {
		if (clientConfig.isLowMemorySlowSearchEnabled()) {
			// The low-memory search does not use the cache; drop it rather than holding every
			// cached search string for the rest of the session.
			if (searchStringCache != null) {
				searchStringCache.release();
			}
			return new ElementSearchLowMem(elementPrefixParser.getNoPrefix(), elementInfos);
		} else {
			if (searchStringCache != null) {
				// Collect whatever we end up deriving, so the next launch can skip it.
				searchStringCache.startCollecting();
			}
			return new ElementSearch(elementPrefixParser, elementInfos, ingredientManager, searchStringCache);
		}
	}

	public void addIngredient(IListElementInfo<?> info) {
		addIngredients(Collections.singletonList(info));
	}

	public synchronized void addIngredients(Collection<IListElementInfo<?>> ingredients) {
		if (closed) return;
		// Process hidden states in parallel if the list is large (guarded by RegistryLock)
		Stream<IListElementInfo<?>> stream = (DebugConfig.isParallelSearchEnabled())
				? ingredients.parallelStream()
				: ingredients.stream();

		stream.forEach(i -> {
			if (closed) return;
			synchronized (RegistryLock.get()) {
				updateHiddenState(i.getElement());
			}
		});

		if (closed) return;
		// Add to the search index. This appends to the mutable side of the already-built
		// storage; a full rebuild only happens via rebuildItemFilter().
		for (IListElementInfo<?> info : ingredients) {
			if (closed) return;
			this.elementSearch.add(info, ingredientManager);
		}

		invalidateCache();
	}

	public void invalidateCache() {
		ingredientListCached = null;
	}

	public void rebuildItemFilter() {
		this.invalidateCache();
		Collection<IListElement<?>> ingredients = this.elementSearch.getAllIngredients();
		List<IListElementInfo<?>> elementInfos = IngredientListElementFactory.rebuildList(ingredientManager, ingredients, modIdHelper);
		this.ingredientComparator = this.sortIndexUpdater.apply(elementInfos);
		this.elementSearch = createElementSearch(this.clientConfig, this.elementPrefixParser, elementInfos, ingredientManager);
		this.searchIndexDirty = false;
		this.sortIndexesDirty = false;
	}

	private void markSearchIndexDirty() {
		this.searchIndexDirty = true;
		notifyListenersOfChange();
	}

	private void markSortIndexesDirty() {
		this.sortIndexesDirty = true;
		notifyListenersOfChange();
	}

	private void updateDirtyState() {
		if (searchIndexDirty) {
			rebuildItemFilter();
		}
		if (sortIndexesDirty) {
			Collection<IListElement<?>> ingredients = this.elementSearch.getAllIngredients();
			List<IListElementInfo<?>> elementInfos = IngredientListElementFactory.rebuildList(ingredientManager, ingredients, modIdHelper);
			this.ingredientComparator = this.sortIndexUpdater.apply(elementInfos);
			this.sortIndexesDirty = false;
			invalidateCache();
		}
	}

	@Override
	public void onEditModeChanged() {
		updateHidden();
	}

	public void updateHidden() {
		boolean changed = false;
		for (IListElement<?> element : this.elementSearch.getAllIngredients()) {
			if (closed) return;
			changed |= updateHiddenState(element);
		}
		if (changed) {
			invalidateCache();
			notifyListenersOfChange();
		}
	}

	private <V> boolean updateHiddenState(IListElement<V> element) {
		ITypedIngredient<V> typedIngredient = element.getTypedIngredient();
		boolean visible = this.ingredientVisibility.isIngredientVisible(typedIngredient);
		if (element.isVisible() != visible) {
			element.setVisible(visible);
			return true;
		}
		return false;
	}

	@Override
	public <V> void onIngredientVisibilityChanged(ITypedIngredient<V> ingredient, boolean visible) {
		IIngredientType<V> ingredientType = ingredient.getType();
		IIngredientHelper<V> ingredientHelper = ingredientManager.getIngredientHelper(ingredientType);
		this.elementSearch.findElement(ingredient, ingredientHelper)
				.ifPresent(element -> {
					if (element.isVisible() != visible) {
						element.setVisible(visible);
						invalidateCache();
						notifyListenersOfChange();
					}
				});
	}

	@Override
	public <V> void onIngredientVisibilityChanged(Collection<ITypedIngredient<V>> ingredients, boolean visible) {
		boolean changed = false;
		for (ITypedIngredient<V> ingredient : ingredients) {
			IIngredientType<V> ingredientType = ingredient.getType();
			IIngredientHelper<V> ingredientHelper = ingredientManager.getIngredientHelper(ingredientType);
			Optional<IListElement<V>> elementOpt = this.elementSearch.findElement(ingredient, ingredientHelper);
			if (elementOpt.isPresent()) {
				IListElement<V> element = elementOpt.get();
				if (element.isVisible() != visible) {
					element.setVisible(visible);
					changed = true;
				}
			}
		}
		if (changed) {
			invalidateCache();
			notifyListenersOfChange();
		}
	}

	@Override
	public List<IElement<?>> getElements() {
		updateDirtyState();
		String filterText = this.filterTextSource.getFilterText();
		filterText = filterText.toLowerCase();
		List<IElement<?>> cached = ingredientListCached;
		if (cached == null) {
			cached = getIngredientListUncached(filterText)
					.<IElement<?>>map(IngredientElement::new)
					.toList();
			ingredientListCached = cached;
		}
		return cached;
	}

	public <T> List<T> getFilteredIngredients(IIngredientType<T> ingredientType) {
		return getElements()
				.stream()
				.map(IElement::getTypedIngredient)
				.map(i -> i.getIngredient(ingredientType))
				.flatMap(Optional::stream)
				.toList();
	}

	private Stream<ITypedIngredient<?>> getIngredientListUncached(String filterText) {
		String[] filters = filterText.split("\\|");
		List<SearchTokens> searchTokens = Arrays.stream(filters)
				.map(this::parseSearchTokens)
				.filter(s -> !s.isEmpty())
				.toList();

		Stream<IListElement<?>> elementStream;
		if (searchTokens.isEmpty()) {
			// Use parallel stream for large ingredient lists when parallel search is enabled
			// Parallel streams provide better performance with many ingredients
			Collection<IListElement<?>> allIngredients = this.elementSearch.getAllIngredients();
			if (DebugConfig.isParallelSearchEnabled() && allIngredients.size() >= 500) {
				elementStream = allIngredients.parallelStream();
			} else {
				elementStream = allIngredients.stream();
			}
		} else {
			// Use parallel processing for multi-token searches
			if (DebugConfig.isParallelSearchEnabled() && searchTokens.size() >= 2) {
				elementStream = searchTokens.parallelStream()
						.map(this::getSearchResults)
						.flatMap(Set::parallelStream)
						.distinct();
			} else {
				elementStream = searchTokens.stream()
						.map(this::getSearchResults)
						.flatMap(Set::stream)
						.distinct();
			}
		}

		return elementStream
				.filter(IListElement::isVisible)
				.sorted(ingredientComparator)
				.map(IListElement::getTypedIngredient);
	}

	@Override
	public <V> void onIngredientsAdded(IIngredientHelper<V> ingredientHelper, Collection<ITypedIngredient<V>> ingredients) {
		for (ITypedIngredient<V> value : ingredients) {
			Optional<IListElement<V>> matchingElementOptional = this.elementSearch.findElement(value, ingredientHelper);
			if (matchingElementOptional.isPresent()) {
				IListElement<V> matchingElement = matchingElementOptional.get();
				updateHiddenState(matchingElement);
				if (DebugConfig.isDebugIngredientsEnabled()) {
					LOGGER.debug("Updated ingredient: {}", ingredientHelper.getErrorInfo(value.getIngredient()));
				}
			} else {
				IListElementInfo<V> listElementInfo = ListElementInfo.create(value, this.ingredientManager, modIdHelper);
				if (listElementInfo != null) {
					addIngredient(listElementInfo);
					if (DebugConfig.isDebugIngredientsEnabled()) {
						LOGGER.debug("Added ingredient: {}", ingredientHelper.getErrorInfo(value.getIngredient()));
					}
				}
			}
		}
		invalidateCache();
	}

	@Override
	public <V> void onIngredientsRemoved(IIngredientHelper<V> ingredientHelper, Collection<ITypedIngredient<V>> ingredients) {
		// ignore this, it's handled by onIngredientVisibilityChanged
	}

	private record SearchTokens(List<ElementPrefixParser.TokenInfo> toSearch,
								List<ElementPrefixParser.TokenInfo> toRemove) {
		public boolean isEmpty() {
			return toSearch.isEmpty() && toRemove.isEmpty();
		}
	}

	private SearchTokens parseSearchTokens(String filterText) {
		SearchTokens searchTokens = new SearchTokens(new ArrayList<>(), new ArrayList<>());

		if (filterText.isEmpty()) {
			return searchTokens;
		}

		List<Token> tokens = searchTokenizer.tokenize(filterText);
		for (Token token : tokens) {
			if (token.isEmpty()) {
				continue;
			}
			this.elementPrefixParser.parseToken(token.text())
				.ifPresent(result -> {
					if (token.exclusion()) {
						searchTokens.toRemove.add(result);
					} else {
						searchTokens.toSearch.add(result);
					}
				});
		}
		return searchTokens;
	}

	private Set<IListElement<?>> getSearchResults(SearchTokens searchTokens) {
		List<Set<IListElement<?>>> resultsPerToken = searchTokens.toSearch.stream()
				.map(this.elementSearch::getSearchResults)
				.toList();
		Set<IListElement<?>> results = intersection(resultsPerToken);

		if (results.isEmpty() && !searchTokens.toRemove.isEmpty()) {
			results.addAll(this.elementSearch.getAllIngredients());
		}

		if (!results.isEmpty() && !searchTokens.toRemove.isEmpty()) {
			for (ElementPrefixParser.TokenInfo tokenInfo : searchTokens.toRemove) {
				Set<IListElement<?>> resultsToRemove = this.elementSearch.getSearchResults(tokenInfo);
				results.removeAll(resultsToRemove);
				if (results.isEmpty()) {
					break;
				}
			}
		}
		return results;

	}

	/**
	 * Get the elements that are contained in every set.
	 */
	private static <T> Set<T> intersection(List<Set<T>> sets) {
		Set<T> smallestSet = sets.stream()
				.min(Comparator.comparing(Set::size))
				.orElseGet(Set::of);

		Set<T> results = Collections.newSetFromMap(new IdentityHashMap<>());
		results.addAll(smallestSet);

		for (Set<T> set : sets) {
			if (set == smallestSet) {
				continue;
			}
			if (results.retainAll(set) && results.isEmpty()) {
				break;
			}
		}
		return results;
	}

	@Override
	public void addSourceListChangedListener(SourceListChangedListener listener) {
		listeners.add(listener);
	}

	private void notifyListenersOfChange() {
		if (closed) return;
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft != null && !minecraft.isSameThread()) {
				minecraft.execute(this::notifyListenersOfChangeSync);
				return;
			}
		} catch (Throwable ignored) {
			// Minecraft might not be available
		}
		notifyListenersOfChangeSync();
	}

	private void notifyListenersOfChangeSync() {
		if (closed) return;
		for (SourceListChangedListener listener : listeners) {
			listener.onSourceListChanged();
		}
	}
}
