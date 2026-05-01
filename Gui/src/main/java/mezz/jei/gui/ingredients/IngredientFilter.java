package mezz.jei.gui.ingredients;

import mezz.jei.api.helpers.IColorHelper;
import mezz.jei.api.helpers.IModIdHelper;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IIngredientVisibility;
import mezz.jei.common.config.DebugConfig;
import mezz.jei.common.config.IClientConfig;
import mezz.jei.common.config.IClientToggleState;
import mezz.jei.common.config.IIngredientFilterConfig;
import mezz.jei.core.QuantifiedIntegration.QuantifiedIntegration;
import mezz.jei.gui.filter.IFilterTextSource;
import mezz.jei.gui.overlay.elements.IElement;
import mezz.jei.gui.overlay.IIngredientGridSource;
import mezz.jei.gui.overlay.elements.IngredientElement;
import mezz.jei.gui.search.ElementPrefixParser;
import mezz.jei.gui.search.ElementSearch;
import mezz.jei.gui.search.ElementSearchLowMem;
import mezz.jei.gui.search.IElementSearch;
import mezz.jei.gui.search.SearchStringCache;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class IngredientFilter implements
		IIngredientGridSource,
		IIngredientManager.IIngredientListener,
		IIngredientVisibility.IListener,
		IClientToggleState.IEditModeListener,
		AutoCloseable {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final Pattern QUOTE_PATTERN = Pattern.compile("\"");
	private static final Pattern FILTER_SPLIT_PATTERN = Pattern.compile("(-?\".*?(?:\"|$)|\\S+)");

	private final IClientConfig clientConfig;
	private final IFilterTextSource filterTextSource;
	private final IIngredientManager ingredientManager;
	private final Comparator<IListElement<?>> ingredientComparator;
	private final IModIdHelper modIdHelper;
	private final IIngredientVisibility ingredientVisibility;

	private final ElementPrefixParser elementPrefixParser;
	private IElementSearch elementSearch;

	@Nullable
	private volatile List<IElement<?>> ingredientListCached;
	private final List<SourceListChangedListener> listeners = new ArrayList<>();
	private volatile boolean closed = false;

	public IngredientFilter(
			IFilterTextSource filterTextSource,
			IClientConfig clientConfig,
			IIngredientFilterConfig config,
			IIngredientManager ingredientManager,
			Comparator<IListElement<?>> ingredientComparator,
			List<IListElementInfo<?>> ingredients,
			IModIdHelper modIdHelper,
			IIngredientVisibility ingredientVisibility,
			IColorHelper colorHelper,
			IClientToggleState clientToggleState,
			@Nullable SearchStringCache searchStringCache
	) {
		this.filterTextSource = filterTextSource;
		this.clientConfig = clientConfig;
		this.ingredientManager = ingredientManager;
		this.ingredientComparator = ingredientComparator;
		this.modIdHelper = modIdHelper;
		this.ingredientVisibility = ingredientVisibility;
		this.elementPrefixParser = new ElementPrefixParser(ingredientManager, config, colorHelper);

		this.elementSearch = createElementSearch(clientConfig, elementPrefixParser);

		LOGGER.info("Adding {} ingredients", ingredients.size());
		for (IListElementInfo<?> ingredient : ingredients) {
			updateHiddenState(ingredient.getElement());
		}
		if (this.elementSearch instanceof ElementSearch elementSearchImpl && searchStringCache != null) {
			elementSearchImpl.addAll(ingredients, ingredientManager, searchStringCache);
		} else {
			this.elementSearch.addAll(ingredients, ingredientManager);
		}
		LOGGER.info("Added {} ingredients", ingredients.size());
		if (DebugConfig.isLogSuffixTreeStatsEnabled()) {
			this.elementSearch.logStatistics();
		}

		this.filterTextSource.addListener(filterText -> {
			invalidateCache();
			notifyListenersOfChange();
		});

		clientToggleState.addEditModeToggleListener(this);

		// Pre-build the sorted ingredient list cache through QAPI during loading
		// to avoid a main-thread freeze when the user first opens their inventory.
		getElements();
	}

	@Override
	public void close() {
		LOGGER.info("Closing IngredientFilter");
		this.closed = true;
	}

	private static IElementSearch createElementSearch(IClientConfig clientConfig, ElementPrefixParser elementPrefixParser) {
		if (clientConfig.isLowMemorySlowSearchEnabled()) {
			return new ElementSearchLowMem();
		} else {
			return new ElementSearch(elementPrefixParser);
		}
	}

	public void addIngredient(IListElementInfo<?> info) {
		addIngredients(Collections.singletonList(info));
	}

	public synchronized void addIngredients(Collection<IListElementInfo<?>> ingredients) {
		if (closed) return;
		if (DebugConfig.isParallelSearchEnabled() && ingredients.size() > 1) {
			QuantifiedIntegration.forEach("jei-hidden-state", ingredients, i -> {
				if (closed) return;
				updateHiddenState(i.getElement());
			});
		} else {
			for (IListElementInfo<?> i : ingredients) {
				if (closed) return;
				updateHiddenState(i.getElement());
			}
		}

		if (closed) return;
		// Add to search tree in bulk
		this.elementSearch.addAll(ingredients, ingredientManager);

		invalidateCache();
	}

	public void invalidateCache() {
		ingredientListCached = null;
	}

	public void rebuildItemFilter() {
		this.invalidateCache();
		Collection<IListElement<?>> ingredients = this.elementSearch.getAllIngredients();
		this.elementSearch = createElementSearch(this.clientConfig, this.elementPrefixParser);
		List<IListElementInfo<?>> elementInfos = IngredientListElementFactory.rebuildList(ingredientManager, ingredients, modIdHelper);
		addIngredients(elementInfos);
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
		String filterText = this.filterTextSource.getFilterText();
		filterText = filterText.toLowerCase();
		List<IElement<?>> cached = ingredientListCached;
		if (cached == null) {
			String taskFilterText = filterText;
			if (DebugConfig.isAsyncLoadingEnabled()) {
				cached = QuantifiedIntegration.submit("jei-filter-elements", () -> getIngredientListUncached(taskFilterText)
						.<IElement<?>>map(IngredientElement::new)
						.toList())
					.join();
			} else {
				cached = getIngredientListUncached(taskFilterText)
					.<IElement<?>>map(IngredientElement::new)
					.toList();
			}
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
			Collection<IListElement<?>> allIngredients = this.elementSearch.getAllIngredients();
			elementStream = allIngredients.stream();
		} else {
			if (DebugConfig.isParallelSearchEnabled() && searchTokens.size() >= 2) {
				elementStream = QuantifiedIntegration.mapOrdered("jei-filter-token-search", searchTokens, this::getSearchResults)
						.stream()
						.flatMap(Set::stream)
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
			Optional<IListElement<V>> matchingElement = this.elementSearch.findElement(value, ingredientHelper);
			if (matchingElement.isPresent()) {
				updateHiddenState(matchingElement.get());
				if (DebugConfig.isDebugModeEnabled()) {
					LOGGER.debug("Updated ingredient: {}", ingredientHelper.getErrorInfo(value.getIngredient()));
				}
			} else {
				IListElementInfo<V> listElementInfo = ListElementInfo.create(value, this.ingredientManager, modIdHelper);
				if (listElementInfo != null) {
					addIngredient(listElementInfo);
					if (DebugConfig.isDebugModeEnabled()) {
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
		Matcher filterMatcher = FILTER_SPLIT_PATTERN.matcher(filterText);
		while (filterMatcher.find()) {
			String string = filterMatcher.group(1);
			final boolean remove = string.startsWith("-");
			if (remove) {
				string = string.substring(1);
			}
			string = QUOTE_PATTERN.matcher(string).replaceAll("");
			if (string.isEmpty()) {
				continue;
			}
			this.elementPrefixParser.parseToken(string)
					.ifPresent(result -> {
						if (remove) {
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
