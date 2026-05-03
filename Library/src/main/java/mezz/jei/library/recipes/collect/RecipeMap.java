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
import mezz.jei.core.QuantifiedIntegration.QuantifiedIntegration;
import mezz.jei.library.ingredients.IIngredientSupplier;
import org.jetbrains.annotations.UnmodifiableView;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
		Collection<RecipeType<?>> recipeCategoryUids;
		Collection<RecipeType<?>> catalystRecipeCategoryUids;

		synchronized (ingredientUidToCategoryMap) {
			recipeCategoryUids = List.copyOf(ingredientUidToCategoryMap.get(ingredientUid));
		}
		synchronized (categoryCatalystUidToRecipeCategoryMap) {
			catalystRecipeCategoryUids = List.copyOf(categoryCatalystUidToRecipeCategoryMap.get(ingredientUid));
		}

		return Stream.concat(recipeCategoryUids.stream(), catalystRecipeCategoryUids.stream())
			.distinct()
			.sorted(recipeTypeComparator);
	}

	public <T> void addCatalystForCategory(RecipeType<?> recipeType, ITypedIngredient<T> ingredient) {
		Object ingredientUid = getIngredientUid(ingredient);
		categoryCatalystUidToRecipeCategoryMap.put(ingredientUid, recipeType);
	}

	@UnmodifiableView
	public <T> List<T> getRecipes(RecipeType<T> recipeType, ITypedIngredient<?> ingredient) {
		Object ingredientUid = getIngredientUid(ingredient);
		return recipeTable.get(recipeType, ingredientUid);
	}

	public <T> boolean isCatalystForRecipeCategory(RecipeType<T> recipeType, ITypedIngredient<?> ingredient) {
		Object ingredientUid = getIngredientUid(ingredient);
		return categoryCatalystUidToRecipeCategoryMap.containsEntry(ingredientUid, recipeType);
	}

	/**
	 * Add recipe with QAPI processing for large ingredient lists.
	 */
	public <T> void addRecipe(RecipeType<T> recipeType, T recipe, IIngredientSupplier ingredientSupplier) {
		Collection<ITypedIngredient<?>> ingredients = ingredientSupplier.getIngredients(this.role);

		// Use parallel processing for large ingredient lists, but avoid nested parallelism
		if (DebugConfig.isParallelSearchEnabled() &&
			ingredients.size() >= PARALLEL_THRESHOLD) {
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
			for (Object ingredientUid : ingredientUids) {
				ingredientUidToCategoryMap.put(ingredientUid, recipeType);
			}
			recipeTable.add(recipe, recipeType, ingredientUids);
		}
	}

	/**
	 * QAPI recipe addition (large ingredient lists).
	 */
	private <T> void addRecipeParallel(RecipeType<T> recipeType, T recipe, Collection<ITypedIngredient<?>> ingredients) {
		QuantifiedIntegration.runAsync("jei-recipe-parallel-add-" + recipeType.getUid().getPath(), () -> {
			try {
				// Extract ingredient UIDs in parallel
				List<Object> ingredientUidList = QuantifiedIntegration.mapOrdered("jei-recipe-ingredient-uid", ingredients, this::getIngredientUidSafe);

				Set<Object> ingredientUids = ingredientUidList.stream()
					.filter(java.util.Objects::nonNull)
					.collect(Collectors.toCollection(ConcurrentHashMap::newKeySet));

				if (!ingredientUids.isEmpty()) {
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
		});
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
