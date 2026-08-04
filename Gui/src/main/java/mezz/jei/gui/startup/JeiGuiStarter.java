package mezz.jei.gui.startup;

import mezz.jei.api.helpers.IColorHelper;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.helpers.IModIdHelper;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.api.registration.IRuntimeRegistration;
import mezz.jei.api.runtime.IEditModeConfig;
import mezz.jei.api.runtime.IIngredientFilter;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IIngredientVisibility;
import mezz.jei.api.runtime.IScreenHelper;
import mezz.jei.api.search.ISearchStorageBuilderFactory;
import mezz.jei.common.Internal;
import mezz.jei.common.config.IClientConfig;
import mezz.jei.common.config.IClientToggleState;
import mezz.jei.common.config.IIngredientFilterConfig;
import mezz.jei.common.config.IIngredientGridConfig;
import mezz.jei.common.config.IJeiClientConfigs;
import mezz.jei.common.gui.textures.Textures;
import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.common.network.IConnectionToServer;
import mezz.jei.common.util.ErrorUtil;
import mezz.jei.core.util.LoggedTimer;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.config.IBookmarkConfig;
import mezz.jei.gui.config.ILookupHistoryConfig;
import mezz.jei.gui.config.IngredientTypeSortingConfig;
import mezz.jei.gui.config.ModNameSortingConfig;
import mezz.jei.gui.events.GuiEventHandler;
import mezz.jei.gui.filter.FilterTextSource;
import mezz.jei.gui.filter.IFilterTextSource;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import mezz.jei.gui.ingredients.IngredientFilter;
import mezz.jei.gui.ingredients.IngredientFilterApi;
import mezz.jei.gui.ingredients.IngredientListElementFactory;
import mezz.jei.gui.ingredients.IngredientSorter;
import mezz.jei.gui.input.ClientInputHandler;
import mezz.jei.gui.input.CombinedRecipeFocusSource;
import mezz.jei.gui.input.GuiContainerWrapper;
import mezz.jei.gui.input.ICharTypedHandler;
import mezz.jei.gui.input.handlers.BookmarkInputHandler;
import mezz.jei.gui.input.handlers.ChatLinkInputHandler;
import mezz.jei.gui.input.handlers.DragRouter;
import mezz.jei.gui.input.handlers.EditInputHandler;
import mezz.jei.gui.input.handlers.FocusInputHandler;
import mezz.jei.gui.input.handlers.GlobalInputHandler;
import mezz.jei.gui.input.handlers.GuiAreaInputHandler;
import mezz.jei.gui.input.handlers.UserInputRouter;
import mezz.jei.gui.overlay.IngredientListOverlay;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import mezz.jei.gui.overlay.bookmarks.history.LookupHistory;
import mezz.jei.gui.recipes.RecipesGui;
import mezz.jei.gui.search.SearchStringCache;
import mezz.jei.gui.util.FocusUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.RegistryAccess;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

public class JeiGuiStarter {
	private static final Logger LOGGER = LogManager.getLogger();

