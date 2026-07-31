package mezz.jei.library.load;

import com.google.common.collect.ImmutableListMultimap;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.helpers.IColorHelper;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.helpers.IModIdHelper;
import mezz.jei.api.helpers.IStackHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.advanced.IRecipeManagerPlugin;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.category.extensions.IRecipeCategoryDecorator;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IIngredientVisibility;
import mezz.jei.api.runtime.IJeiFeatures;
import mezz.jei.api.runtime.IScreenHelper;
import mezz.jei.common.Internal;
import mezz.jei.common.config.IClientToggleState;
import mezz.jei.common.config.IIngredientFilterConfig;
import mezz.jei.common.network.IConnectionToServer;
import mezz.jei.common.platform.IPlatformFluidHelperInternal;
import mezz.jei.common.platform.Services;
import mezz.jei.common.util.StackHelper;
import mezz.jei.core.util.LoggedTimer;
import mezz.jei.library.config.EditModeConfig;
import mezz.jei.library.config.IModIdFormatConfig;
import mezz.jei.library.config.RecipeCategorySortingConfig;
import mezz.jei.library.focus.FocusFactory;
import mezz.jei.library.gui.helpers.GuiHelper;
import mezz.jei.library.helpers.ModIdHelper;
import mezz.jei.library.ingredients.IngredientBlacklistInternal;
import mezz.jei.library.ingredients.IngredientVisibility;
import mezz.jei.library.ingredients.subtypes.SubtypeInterpreters;
import mezz.jei.library.ingredients.subtypes.SubtypeManager;
import mezz.jei.library.load.registration.AdvancedRegistration;
import mezz.jei.library.load.registration.GuiHandlerRegistration;
import mezz.jei.library.load.registration.IngredientManagerBuilder;
import mezz.jei.library.load.registration.RecipeCatalystRegistration;
import mezz.jei.library.load.registration.RecipeCategoryRegistration;
import mezz.jei.library.load.registration.RecipeManagerPluginHelper;
import mezz.jei.library.load.registration.RecipeRegistration;
import mezz.jei.library.load.registration.RecipeTransferRegistration;
import mezz.jei.library.load.registration.SubtypeRegistration;
import mezz.jei.library.load.registration.VanillaCategoryExtensionRegistration;
import mezz.jei.library.plugins.vanilla.VanillaPlugin;
import mezz.jei.library.plugins.vanilla.VanillaRecipeFactory;
import mezz.jei.library.plugins.vanilla.anvil.SmithingRecipeCategory;
import mezz.jei.library.plugins.vanilla.crafting.CraftingRecipeCategory;
import mezz.jei.library.recipes.RecipeManager;
import mezz.jei.library.recipes.RecipeManagerInternal;
import mezz.jei.library.runtime.DelegatingJeiHelpers;
import mezz.jei.library.runtime.JeiHelpers;
import mezz.jei.library.startup.StartData;
import mezz.jei.library.transfer.RecipeTransferHandlerHelper;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

public final class PluginLoader {
	private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger();
	private PluginLoader() {}

	public static SubtypeManager registerSubtypes(StartData data) {
		return registerSubtypes(data, false, null);
	}

	public static SubtypeManager registerSubtypes(StartData data, boolean useAsyncFallback, IncompatiblePluginStore incompatiblePluginStore) {
		return registerSubtypes(data, useAsyncFallback, incompatiblePluginStore, null);
	}

	public static SubtypeManager registerSubtypes(StartData data, boolean useAsyncFallback, IncompatiblePluginStore incompatiblePluginStore, @Nullable Consumer<Runnable> mainThreadRunner) {
		IPlatformFluidHelperInternal<?> fluidHelper = Services.PLATFORM.getFluidHelper();
		List<IModPlugin> plugins = data.plugins();
		SubtypeRegistration subtypeRegistration = new SubtypeRegistration();
		if (useAsyncFallback && incompatiblePluginStore != null) {
			PluginCaller.callOnPluginsWithFallback("Registering item subtypes", plugins, p -> p.registerItemSubtypes(subtypeRegistration), incompatiblePluginStore, mainThreadRunner);
			PluginCaller.callOnPluginsWithFallback("Registering fluid subtypes", plugins, p ->
				p.registerFluidSubtypes(subtypeRegistration, fluidHelper), incompatiblePluginStore, mainThreadRunner);
		} else {
			PluginCaller.callOnPlugins("Registering item subtypes", plugins, p -> p.registerItemSubtypes(subtypeRegistration), mainThreadRunner, incompatiblePluginStore);
			PluginCaller.callOnPlugins("Registering fluid subtypes", plugins, p ->
				p.registerFluidSubtypes(subtypeRegistration, fluidHelper), mainThreadRunner, incompatiblePluginStore);
		}
		SubtypeInterpreters subtypeInterpreters = subtypeRegistration.getInterpreters();
		return new SubtypeManager(subtypeInterpreters);
	}

