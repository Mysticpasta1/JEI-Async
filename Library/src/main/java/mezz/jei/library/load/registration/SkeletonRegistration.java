package mezz.jei.library.load.registration;

import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.search.ISearchStorage;
import mezz.jei.api.search.ISearchStorageBuilderFactory;
import mezz.jei.api.search.ISearchStorageFactory;
import mezz.jei.core.search.BakedSubstringIndexBuilder;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.advanced.IRecipeManagerPlugin;
import mezz.jei.api.recipe.advanced.IRecipeManagerPluginHelper;
import mezz.jei.api.recipe.advanced.ISimpleRecipeManagerPlugin;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.category.extensions.IExtendableRecipeCategory;
import mezz.jei.api.recipe.category.extensions.IRecipeCategoryDecorator;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;
import mezz.jei.api.recipe.category.extensions.vanilla.smithing.IExtendableSmithingRecipeCategory;
import mezz.jei.api.recipe.transfer.*;
import mezz.jei.api.recipe.vanilla.IVanillaRecipeFactory;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.registration.IRuntimeRegistration;
import mezz.jei.api.registration.IVanillaCategoryExtensionRegistration;
import mezz.jei.api.runtime.IBookmarkOverlay;
import mezz.jei.api.runtime.IEditModeConfig;
import mezz.jei.api.runtime.IIngredientFilter;
import mezz.jei.api.runtime.IIngredientListOverlay;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiFeatures;
import mezz.jei.api.runtime.IRecipesGui;
import mezz.jei.api.runtime.IScreenHelper;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.ItemLike;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class SkeletonRegistration implements
	IRecipeCategoryRegistration,
	IRecipeRegistration,
	IVanillaCategoryExtensionRegistration,
	IRecipeTransferRegistration,
	IRecipeCatalystRegistration,
	IGuiHandlerRegistration,
	IAdvancedRegistration,
	IRuntimeRegistration {

	private final IJeiHelpers jeiHelpers;
	private final IIngredientManager ingredientManager;
	private final IRecipeManager recipeManager;
	private final IEditModeConfig editModeConfig;
	private final IScreenHelper screenHelper;
	private final IRecipeTransferHandlerHelper transferHelper;

	public SkeletonRegistration(
		IJeiHelpers jeiHelpers,
		IIngredientManager ingredientManager,
		IRecipeManager recipeManager,
		IEditModeConfig editModeConfig,
		IScreenHelper screenHelper,
		IRecipeTransferHandlerHelper transferHelper
	) {
		this.jeiHelpers = jeiHelpers;
		this.ingredientManager = ingredientManager;
		this.recipeManager = recipeManager;
		this.editModeConfig = editModeConfig;
		this.screenHelper = screenHelper;
		this.transferHelper = transferHelper;
	}

	@Override
	public IJeiHelpers getJeiHelpers() {
		return jeiHelpers;
	}

	@Override
	public IIngredientManager getIngredientManager() {
		return ingredientManager;
	}

	@Override
	public IVanillaRecipeFactory getVanillaRecipeFactory() {
		return jeiHelpers.getVanillaRecipeFactory();
	}

	@Override
	public ISearchStorageBuilderFactory getSearchStorageBuilderFactory() {
		return BakedSubstringIndexBuilder::new;
	}

	@SuppressWarnings("removal")
	@Override
	public ISearchStorageFactory getSearchStorageFactory() {
		return new ISearchStorageFactory() {
			@Override
			public <T> ISearchStorage<T> createSearchStorage() {
				return new BakedSubstringIndexBuilder<T>().build();
			}
		};
	}

	@Override
	public mezz.jei.api.runtime.IIngredientVisibility getIngredientVisibility() {
		return jeiHelpers.getIngredientVisibility();
	}

	@Override
	public void addRecipeCategories(IRecipeCategory<?>... recipeCategories) {}

	@Override
	public <T> void addRecipes(RecipeType<T> recipeType, List<T> recipes) {}

	@Override
	public <T> void addIngredientInfo(T ingredient, IIngredientType<T> ingredientType, net.minecraft.network.chat.Component... descriptionComponents) {}

	@Override
	public <T> void addIngredientInfo(List<T> ingredients, IIngredientType<T> ingredientType, net.minecraft.network.chat.Component... descriptionComponents) {}

	@Override
	public IExtendableRecipeCategory<CraftingRecipe, ICraftingCategoryExtension> getCraftingCategory() { return null; }

	@Override
	public IExtendableSmithingRecipeCategory getSmithingCategory() { return null; }

	@Override
	public IRecipeTransferHandlerHelper getTransferHelper() {
		return transferHelper;
	}

	@Override
	public <C extends AbstractContainerMenu, R> void addRecipeTransferHandler(Class<? extends C> containerClass, @Nullable MenuType<C> menuType, RecipeType<R> recipeType, int recipeSlotStart, int recipeSlotCount, int inventorySlotStart, int inventorySlotCount) {}

	@Override
	public <C extends AbstractContainerMenu, R> void addRecipeTransferHandler(IRecipeTransferInfo<C, R> recipeTransferInfo) {}

	@Override
	public <C extends AbstractContainerMenu, R> void addRecipeTransferHandler(IRecipeTransferHandler<C, R> recipeTransferHandler, RecipeType<R> recipeType) {}

	@Override
	public <C extends AbstractContainerMenu> void addUniversalRecipeTransferHandler(IUniversalRecipeTransferHandler<C> universalRecipeTransferHandler) {}

	@Override
	public <C extends AbstractContainerMenu, R> void addUniversalRecipeTransferHandler(IRecipeTransferHandler<C, R> universalRecipeTransferHandler) {}

	@Override
	public void addRecipeCatalysts(RecipeType<?> recipeType, ItemLike... ingredients) {}

	@Override
	public void addRecipeCatalysts(RecipeType<?> recipeType, ItemStack... ingredients) {}

	@Override
	public <T> void addRecipeCatalysts(RecipeType<?> recipeType, IIngredientType<T> ingredientType, List<T> ingredients) {}

	@Override
	public <T> void addRecipeCatalyst(IIngredientType<T> ingredientType, T ingredient, RecipeType<?>... recipeTypes) {}

	@Override
	public <T extends net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>> void addGuiContainerHandler(Class<? extends T> guiClass, mezz.jei.api.gui.handlers.IGuiContainerHandler<T> guiHandler) {}

	@Override
	public <T extends net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>> void addGenericGuiContainerHandler(Class<? extends T> guiClass, mezz.jei.api.gui.handlers.IGuiContainerHandler<?> guiHandler) {}

	@Override
	public <T extends net.minecraft.client.gui.screens.Screen> void addGuiScreenHandler(Class<T> guiClass, mezz.jei.api.gui.handlers.IScreenHandler<T> handler) {}

	@Override
	public void addGlobalGuiHandler(mezz.jei.api.gui.handlers.IGlobalGuiHandler globalGuiHandler) {}

	@Override
	public <T extends AbstractContainerScreen<?>> void addRecipeClickArea(Class<? extends T> containerScreenClass, int xPos, int yPos, int width, int height, RecipeType<?>... recipeTypes) {
		IGuiHandlerRegistration.super.addRecipeClickArea(containerScreenClass, xPos, yPos, width, height, recipeTypes);
	}

	@Override
	public <T extends net.minecraft.client.gui.screens.Screen> void addGhostIngredientHandler(Class<T> guiClass, mezz.jei.api.gui.handlers.IGhostIngredientHandler<T> handler) {}

	@Override
	public IRecipeManagerPluginHelper getRecipeManagerPluginHelper() { return null; }

	@Override
	public void addRecipeManagerPlugin(IRecipeManagerPlugin recipeManagerPlugin) {}

	@Override
	public <T> void addTypedRecipeManagerPlugin(RecipeType<T> recipeType, ISimpleRecipeManagerPlugin<T> recipeManagerPlugin) {}

	@Override
	public <T> void addRecipeCategoryDecorator(RecipeType<T> recipeType, IRecipeCategoryDecorator<T> decorator) {}

	@Override
	public IJeiFeatures getJeiFeatures() { return null; }

	@Override
	public void setIngredientListOverlay(IIngredientListOverlay ingredientListOverlay) {}

	@Override
	public void setBookmarkOverlay(IBookmarkOverlay bookmarkOverlay) {}

	@Override
	public void setRecipesGui(IRecipesGui recipesGui) {}

	@Override
	public void setIngredientFilter(IIngredientFilter ingredientFilter) {}

	@Override
	public IRecipeManager getRecipeManager() { return recipeManager; }

	@Override
	public IScreenHelper getScreenHelper() {
		return screenHelper;
	}

	@Override
	public IRecipeTransferManager getRecipeTransferManager() { return null; }

	@Override
	public IEditModeConfig getEditModeConfig() {
		return editModeConfig;
	}
}
