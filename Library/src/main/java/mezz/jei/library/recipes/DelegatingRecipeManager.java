package mezz.jei.library.recipes;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.drawable.IScalableDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeCategoriesLookup;
import mezz.jei.api.recipe.IRecipeCatalystLookup;
import mezz.jei.api.recipe.IRecipeLookup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import mezz.jei.api.ingredients.IIngredientSupplier;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class DelegatingRecipeManager implements IRecipeManager {
	private @Nullable IRecipeManager delegate;

	public void setDelegate(IRecipeManager delegate) {
		this.delegate = delegate;
	}

	@Override
	public <R> IRecipeLookup<R> createRecipeLookup(RecipeType<R> recipeType) {
		if (delegate == null) {
			return null;
		}
		return delegate.createRecipeLookup(recipeType);
	}

	@Override
	public IRecipeCategoriesLookup createRecipeCategoryLookup() {
		if (delegate == null) {
			return null;
		}
		return delegate.createRecipeCategoryLookup();
	}

	@Override
	public <T> IRecipeCategory<T> getRecipeCategory(RecipeType<T> recipeType) {
		if (delegate == null) {
			return null;
		}
		return delegate.getRecipeCategory(recipeType);
	}

	@Override
	public IRecipeCatalystLookup createRecipeCatalystLookup(RecipeType<?> recipeType) {
		if (delegate == null) {
			return null;
		}
		return delegate.createRecipeCatalystLookup(recipeType);
	}

	@Override
	public <T> void hideRecipes(RecipeType<T> recipeType, Collection<T> recipes) {
		if (delegate != null) {
			delegate.hideRecipes(recipeType, recipes);
		}
	}

	@Override
	public <T> void unhideRecipes(RecipeType<T> recipeType, Collection<T> recipes) {
		if (delegate != null) {
			delegate.unhideRecipes(recipeType, recipes);
		}
	}

	@Override
	public <T> void addRecipes(RecipeType<T> recipeType, List<T> recipes) {
		if (delegate != null) {
			delegate.addRecipes(recipeType, recipes);
		}
	}

	@Override
	public void hideRecipeCategory(RecipeType<?> recipeType) {
		if (delegate != null) {
			delegate.hideRecipeCategory(recipeType);
		}
	}

	@Override
	public void unhideRecipeCategory(RecipeType<?> recipeType) {
		if (delegate != null) {
			delegate.unhideRecipeCategory(recipeType);
		}
	}

	@Override
	public <T> IRecipeLayoutDrawable<T> createRecipeLayoutDrawableOrShowError(IRecipeCategory<T> recipeCategory, T recipe, IFocusGroup focusGroup) {
		if (delegate == null) {
			return null;
		}
		return delegate.createRecipeLayoutDrawableOrShowError(recipeCategory, recipe, focusGroup);
	}

	@Override
	public <T> Optional<IRecipeLayoutDrawable<T>> createRecipeLayoutDrawable(IRecipeCategory<T> recipeCategory, T recipe, IFocusGroup focusGroup) {
		if (delegate == null) {
			return Optional.empty();
		}
		return delegate.createRecipeLayoutDrawable(recipeCategory, recipe, focusGroup);
	}

	@Override
	public <T> Optional<IRecipeLayoutDrawable<T>> createRecipeLayoutDrawable(IRecipeCategory<T> recipeCategory, T recipe, IFocusGroup focusGroup, IScalableDrawable background, int borderSize) {
		if (delegate == null) {
			return Optional.empty();
		}
		return delegate.createRecipeLayoutDrawable(recipeCategory, recipe, focusGroup, background, borderSize);
	}

	@Override
	public IRecipeSlotDrawable createRecipeSlotDrawable(RecipeIngredientRole role, List<Optional<ITypedIngredient<?>>> ingredients, Set<Integer> focusedIngredients, int ingredientCycleOffset) {
		if (delegate == null) {
			return null;
		}
		return delegate.createRecipeSlotDrawable(role, ingredients, focusedIngredients, ingredientCycleOffset);
	}

	@Override
	public <T> Optional<RecipeType<T>> getRecipeType(ResourceLocation recipeUid, Class<? extends T> recipeClass) {
		if (delegate == null) {
			return Optional.empty();
		}
		return delegate.getRecipeType(recipeUid, recipeClass);
	}

	@Override
	public <T> IIngredientSupplier getRecipeIngredients(IRecipeCategory<T> recipeCategory, T recipe) {
		if (delegate == null) {
			return null;
		}
		return delegate.getRecipeIngredients(recipeCategory, recipe);
	}

	@Override
	public Optional<RecipeType<?>> getRecipeType(ResourceLocation recipeUid) {
		if (delegate == null) {
			return Optional.empty();
		}
		return delegate.getRecipeType(recipeUid);
	}

	@Override
	public List<IRecipeButtonControllerFactory> getRecipeButtonControllerFactories() {
		if (delegate == null) {
			return Collections.emptyList();
		}
		return delegate.getRecipeButtonControllerFactories();
	}
}