	public static IIngredientManager registerIngredients(StartData data, SubtypeManager subtypeManager, IColorHelper colorHelper, IIngredientFilterConfig ingredientFilterConfig) {
		return registerIngredients(data, subtypeManager, colorHelper, ingredientFilterConfig, false, null);
	}

	public static IIngredientManager registerIngredients(StartData data, SubtypeManager subtypeManager, IColorHelper colorHelper, IIngredientFilterConfig ingredientFilterConfig, boolean useAsyncFallback, IncompatiblePluginStore incompatiblePluginStore) {
		return registerIngredients(data, subtypeManager, colorHelper, ingredientFilterConfig, useAsyncFallback, incompatiblePluginStore, null);
	}

	public static IIngredientManager registerIngredients(StartData data, SubtypeManager subtypeManager, IColorHelper colorHelper, IIngredientFilterConfig ingredientFilterConfig, boolean useAsyncFallback, IncompatiblePluginStore incompatiblePluginStore, @Nullable Consumer<Runnable> mainThreadRunner) {
		List<IModPlugin> plugins = data.plugins();
		IngredientManagerBuilder ingredientManagerBuilder = new IngredientManagerBuilder(subtypeManager, colorHelper);
		if (useAsyncFallback && incompatiblePluginStore != null) {
			PluginCaller.callOnPluginsWithFallback("Registering ingredients", plugins, p -> p.registerIngredients(ingredientManagerBuilder), incompatiblePluginStore, mainThreadRunner);
			PluginCaller.callOnPluginsWithFallback("Registering extra ingredients", plugins, p -> p.registerExtraIngredients(ingredientManagerBuilder), incompatiblePluginStore, mainThreadRunner);
		} else {
			PluginCaller.callOnPlugins("Registering ingredients", plugins, p -> p.registerIngredients(ingredientManagerBuilder), mainThreadRunner, incompatiblePluginStore);
			PluginCaller.callOnPlugins("Registering extra ingredients", plugins, p -> p.registerExtraIngredients(ingredientManagerBuilder), mainThreadRunner, incompatiblePluginStore);
		}

		if (ingredientFilterConfig.getSearchIngredientAliases()) {
			if (useAsyncFallback && incompatiblePluginStore != null) {
				PluginCaller.callOnPluginsWithFallback("Registering search ingredient aliases", plugins, p -> p.registerIngredientAliases(ingredientManagerBuilder), incompatiblePluginStore, mainThreadRunner);
			} else {
				PluginCaller.callOnPlugins("Registering search ingredient aliases", plugins, p -> p.registerIngredientAliases(ingredientManagerBuilder), mainThreadRunner, incompatiblePluginStore);
			}
		}
		return ingredientManagerBuilder.build();
	}

	public static JeiHelpers createJeiHelpers(
		IModIdFormatConfig modIdFormatConfig,
		IColorHelper colorHelper,
		EditModeConfig editModeConfig,
		FocusFactory focusFactory,
		IIngredientManager ingredientManager,
		SubtypeManager subtypeManager
	) {
		VanillaRecipeFactory vanillaRecipeFactory = new VanillaRecipeFactory(ingredientManager);
		StackHelper stackHelper = new StackHelper(subtypeManager);
		GuiHelper guiHelper = new GuiHelper(ingredientManager);

		IModIdHelper modIdHelper = new ModIdHelper(modIdFormatConfig, ingredientManager);

		IClientToggleState toggleState = Internal.getClientToggleState();
		IngredientBlacklistInternal blacklist = new IngredientBlacklistInternal();
		ingredientManager.registerIngredientListener(blacklist);

		IIngredientVisibility ingredientVisibility = new IngredientVisibility(
			blacklist,
			toggleState,
			editModeConfig,
			ingredientManager
		);

		return new JeiHelpers(guiHelper, stackHelper, modIdHelper, focusFactory, colorHelper, ingredientManager, vanillaRecipeFactory, ingredientVisibility);
	}