	public static JeiEventHandlers start(IRuntimeRegistration registration) {
		LOGGER.info("Starting JEI GUI");
		LoggedTimer timer = new LoggedTimer();

		IConnectionToServer serverConnection = Internal.getServerConnection();
		Textures textures = Internal.getTextures();
		IInternalKeyMappings keyMappings = Internal.getKeyMappings();

		IScreenHelper screenHelper = registration.getScreenHelper();
		IRecipeTransferManager recipeTransferManager = registration.getRecipeTransferManager();
		IRecipeManager recipeManager = registration.getRecipeManager();
		IIngredientManager ingredientManager = registration.getIngredientManager();
		IEditModeConfig editModeConfig = registration.getEditModeConfig();
		ISearchStorageBuilderFactory searchStorageBuilderFactory = registration.getSearchStorageBuilderFactory();

		IJeiHelpers jeiHelpers = registration.getJeiHelpers();
		IIngredientVisibility ingredientVisibility = jeiHelpers.getIngredientVisibility();
		IColorHelper colorHelper = jeiHelpers.getColorHelper();
		IModIdHelper modIdHelper = jeiHelpers.getModIdHelper();
		IFocusFactory focusFactory = jeiHelpers.getFocusFactory();
		IGuiHelper guiHelper = jeiHelpers.getGuiHelper();

		IFilterTextSource filterTextSource = new FilterTextSource();
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		ErrorUtil.checkNotNull(level, "minecraft.level");

		RegistryAccess registryAccess = level.registryAccess();

		@SuppressWarnings("unchecked")
		List<IListElementInfo<?>> ingredientList = (List<IListElementInfo<?>>) registration.getInternalIngredientList()
			.orElseGet(() -> {
				LOGGER.info("Building ingredient list (Sync fallback)...");
				timer.start("Building ingredient list");
				List<IListElementInfo<?>> list = IngredientListElementFactory.createBaseList(ingredientManager, modIdHelper);
				timer.stop();
				return list;
			});

		timer.start("Building ingredient filter");
		GuiConfigData configData = GuiConfigData.create();

		ModNameSortingConfig modNameSortingConfig = configData.modNameSortingConfig();
		IngredientTypeSortingConfig ingredientTypeSortingConfig = configData.ingredientTypeSortingConfig();
		IClientToggleState toggleState = Internal.getClientToggleState();
		IBookmarkConfig bookmarkConfig = configData.bookmarkConfig();
		ILookupHistoryConfig lookupHistoryConfig = configData.lookupHistoryConfig();

		IJeiClientConfigs jeiClientConfigs = Internal.getJeiClientConfigs();
		IClientConfig clientConfig = jeiClientConfigs.getClientConfig();
		IIngredientGridConfig ingredientListConfig = jeiClientConfigs.getIngredientListConfig();
		IIngredientGridConfig bookmarkListConfig = jeiClientConfigs.getBookmarkListConfig();
		IIngredientFilterConfig ingredientFilterConfig = jeiClientConfigs.getIngredientFilterConfig();

		Function<List<IListElementInfo<?>>, Comparator<IListElement<?>>> sortIndexUpdater = ingredients -> IngredientSorter.sortIngredients(
			clientConfig,
			modNameSortingConfig,
			ingredientTypeSortingConfig,
			ingredientManager,
			ingredients
		);

		// Deriving search strings means generating a full tooltip for every ingredient, which fires
		// the mod tooltip events once per ingredient and has to happen on the render thread. In a
		// large pack that is minutes of frozen client on every single launch. Caching the results
		// keyed by the exact set of ingredients and the language means it only happens when
		// something actually changed.
		SearchStringCache searchStringCache = createSearchStringCache(ingredientList, ingredientManager);

		IngredientFilter ingredientFilter = new IngredientFilter(
			filterTextSource,
			clientConfig,
			ingredientFilterConfig,
			ingredientManager,
			sortIndexUpdater,
			ingredientList,
			modIdHelper,
			ingredientVisibility,
			colorHelper,
			searchStorageBuilderFactory,
			toggleState,
			searchStringCache
		);
		ingredientManager.registerIngredientListener(ingredientFilter);
		ingredientVisibility.registerListener(ingredientFilter);
		timer.stop();

		IIngredientFilter ingredientFilterApi = new IngredientFilterApi(ingredientFilter, filterTextSource);
		registration.setIngredientFilter(ingredientFilterApi);

		LookupHistory lookupHistory = new LookupHistory(
			recipeManager,
			ingredientManager,
			focusFactory,
			clientConfig,
			lookupHistoryConfig
		);

		IngredientListOverlay ingredientListOverlay = OverlayHelper.createIngredientListOverlay(
			ingredientManager,
			screenHelper,
			ingredientFilter,
			lookupHistory,
			filterTextSource,
			keyMappings,
			ingredientListConfig,
			clientConfig,
			toggleState,
			serverConnection,
			ingredientFilterConfig,
			textures,
			colorHelper
		);
		registration.setIngredientListOverlay(ingredientListOverlay);

		BookmarkList bookmarkList = new BookmarkList(recipeManager, focusFactory, ingredientManager, registryAccess, bookmarkConfig, clientConfig, guiHelper);
		bookmarkConfig.loadBookmarks(recipeManager, focusFactory, guiHelper, ingredientManager, registryAccess, bookmarkList);

		BookmarkOverlay bookmarkOverlay = OverlayHelper.createBookmarkOverlay(
			ingredientManager,
			screenHelper,
			bookmarkList,
			lookupHistory,
			keyMappings,
			bookmarkListConfig,
			ingredientFilterConfig,
			clientConfig,
			toggleState,
			serverConnection,
			textures,
			colorHelper
		);
		registration.setBookmarkOverlay(bookmarkOverlay);

		GuiEventHandler guiEventHandler = new GuiEventHandler(
			screenHelper,
			bookmarkOverlay,
			ingredientListOverlay
		);

		RecipesGui recipesGui = new RecipesGui(
			recipeManager,
			recipeTransferManager,
			ingredientManager,
			keyMappings,
			focusFactory,
			bookmarkList,
			lookupHistory,
			guiHelper
		);
		registration.setRecipesGui(recipesGui);

		CombinedRecipeFocusSource recipeFocusSource = new CombinedRecipeFocusSource(
			recipesGui,
			ingredientListOverlay,
			bookmarkOverlay,
			new GuiContainerWrapper(screenHelper)
		);

		List<ICharTypedHandler> charTypedHandlers = List.of(
			ingredientListOverlay
		);

		FocusUtil focusUtil = new FocusUtil(focusFactory, clientConfig, ingredientManager);

		UserInputRouter userInputRouter = new UserInputRouter(
			"JEIGlobal",
			new EditInputHandler(recipeFocusSource, toggleState, editModeConfig),
			ingredientListOverlay.createInputHandler(),
			bookmarkOverlay.createInputHandler(),
			new FocusInputHandler(recipeFocusSource, recipesGui, focusUtil, clientConfig, ingredientManager, toggleState, serverConnection),
			new BookmarkInputHandler(recipeFocusSource, bookmarkList, bookmarkOverlay, clientConfig, recipesGui),
			new GlobalInputHandler(toggleState),
			new GuiAreaInputHandler(screenHelper, recipesGui, focusFactory)
		);

		DragRouter dragRouter = new DragRouter(
			ingredientListOverlay.createDragHandler(),
			bookmarkOverlay.createDragHandler()
		);
		ClientInputHandler clientInputHandler = new ClientInputHandler(
			charTypedHandlers,
			new ChatLinkInputHandler(recipesGui, focusUtil, screenHelper, bookmarkList),
			userInputRouter,
			dragRouter,
			keyMappings,
			screenHelper
		);
		ResourceReloadHandler resourceReloadHandler = new ResourceReloadHandler(
			ingredientListOverlay,
			ingredientFilter
		);

		return new JeiEventHandlers(
			guiEventHandler,
			clientInputHandler,
			resourceReloadHandler
		);
	}

