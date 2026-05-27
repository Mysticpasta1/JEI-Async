package mezz.jei.library.load.registration;

import com.google.common.base.Preconditions;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.common.util.ErrorUtil;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class RecipeCategoryRegistration implements IRecipeCategoryRegistration {
	private static final Logger LOGGER = LogManager.getLogger();
	private final List<IRecipeCategory<?>> recipeCategories = new ArrayList<>();
	private final Map<ResourceLocation, RecipeType<?>> recipeTypes = new HashMap<>();
	private final IJeiHelpers jeiHelpers;
	private final Consumer<Collection<IRecipeCategory<?>>> categoryListener;

	public RecipeCategoryRegistration(IJeiHelpers jeiHelpers, Consumer<Collection<IRecipeCategory<?>>> categoryListener) {
		this.jeiHelpers = jeiHelpers;
		this.categoryListener = categoryListener;
	}

	@Override
	public void addRecipeCategories(IRecipeCategory<?>... recipeCategories) {
		ErrorUtil.checkNotEmpty(recipeCategories, "recipeCategories");

		List<IRecipeCategory<?>> added = new ArrayList<>();
		for (IRecipeCategory<?> recipeCategory : recipeCategories) {
			RecipeType<?> recipeType = recipeCategory.getRecipeType();
			Preconditions.checkNotNull(recipeType, "Recipe type cannot be null %s", recipeCategory);
			ResourceLocation recipeTypeUid = recipeType.getUid();
			if (recipeTypes.containsKey(recipeTypeUid)) {
				RecipeType<?> existing = recipeTypes.get(recipeTypeUid);
				LOGGER.warn("Skipping duplicate registration for recipe type UID '{}': {} (existing: {})", recipeTypeUid, recipeType, existing);
				continue;
			}
			recipeTypes.put(recipeTypeUid, recipeType);
			Preconditions.checkArgument(recipeCategory.getWidth() > 0, "Width must be greater than 0");
			Preconditions.checkArgument(recipeCategory.getHeight() > 0, "Height must be greater than 0");
			added.add(recipeCategory);
		}

		if (!added.isEmpty()) {
			this.recipeCategories.addAll(added);
			this.categoryListener.accept(Collections.unmodifiableCollection(this.recipeCategories));
		}
	}

	@Override
	public IJeiHelpers getJeiHelpers() {
		return jeiHelpers;
	}

	@Unmodifiable
	public List<IRecipeCategory<?>> getRecipeCategories() {
		return List.copyOf(recipeCategories);
	}
}