	@Unmodifiable
	private static List<IRecipeCategory<?>> createRecipeCategories(List<IModPlugin> plugins, VanillaPlugin vanillaPlugin, IJeiHelpers jeiHelpers) {
		return createRecipeCategories(plugins, vanillaPlugin, jeiHelpers, false, null, p -> null);
	}

	@Unmodifiable
	private static List<IRecipeCategory<?>> createRecipeCategories(List<IModPlugin> plugins, VanillaPlugin vanillaPlugin, IJeiHelpers jeiHelpers, boolean useAsyncFallback, IncompatiblePluginStore incompatiblePluginStore) {
		return createRecipeCategories(plugins, vanillaPlugin, jeiHelpers, useAsyncFallback, incompatiblePluginStore, p -> null);
	}

	@Unmodifiable
	private static List<IRecipeCategory<?>> createRecipeCategories(List<IModPlugin> plugins, VanillaPlugin vanillaPlugin, IJeiHelpers jeiHelpers, boolean useAsyncFallback, IncompatiblePluginStore incompatiblePluginStore, @Nullable Consumer<Runnable> mainThreadRunner) {
		return createRecipeCategories(plugins, vanillaPlugin, jeiHelpers, useAsyncFallback, incompatiblePluginStore,
			mainThreadRunner != null ? p -> mainThreadRunner : p -> null
		);
	}

	@Unmodifiable
	private static List<IRecipeCategory<?>> createRecipeCategories(List<IModPlugin> plugins, VanillaPlugin vanillaPlugin, IJeiHelpers jeiHelpers, boolean useAsyncFallback, IncompatiblePluginStore incompatiblePluginStore, @Nullable Function<IModPlugin, Consumer<Runnable>> mainThreadRunnerResolver) {
		RecipeCategoryRegistration recipeCategoryRegistration = new RecipeCategoryRegistration(jeiHelpers, categories -> {
			if (jeiHelpers instanceof JeiHelpers concreteHelpers) {
				concreteHelpers.setRecipeCategories(categories);
			} else if (jeiHelpers instanceof DelegatingJeiHelpers delegatingHelpers) {
				IJeiHelpers delegate = delegatingHelpers.getDelegate();
				if (delegate instanceof JeiHelpers concreteHelpers) {
					concreteHelpers.setRecipeCategories(categories);
				}
			}
		});
		if (useAsyncFallback && incompatiblePluginStore != null) {
			PluginCaller.callOnPluginsWithFallback("Registering categories", plugins, p -> p.registerCategories(recipeCategoryRegistration), incompatiblePluginStore, mainThreadRunnerResolver);
		} else {
			PluginCaller.callOnPlugins("Registering categories", plugins, p -> p.registerCategories(recipeCategoryRegistration), mainThreadRunnerResolver, incompatiblePluginStore);
		}
		if (vanillaPlugin.getCraftingCategory().isEmpty() || vanillaPlugin.getSmithingCategory().isEmpty()) {
			// The vanilla categories are required to build the vanilla category extensions, so losing
			// them takes the whole load down. VanillaPlugin assigns these fields before it hands the
			// categories to the registration, so an empty Optional means it never got that far and
			// re-running it cannot duplicate anything already registered.
			LOGGER.warn("The vanilla JEI plugin did not register its categories, running it again directly.");
			try {
				vanillaPlugin.registerCategories(recipeCategoryRegistration);
			} catch (Throwable e) {
				throw new IllegalStateException("The vanilla JEI plugin failed to register its recipe categories", e);
			}
		}
		CraftingRecipeCategory craftingCategory = vanillaPlugin.getCraftingCategory()
			.orElseThrow(() -> new IllegalStateException("The vanilla JEI plugin did not register a crafting category"));
		SmithingRecipeCategory smithingCategory = vanillaPlugin.getSmithingCategory()
			.orElseThrow(() -> new IllegalStateException("The vanilla JEI plugin did not register a smithing category"));
		VanillaCategoryExtensionRegistration vanillaCategoryExtensionRegistration = new VanillaCategoryExtensionRegistration(craftingCategory, smithingCategory, jeiHelpers);
		if (useAsyncFallback && incompatiblePluginStore != null) {
			PluginCaller.callOnPluginsWithFallback("Registering vanilla category extensions", plugins, p -> p.registerVanillaCategoryExtensions(vanillaCategoryExtensionRegistration), incompatiblePluginStore, mainThreadRunnerResolver);
		} else {
			PluginCaller.callOnPlugins("Registering vanilla category extensions", plugins, p -> p.registerVanillaCategoryExtensions(vanillaCategoryExtensionRegistration), mainThreadRunnerResolver, incompatiblePluginStore);
		}
		return recipeCategoryRegistration.getRecipeCategories();
	}

