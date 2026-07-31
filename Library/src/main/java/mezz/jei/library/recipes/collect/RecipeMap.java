package mezz.jei.library.recipes.collect;

import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.IIngredientTypeWithSubtypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.config.DebugConfig;
import mezz.jei.library.ingredients.IIngredientSupplier;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A RecipeMap efficiently links recipes, IRecipeCategory, and Ingredients.
 * Optimized with parallel processing support for large datasets.
 */
public class RecipeMap {
	private static final Logger LOGGER = LogManager.getLogger();

	// Threshold for parallel processing
	private static final int PARALLEL_THRESHOLD = 100;

	private final RecipeIngredientTable recipeTable = new RecipeIngredientTable();
	private final Multimap<Object, RecipeType<?>> ingredientUidToCategoryMap = Multimaps.synchronizedSetMultimap(Multimaps.newSetMultimap(new ConcurrentHashMap<>(), ConcurrentHashMap::newKeySet));
	private final Multimap<Object, RecipeType<?>> categoryCatalystUidToRecipeCategoryMap = Multimaps.synchronizedSetMultimap(Multimaps.newSetMultimap(new ConcurrentHashMap<>(), ConcurrentHashMap::newKeySet));
	/**
	 * Read-only snapshots taken at {@link #compact()}, once loading has stopped writing.
	 * <p>
	 * The loading multimaps give every ingredient uid its own ConcurrentHashMap-backed set, which
	 * in a large pack means hundreds of thousands of concurrent sets holding one or two elements
	 * each. Collapsing them into pre-sorted immutable lists is a large memory saving, and lets
	 * lookups skip the lock and the defensive copy they previously did on every call.
	 * <p>
	 * Freezing <em>moves</em> the entries out of the multimap above rather than copying them, so
	 * the two representations are never both populated. Whichever one is in use is decided by
	 * whether the frozen field is null.
	 */
	@Nullable
	private volatile Map<Object, List<RecipeType<?>>> frozenIngredientTypes;
	@Nullable
	private volatile Map<Object, List<RecipeType<?>>> frozenCatalystTypes;
	private final Comparator<RecipeType<?>> recipeTypeComparator;
	private final IIngredientManager ingredientManager;
	private final RecipeIngredientRole role;

	public RecipeMap(Comparator<RecipeType<?>> recipeTypeComparator, IIngredientManager ingredientManager, RecipeIngredientRole role) {
		this.recipeTypeComparator = recipeTypeComparator;
		this.ingredientManager = ingredientManager;
		this.role = role;
	}

	/**
	 * Get recipe types for an ingredient with parallel sorting.
	 */
	public <T> Stream<RecipeType<?>> getRecipeTypes(ITypedIngredient<T> ingredient) {
		Object ingredientUid = getIngredientUid(ingredient);

		// Each side is resolved independently: a runtime write thaws only the map it touches, so
		// one can be frozen while the other is not.
		List<RecipeType<?>> recipeTypes = lookup(ingredientUid, frozenIngredientTypes, ingredientUidToCategoryMap);
		List<RecipeType<?>> catalystTypes = lookup(ingredientUid, frozenCatalystTypes, categoryCatalystUidToRecipeCategoryMap);

		// Frozen values are already deduplicated and sorted, so when only one side has entries
		// there is nothing left to merge, copy or sort.
		if (catalystTypes.isEmpty()) {
			return recipeTypes.stream();
		}
		if (recipeTypes.isEmpty()) {
			return catalystTypes.stream();
		}
		return Stream.concat(recipeTypes.stream(), catalystTypes.stream())
			.distinct()
			.sorted(recipeTypeComparator);
	}

	private List<RecipeType<?>> lookup(
		Object ingredientUid,
		@Nullable Map<Object, List<RecipeType<?>>> frozen,
		Multimap<Object, RecipeType<?>> live
	) {
		if (frozen != null) {
			List<RecipeType<?>> types = frozen.get(ingredientUid);
			return types == null ? List.of() : types;
		}
		synchronized (live) {
			return List.copyOf(live.get(ingredientUid));
		}
	}

	public <T> void addCatalystForCategory(RecipeType<?> recipeType, ITypedIngredient<T> ingredient) {
		Object ingredientUid = getIngredientUid(ingredient);
		// Move the frozen entries back first: they were drained out of this multimap, so writing
		// to it while the snapshot is still in place would strand everything already indexed.
		if (frozenCatalystTypes != null) {
			thaw(categoryCatalystUidToRecipeCategoryMap, true);
		}
		categoryCatalystUidToRecipeCategoryMap.put(ingredientUid, recipeType);
	}

	@UnmodifiableView
	public <T> List<T> getRecipes(RecipeType<T> recipeType, ITypedIngredient<?> ingredient) {
		Object ingredientUid = getIngredientUid(ingredient);
		return recipeTable.get(recipeType, ingredientUid);
	}

	public <T> boolean isCatalystForRecipeCategory(RecipeType<T> recipeType, ITypedIngredient<?> ingredient) {
		Object ingredientUid = getIngredientUid(ingredient);
		Map<Object, List<RecipeType<?>>> frozenCatalysts = frozenCatalystTypes;
		if (frozenCatalysts != null) {
			List<RecipeType<?>> types = frozenCatalysts.get(ingredientUid);
			return types != null && types.contains(recipeType);
		}
		return categoryCatalystUidToRecipeCategoryMap.containsEntry(ingredientUid, recipeType);
	}