	/**
	 * Builds the search string cache for this exact ingredient set.
	 * <p>
	 * The key covers every ingredient uid plus the active language, so adding, removing or updating
	 * a mod, or switching language, invalidates it and the strings get derived again. Returns a
	 * cache that simply misses everything if the key cannot be computed.
	 */
	private static SearchStringCache createSearchStringCache(List<IListElementInfo<?>> ingredientList, IIngredientManager ingredientManager) {
		LoggedTimer timer = new LoggedTimer();
		timer.start("Loading search string cache");
		List<String> uids = new ArrayList<>(ingredientList.size());
		for (IListElementInfo<?> info : ingredientList) {
			try {
				ITypedIngredient<?> typedIngredient = info.getTypedIngredient();
				uids.add(getUidString(typedIngredient, ingredientManager));
			} catch (RuntimeException | LinkageError e) {
				LOGGER.debug("Failed to compute a search cache uid for an ingredient", e);
			}
		}
		String locale = Minecraft.getInstance().getLanguageManager().getSelected();
		SearchStringCache cache = new SearchStringCache(SearchStringCache.computeCacheKey(uids, locale));
		cache.load();
		timer.stop();
		return cache;
	}

	private static <T> String getUidString(ITypedIngredient<T> typedIngredient, IIngredientManager ingredientManager) {
		IIngredientHelper<T> ingredientHelper = ingredientManager.getIngredientHelper(typedIngredient.getType());
		return ingredientHelper.getUniqueId(typedIngredient.getIngredient(), UidContext.Ingredient).toString();
	}
}