	public static IScreenHelper createGuiScreenHelper(List<IModPlugin> plugins, IJeiHelpers jeiHelpers, IIngredientManager ingredientManager) {
		return createGuiScreenHelper(plugins, jeiHelpers, ingredientManager, null);
	}

	public static IScreenHelper createGuiScreenHelper(List<IModPlugin> plugins, IJeiHelpers jeiHelpers, IIngredientManager ingredientManager, @Nullable IncompatiblePluginStore incompatiblePluginStore) {
		return createGuiScreenHelper(plugins, jeiHelpers, ingredientManager, incompatiblePluginStore, null);
	}

	public static IScreenHelper createGuiScreenHelper(List<IModPlugin> plugins, IJeiHelpers jeiHelpers, IIngredientManager ingredientManager, @Nullable IncompatiblePluginStore incompatiblePluginStore, @Nullable Consumer<Runnable> mainThreadRunner) {
		GuiHandlerRegistration guiHandlerRegistration = new GuiHandlerRegistration(jeiHelpers);
		PluginCaller.callOnPlugins("Registering gui handlers", plugins, p -> p.registerGuiHandlers(guiHandlerRegistration), mainThreadRunner, incompatiblePluginStore);
		return guiHandlerRegistration.createGuiScreenHelper(ingredientManager);
	}

	public static IRecipeTransferManager createRecipeTransferManager(
		List<IModPlugin> plugins,
		IJeiHelpers jeiHelpers,
		IConnectionToServer connectionToServer
	) {
		return createRecipeTransferManager(plugins, jeiHelpers, connectionToServer, null);
	}

	public static IRecipeTransferManager createRecipeTransferManager(
		List<IModPlugin> plugins,
		IJeiHelpers jeiHelpers,
		IConnectionToServer connectionToServer,
		@Nullable IncompatiblePluginStore incompatiblePluginStore
	) {
		return createRecipeTransferManager(plugins, jeiHelpers, connectionToServer, incompatiblePluginStore, null);
	}

	public static IRecipeTransferManager createRecipeTransferManager(
		List<IModPlugin> plugins,
		IJeiHelpers jeiHelpers,
		IConnectionToServer connectionToServer,
		@Nullable IncompatiblePluginStore incompatiblePluginStore,
		@Nullable Consumer<Runnable> mainThreadRunner
	) {
		IStackHelper stackHelper = jeiHelpers.getStackHelper();
		IRecipeTransferHandlerHelper handlerHelper = new RecipeTransferHandlerHelper(stackHelper);
		RecipeTransferRegistration recipeTransferRegistration = new RecipeTransferRegistration(stackHelper, handlerHelper, jeiHelpers, connectionToServer);
		PluginCaller.callOnPlugins("Registering recipes transfer handlers", plugins, p -> p.registerRecipeTransferHandlers(recipeTransferRegistration), mainThreadRunner, incompatiblePluginStore);
		return recipeTransferRegistration.createRecipeTransferManager();
	}

	public static RecipeManager createRecipeManager(
		List<IModPlugin> plugins,
		VanillaPlugin vanillaPlugin,
		RecipeCategorySortingConfig recipeCategorySortingConfig,
		IJeiHelpers jeiHelpers,
		IIngredientManager ingredientManager
	) {
		return createRecipeManager(plugins, vanillaPlugin, recipeCategorySortingConfig, jeiHelpers, ingredientManager, false, null);
	}

	public static RecipeManager createRecipeManager(
		List<IModPlugin> plugins,
		VanillaPlugin vanillaPlugin,
		RecipeCategorySortingConfig recipeCategorySortingConfig,
		IJeiHelpers jeiHelpers,
		IIngredientManager ingredientManager,
		boolean useAsyncFallback,
		IncompatiblePluginStore incompatiblePluginStore
	) {
		return createRecipeManager(plugins, vanillaPlugin, recipeCategorySortingConfig, jeiHelpers, ingredientManager, useAsyncFallback, incompatiblePluginStore, null);
	}

