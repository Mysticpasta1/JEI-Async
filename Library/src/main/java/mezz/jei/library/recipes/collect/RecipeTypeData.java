package mezz.jei.library.recipes.collect;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.category.IRecipeCategory;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class RecipeTypeData<T> {
	private final IRecipeCategory<T> recipeCategory;
	private final List<ITypedIngredient<?>> recipeCategoryCatalysts;
	private final ArrayList<T> backingRecipes = new ArrayList<>();
	private final List<T> recipes = java.util.Collections.synchronizedList(backingRecipes);
	private final Set<T> hiddenRecipes = java.util.Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
	/**
	 * Cached unmodifiable view. {@link #getRecipes()} is called per frame while a recipe GUI is
	 * open, and each call used to allocate a fresh wrapper.
	 */
	private final List<T> recipesView = Collections.unmodifiableList(recipes);

	public RecipeTypeData(IRecipeCategory<T> recipeCategory, List<ITypedIngredient<?>> recipeCategoryCatalysts) {
		this.recipeCategory = recipeCategory;
		this.recipeCategoryCatalysts = List.copyOf(recipeCategoryCatalysts);
	}

	public IRecipeCategory<T> getRecipeCategory() {
		return recipeCategory;
	}

	@Unmodifiable
	public List<ITypedIngredient<?>> getRecipeCategoryCatalysts() {
		return recipeCategoryCatalysts;
	}

	@UnmodifiableView
	public List<T> getRecipes() {
		return recipesView;
	}

	public void addRecipes(Collection<T> recipes) {
		this.recipes.addAll(recipes);
	}

	public Set<T> getHiddenRecipes() {
		return hiddenRecipes;
	}

	/**
	 * Trims the recipe list to size once loading is done. An ArrayList grown by repeated addAll
	 * can hold up to ~50% slack, which across every recipe type is a lot of dead array space.
	 */
	public void compact() {
		// Lock on the synchronized wrapper's monitor, which is what its own methods use.
		synchronized (recipes) {
			backingRecipes.trimToSize();
		}
	}
}