	/**
	 * Add recipe with parallel processing for large ingredient lists.
	 */
	public <T> void addRecipe(RecipeType<T> recipeType, T recipe, IIngredientSupplier ingredientSupplier) {
		Collection<ITypedIngredient<?>> ingredients = ingredientSupplier.getIngredients(this.role);

		// Use parallel processing for large ingredient lists, but avoid nested parallelism
		if (DebugConfig.isParallelSearchEnabled() &&
			ingredients.size() >= PARALLEL_THRESHOLD &&
			java.util.concurrent.ForkJoinTask.getPool() == null) {
			addRecipeParallel(recipeType, recipe, ingredients);
		} else {
			addRecipeSequential(recipeType, recipe, ingredients);
		}
	}

	/**
	 * Sequential recipe addition (small ingredient lists).
	 */
	private <T> void addRecipeSequential(RecipeType<T> recipeType, T recipe, Collection<ITypedIngredient<?>> ingredients) {
		Set<Object> ingredientUids = new HashSet<>();
		for (ITypedIngredient<?> ingredient : ingredients) {
			Object ingredientUid = getIngredientUid(ingredient);
			ingredientUids.add(ingredientUid);
		}

		if (!ingredientUids.isEmpty()) {
			if (frozenIngredientTypes != null) {
				thaw(ingredientUidToCategoryMap, false);
			}
			for (Object ingredientUid : ingredientUids) {
				ingredientUidToCategoryMap.put(ingredientUid, recipeType);
			}
			recipeTable.add(recipe, recipeType, ingredientUids);
		}
	}

	/**
	 * Parallel recipe addition (large ingredient lists).
	 */
	private <T> void addRecipeParallel(RecipeType<T> recipeType, T recipe, Collection<ITypedIngredient<?>> ingredients) {
		try {
			// Extract ingredient UIDs in parallel
			// Note: getIngredientUidSafe reads NBT/data, not Forge registries, so no RegistryLock needed
			Set<Object> ingredientUids = ingredients.parallelStream()
				.map(this::getIngredientUidSafe)
				.filter(java.util.Objects::nonNull)
				.collect(Collectors.toCollection(ConcurrentHashMap::newKeySet));

			if (!ingredientUids.isEmpty()) {
				if (frozenIngredientTypes != null) {
					thaw(ingredientUidToCategoryMap, false);
				}

				// Update category map
				for (Object uid : ingredientUids) {
					ingredientUidToCategoryMap.put(uid, recipeType);
				}

				// Add to recipe table
				recipeTable.add(recipe, recipeType, ingredientUids);
			}
		} catch (Exception e) {
			LOGGER.warn("Parallel recipe addition failed, falling back to sequential for recipe type {}", recipeType, e);
			addRecipeSequential(recipeType, recipe, ingredients);
		}
	}

	private <T> Object getIngredientUidSafe(ITypedIngredient<T> typedIngredient) {
		try {
			return getIngredientUid(typedIngredient);
		} catch (Exception e) {
			LOGGER.error("Failed to get ingredient UID for {}", typedIngredient.getIngredient(), e);
			return null;
		}
	}

	public void compact() {
		recipeTable.compact();
		frozenIngredientTypes = freezeAndDrain(ingredientUidToCategoryMap);
		frozenCatalystTypes = freezeAndDrain(categoryCatalystUidToRecipeCategoryMap);
	}

	/**
	 * Moves a loading multimap into a plain map of pre-sorted immutable lists, emptying it as it
	 * goes so the two forms never both hold the data.
	 */
	private Map<Object, List<RecipeType<?>>> freezeAndDrain(Multimap<Object, RecipeType<?>> source) {
		List<Object> keys;
		synchronized (source) {
			keys = new ArrayList<>(source.keySet());
		}

		Map<Object, List<RecipeType<?>>> frozen = new HashMap<>(capacityFor(keys.size()));
		for (Object key : keys) {
			Collection<RecipeType<?>> removed;
			synchronized (source) {
				// removeAll hands back the values and releases the per-key concurrent set.
				removed = source.removeAll(key);
			}
			if (removed.isEmpty()) {
				continue;
			}
			List<RecipeType<?>> types = new ArrayList<>(removed);
			// Sort once here instead of on every lookup. The multimaps are set-backed, so the
			// values are already distinct.
			types.sort(recipeTypeComparator);
			frozen.put(key, List.copyOf(types));
		}
		return frozen;
	}

	/**
	 * Puts the entries back into the multimap so that content registered at runtime (via the
	 * recipe manager API) can still be indexed. Rare; the next {@link #compact()} re-freezes it.
	 */
	private void thaw(Multimap<Object, RecipeType<?>> target, boolean catalysts) {
		synchronized (target) {
			Map<Object, List<RecipeType<?>>> frozen = catalysts ? frozenCatalystTypes : frozenIngredientTypes;
			if (frozen == null) {
				return;
			}
			for (Map.Entry<Object, List<RecipeType<?>>> entry : frozen.entrySet()) {
				target.putAll(entry.getKey(), entry.getValue());
			}
			if (catalysts) {
				frozenCatalystTypes = null;
			} else {
				frozenIngredientTypes = null;
			}
		}
	}

	private static int capacityFor(int size) {
		return Math.max(16, (int) (size / 0.75f) + 1);
	}

	private <T> Object getIngredientUid(ITypedIngredient<T> typedIngredient) {
		IIngredientType<T> type = typedIngredient.getType();
		T ingredient = typedIngredient.getIngredient();
		IIngredientHelper<T> ingredientHelper = ingredientManager.getIngredientHelper(type);

		if (type instanceof IIngredientTypeWithSubtypes<?, T> ingredientTypeWithSubtypes) {
			if (!ingredientHelper.hasSubtypes(ingredient)) {
				return ingredientTypeWithSubtypes.getBase(ingredient);
			}
		}

		return ingredientHelper.getUniqueId(ingredient, UidContext.Recipe);
	}
}