	public static RecipeManager createRecipeManager(
		List<IModPlugin> plugins,
		VanillaPlugin vanillaPlugin,
		RecipeCategorySortingConfig recipeCategorySortingConfig,
		IJeiHelpers jeiHelpers,
		IIngredientManager ingredientManager,
		boolean useAsyncFallback,
		IncompatiblePluginStore incompatiblePluginStore,
		@Nullable Consumer<Runnable> mainThreadRunner
	) {
		List<IRecipeCategory<?>> recipeCategories = createRecipeCategories(plugins, vanillaPlugin, jeiHelpers, useAsyncFallback, incompatiblePluginStore, Minecraft.getInstance()::execute);

		RecipeCatalystRegistration recipeCatalystRegistration = new RecipeCatalystRegistration(ingredientManager, jeiHelpers);
		callPlugins("Registering recipe catalysts", plugins, p -> p.registerRecipeCatalysts(recipeCatalystRegistration), useAsyncFallback, incompatiblePluginStore, mainThreadRunner);
		ImmutableListMultimap<RecipeType<?>, ITypedIngredient<?>> recipeCatalysts = recipeCatalystRegistration.getRecipeCatalysts();

		LoggedTimer timer = new LoggedTimer();
		timer.start("Building recipe registry");

		RecipeManagerInternal recipeManagerInternal = new RecipeManagerInternal(
			recipeCategories,
			recipeCatalysts,
			ingredientManager,
			recipeCategorySortingConfig,
			jeiHelpers.getIngredientVisibility()
		);

		IJeiFeatures jeiFeatures = Internal.getJeiFeatures();
		RecipeManagerPluginHelper recipeManagerPluginHelper = new RecipeManagerPluginHelper(recipeManagerInternal);
		AdvancedRegistration advancedRegistration = new AdvancedRegistration(jeiHelpers, jeiFeatures, recipeManagerPluginHelper);
		callPlugins("Registering advanced plugins", plugins, p -> p.registerAdvanced(advancedRegistration), useAsyncFallback, incompatiblePluginStore, mainThreadRunner);

		List<IRecipeManagerPlugin> recipeManagerPlugins = advancedRegistration.getRecipeManagerPlugins();
		ImmutableListMultimap<RecipeType<?>, IRecipeCategoryDecorator<?>> recipeCategoryDecorators = advancedRegistration.getRecipeCategoryDecorators();
		recipeManagerInternal.addPlugins(recipeManagerPlugins);
		recipeManagerInternal.addDecorators(recipeCategoryDecorators);

		RecipeRegistration recipeRegistration = new RecipeRegistration(jeiHelpers, ingredientManager, recipeManagerInternal);

		callPlugins("Registering recipes", plugins, p -> p.registerRecipes(recipeRegistration), useAsyncFallback, incompatiblePluginStore, mainThreadRunner);
		recipeManagerInternal.compact();

		timer.stop();
		return new RecipeManager(recipeManagerInternal, ingredientManager);
	}

	private static void callPlugins(
			String title,
			List<IModPlugin> plugins,
			java.util.function.Consumer<IModPlugin> func,
			boolean useAsyncFallback,
			IncompatiblePluginStore incompatiblePluginStore
	) {
		callPlugins(title, plugins, func, useAsyncFallback, incompatiblePluginStore, p -> null);
	}

	private static void callPlugins(
			String title,
			List<IModPlugin> plugins,
			Consumer<IModPlugin> func,
			boolean useAsyncFallback,
			IncompatiblePluginStore incompatiblePluginStore,
			@Nullable Consumer<Runnable> mainThreadRunner
	) {
		callPlugins(title, plugins, func, useAsyncFallback, incompatiblePluginStore,
			mainThreadRunner != null ? p -> mainThreadRunner : p -> null
		);
	}

	private static void callPlugins(
			String title,
			List<IModPlugin> plugins,
			Consumer<IModPlugin> func,
			boolean useAsyncFallback,
			IncompatiblePluginStore incompatiblePluginStore,
			@Nullable Function<IModPlugin, Consumer<Runnable>> mainThreadRunnerResolver
	) {
		if (useAsyncFallback && incompatiblePluginStore != null) {
			PluginCaller.callOnPluginsWithFallback(title, plugins, func, incompatiblePluginStore, mainThreadRunnerResolver);
		} else {
			PluginCaller.callOnPlugins(title, plugins, func, mainThreadRunnerResolver, incompatiblePluginStore);
		}
	}
}
