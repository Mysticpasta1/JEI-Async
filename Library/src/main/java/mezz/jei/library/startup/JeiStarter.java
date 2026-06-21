package mezz.jei.library.startup;

import com.google.common.collect.ImmutableSetMultimap;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.helpers.IColorHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IScreenHelper;
import mezz.jei.common.Internal;
import mezz.jei.common.config.ConfigManager;
import mezz.jei.common.config.DebugConfig;
import mezz.jei.common.config.IIngredientFilterConfig;
import mezz.jei.common.config.JeiClientConfigs;
import mezz.jei.common.config.file.ConfigSchemaBuilder;
import mezz.jei.common.config.file.FileWatcher;
import mezz.jei.common.config.file.IConfigSchemaBuilder;
import mezz.jei.common.platform.Services;
import mezz.jei.common.util.ErrorUtil;
import mezz.jei.common.util.RegistryUtil;
import mezz.jei.common.util.SafeIngredientUtil;
import mezz.jei.core.util.LoggedTimer;
import mezz.jei.library.color.ColorHelper;
import mezz.jei.library.config.ColorNameConfig;
import mezz.jei.library.config.EditModeConfig;
import mezz.jei.library.config.ModIdFormatConfig;
import mezz.jei.library.config.RecipeCategorySortingConfig;
import mezz.jei.library.focus.FocusFactory;
import mezz.jei.library.gui.helpers.ScreenHelper;
import mezz.jei.library.helpers.CodecHelper;
import mezz.jei.library.ingredients.itemStacks.TypedItemStack;
import mezz.jei.library.ingredients.subtypes.SubtypeManager;
import mezz.jei.library.load.IncompatiblePluginStore;
import mezz.jei.library.load.LoadingState;
import mezz.jei.library.load.PluginCaller;
import mezz.jei.library.load.PluginHelper;
import mezz.jei.library.load.PluginLoader;
import mezz.jei.library.load.registration.RuntimeRegistration;
import mezz.jei.library.plugins.jei.JeiInternalPlugin;
import mezz.jei.library.plugins.vanilla.VanillaPlugin;
import mezz.jei.library.recipes.DelegatingRecipeManager;
import mezz.jei.library.recipes.RecipeManager;
import mezz.jei.library.runtime.DelegatingJeiHelpers;
import mezz.jei.library.runtime.JeiHelpers;
import mezz.jei.library.runtime.JeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.RegistryAccess;
import net.minecraft.sounds.SoundEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class JeiStarter {
	private static final Logger LOGGER = LogManager.getLogger();
	@Nullable
	private static ExecutorService loadingExecutor;

	private static synchronized ExecutorService getLoadingExecutor() {
		if (loadingExecutor == null || loadingExecutor.isShutdown()) {
			loadingExecutor = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "JEI Background Loader");
				t.setDaemon(true);
				return t;
			});
		}
		return loadingExecutor;
	}

	private static final String EXPECTED_VERSION = "19.27.0.340-async-10";

	private final StartData data;
	private final List<IModPlugin> plugins;
	private final VanillaPlugin vanillaPlugin;
	private final ModIdFormatConfig modIdFormatConfig;
	private final ColorNameConfig colorNameConfig;
	private final RecipeCategorySortingConfig recipeCategorySortingConfig;
	@SuppressWarnings("FieldCanBeLocal")
	private final FileWatcher fileWatcher = new FileWatcher("JEI Config File Watcher");
	private final ConfigManager configManager;
	private final JeiClientConfigs jeiClientConfigs;
	private final IncompatiblePluginStore incompatiblePluginStore;
	private volatile boolean isStarting = false;
	private final AtomicReference<CompletableFuture<Void>> loadingFuture = new AtomicReference<>();
	private volatile boolean cancelled = false;
	private volatile boolean hidden = false;
	private volatile LoadingState loadingState = LoadingState.NOT_STARTED;
	private final DelegatingJeiHelpers delegatingJeiHelpers = new DelegatingJeiHelpers(null);
	private final DelegatingRecipeManager delegatingRecipeManager = new DelegatingRecipeManager();

	public JeiStarter(StartData data) {
		if (Services.PLATFORM.getModHelper().isModLoaded("emi")) {
			LOGGER.info("EMI is loaded, JEI GUI will be hidden but recipes will still be available.");
			this.hidden = true;
		} else {
			this.hidden = false;
		}

		ErrorUtil.checkNotEmpty(data.plugins(), "plugins");

		// Check for version mismatch which might indicate another JEI version is present
		String currentVersion = Services.PLATFORM.getModHelper().getModVersionForModId("jei");
		if (!currentVersion.equals("unknown") && !currentVersion.contains(EXPECTED_VERSION)) {
			LOGGER.fatal("JEI-Async version mismatch! Expected {}, but found {}. This usually means another version of JEI is installed.", EXPECTED_VERSION, currentVersion);
			throw new RuntimeException("JEI-Async incompatibility: Another version of JEI (" + currentVersion + ") was detected.");
		}

		this.data = data;
		this.plugins = data.plugins();
		this.vanillaPlugin = PluginHelper.getPluginWithClass(VanillaPlugin.class, plugins)
			.orElseThrow(() -> new IllegalStateException("vanilla plugin not found"));
		JeiInternalPlugin jeiInternalPlugin = PluginHelper.getPluginWithClass(JeiInternalPlugin.class, plugins)
			.orElse(null);
		PluginHelper.sortPlugins(plugins, vanillaPlugin, jeiInternalPlugin);

		Path configDir = Services.PLATFORM.getConfigHelper().createJeiConfigDir();

		this.configManager = new ConfigManager();

		IConfigSchemaBuilder debugFileBuilder = new ConfigSchemaBuilder(configDir.resolve("jei-debug.ini"), "jei.config.debug");
		DebugConfig.create(debugFileBuilder);
		debugFileBuilder.build().register(fileWatcher, configManager);

		IConfigSchemaBuilder modFileBuilder = new ConfigSchemaBuilder(configDir.resolve("jei-mod-id-format.ini"), "jei.config.modIdFormat");
		this.modIdFormatConfig = new ModIdFormatConfig(modFileBuilder);
		modFileBuilder.build().register(fileWatcher, configManager);

		IConfigSchemaBuilder colorFileBuilder = new ConfigSchemaBuilder(configDir.resolve("jei-colors.ini"), "jei.config.colors");
		this.colorNameConfig = new ColorNameConfig(colorFileBuilder);
		colorFileBuilder.build().register(fileWatcher, configManager);

		this.jeiClientConfigs = new JeiClientConfigs(configDir.resolve("jei-client.ini"));
		jeiClientConfigs.register(fileWatcher, configManager);
		Internal.setJeiClientConfigs(jeiClientConfigs);

		fileWatcher.start();

		this.recipeCategorySortingConfig = new RecipeCategorySortingConfig(configDir.resolve("recipe-category-sort-order.ini"));
		this.incompatiblePluginStore = new IncompatiblePluginStore(configDir);

		// Run onConfigManagerAvailable synchronously — it's a lightweight config handoff.
		for (IModPlugin plugin : plugins) {
			try {
				plugin.onConfigManagerAvailable(configManager);
			} catch (Throwable e) {
				LOGGER.error("Plugin failed during onConfigManagerAvailable: {}", plugin.getPluginUid(), e);
			}
		}
	}

	public void start() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			LOGGER.error("Failed to start JEI, there is no Minecraft client level.");
			return;
		}

		// Prevent concurrent loading
		if (isStarting) {
			LOGGER.warn("JEI is already starting. Ignoring duplicate start() call.");
			return;
		}

		// Main thread: capture RegistryAccess (requires minecraft.level)
		RegistryAccess registryAccess = minecraft.level.registryAccess();
		RegistryUtil.setRegistryAccess(registryAccess);

		if (hidden) {
			LOGGER.info("JEI is hidden because EMI is loaded. Loading recipes without GUI...");
			isStarting = true;
			try {
				doLoadingSync();
				Internal.getClientToggleState().setHiddenByEmi(true);
			} finally {
				isStarting = false;
			}
			return;
		}

		if (!DebugConfig.isAsyncLoadingEnabled()) {
			// Sync mode: run everything on main thread (unchanged behavior)
			isStarting = true;
			try {
				doLoadingSync();
			} finally {
				isStarting = false;
			}
			return;
		}

		// Async mode: launch background task
		isStarting = true;
		cancelled = false;
		loadingState = LoadingState.INITIALIZING;
		LOGGER.info("Starting JEI background loading...");

		CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
			try {
				doLoadingAsync();
			} catch (Exception e) {
				if (!cancelled) {
					LOGGER.error("JEI background loading failed catastrophically", e);
				}
			} finally {
				isStarting = false;
			}
		}, getLoadingExecutor());
		loadingFuture.set(future);
	}

	private void doLoadingSync() {
		LoggedTimer totalTime = new LoggedTimer();
		totalTime.start("Starting JEI");
		this.configManager.onJeiStarted();

		JeiRuntime jeiRuntime = buildRuntime(false, r -> Minecraft.getInstance().execute(r));

		PluginCaller.callPlugins("Sending Runtime", plugins, p -> p.onRuntimeAvailable(jeiRuntime), false, incompatiblePluginStore);
		Internal.setRuntime(jeiRuntime);

		totalTime.stop();
		playLoadCompleteSound();
	}

	private void doLoadingAsync() {
		LoggedTimer totalTime = new LoggedTimer();
		totalTime.start("Starting JEI (background)");
		Internal.setLoadingProgress("Initializing...");
		this.configManager.onJeiStarted();

		JeiRuntime jeiRuntime = buildRuntime(true, null);

		if (cancelled) {
			LOGGER.info("JEI background loading was cancelled");
			Internal.setLoadingProgress(null);
			return;
		}

		totalTime.stop();

		Minecraft.getInstance().execute(() -> {
			if (cancelled) {
				Internal.setLoadingProgress(null);
				return;
			}
			Internal.setRuntime(jeiRuntime);
			Internal.setLoadingProgress(null);
			LOGGER.info("JEI has finished background loading and is now available.");
			playLoadCompleteSound();
			PluginCaller.callOnPluginsNonBlocking("Sending Runtime", plugins, p -> p.onRuntimeAvailable(jeiRuntime), incompatiblePluginStore, p -> null);
		});
	}

	private static void playLoadCompleteSound() {
		try {
			Minecraft minecraft = Minecraft.getInstance();
			LOGGER.info("Playing JEI load complete sound");
			minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F));
		} catch (Exception e) {
			LOGGER.error("Failed to play load complete sound", e);
		}
	}

	private JeiRuntime buildRuntime(boolean useAsyncFallback) {
		return buildRuntime(useAsyncFallback, null);
	}

	private JeiRuntime buildRuntime(boolean useAsyncFallback, @Nullable Consumer<Runnable> mainThreadRunner) {
		loadingState = LoadingState.LOADING_SUBTYPES;
		if (!hidden) {
			Internal.setLoadingProgress("Loading subtypes...");
		}
		IColorHelper colorHelper = new ColorHelper(colorNameConfig);
		IIngredientFilterConfig ingredientFilterConfig = jeiClientConfigs.getIngredientFilterConfig();
		SubtypeManager subtypeManager = PluginLoader.registerSubtypes(data, useAsyncFallback, incompatiblePluginStore, Minecraft.getInstance()::execute);

		if (cancelled) {
			throw new CancelledException();
		}

		loadingState = LoadingState.LOADING_INGREDIENTS;
		if (!hidden) {
			Internal.setLoadingProgress("Loading ingredients...");
		}
		IIngredientManager ingredientManager = PluginLoader.registerIngredients(data, subtypeManager, colorHelper, ingredientFilterConfig, useAsyncFallback, incompatiblePluginStore, mainThreadRunner);

		if (cancelled) {
			throw new CancelledException();
		}

		FocusFactory focusFactory = new FocusFactory(ingredientManager);
		CodecHelper codecHelper = new CodecHelper(ingredientManager, focusFactory);

		Path configDir = Services.PLATFORM.getConfigHelper().createJeiConfigDir();
		RegistryAccess registryAccess = RegistryUtil.getRegistryAccess();
		EditModeConfig.FileSerializer editModeSerializer = new EditModeConfig.FileSerializer(
			configDir.resolve("blacklist.json"),
			registryAccess,
			codecHelper
		);
		EditModeConfig editModeConfig = new EditModeConfig(editModeSerializer, ingredientManager);

		ImmutableSetMultimap<String, String> modAliases = PluginLoader.registerModAliases(data, ingredientFilterConfig, useAsyncFallback, incompatiblePluginStore);
		JeiHelpers jeiHelpers = PluginLoader.createJeiHelpers(modAliases, modIdFormatConfig, colorHelper, editModeConfig, focusFactory, codecHelper, ingredientManager, subtypeManager);
		delegatingJeiHelpers.setDelegate(jeiHelpers);

		if (cancelled) {
			throw new CancelledException();
		}

		loadingState = LoadingState.LOADING_CATEGORIES;
		if (!hidden) {
			Internal.setLoadingProgress("Loading categories & recipes...");
		}
		RecipeManager recipeManager = PluginLoader.createRecipeManager(
			plugins,
			vanillaPlugin,
			recipeCategorySortingConfig,
			delegatingJeiHelpers,
			ingredientManager,
			useAsyncFallback,
			incompatiblePluginStore,
			null
		);
		delegatingRecipeManager.setDelegate(recipeManager);

		if (cancelled) {
			throw new CancelledException();
		}

		loadingState = LoadingState.BUILDING_RUNTIME;
		if (!hidden) {
			Internal.setLoadingProgress("Building runtime...");
		}
		IRecipeTransferManager recipeTransferManager = PluginLoader.createRecipeTransferManager(
			vanillaPlugin,
			plugins,
			delegatingJeiHelpers,
			data.serverConnection(),
			useAsyncFallback,
			incompatiblePluginStore,
			mainThreadRunner
		);

		LoggedTimer timer = new LoggedTimer();
		timer.start("Building runtime");
		IScreenHelper screenHelper = PluginLoader.createGuiScreenHelper(plugins, delegatingJeiHelpers, ingredientManager, useAsyncFallback, incompatiblePluginStore, mainThreadRunner);

		RuntimeRegistration runtimeRegistration = new RuntimeRegistration(
			recipeManager,
			delegatingJeiHelpers,
			editModeConfig,
			ingredientManager,
			recipeTransferManager,
			screenHelper
		);

		PluginCaller.callPlugins("Registering Runtime", plugins, p -> p.registerRuntime(runtimeRegistration), useAsyncFallback, incompatiblePluginStore, mainThreadRunner);

		JeiRuntime jeiRuntime = new JeiRuntime(
			recipeManager,
			ingredientManager,
			Internal.getKeyMappings(),
			delegatingJeiHelpers,
			screenHelper,
			recipeTransferManager,
			editModeConfig,
			runtimeRegistration.getIngredientListOverlay(),
			runtimeRegistration.getBookmarkOverlay(),
			runtimeRegistration.getRecipesGui(),
			runtimeRegistration.getIngredientFilter(),
			configManager
		);
		timer.stop();

		loadingState = LoadingState.COMPLETE;
		return jeiRuntime;
	}

	private static class CancelledException extends RuntimeException {
		CancelledException() {
			super("JEI loading was cancelled");
		}
	}

	public boolean isStarting() {
		return isStarting;
	}

	public void stop() {
		LOGGER.info("Stopping JEI");
		cancelled = true;
		loadingState = LoadingState.NOT_STARTED;
		isStarting = false;
		Internal.setLoadingProgress(null);

		CompletableFuture<Void> future = loadingFuture.getAndSet(null);
		if (future != null && !future.isDone()) {
			future.cancel(true);
			LOGGER.info("Cancelled JEI background loading");
		}

		PluginCaller.waitForPendingRuntimeFuture();

		List<IModPlugin> plugins = data.plugins();
		PluginCaller.callPlugins("Sending Runtime Unavailable", plugins, IModPlugin::onRuntimeUnavailable, false, incompatiblePluginStore);

		Internal.getOptionalJeiRuntime().ifPresent(runtime -> {
			try {
				((AutoCloseable) runtime.getIngredientFilter()).close();
			} catch (Exception e) {
				LOGGER.error("Failed to close IngredientFilter", e);
			}
			try {
				IScreenHelper screenHelper = runtime.getScreenHelper();
				if (screenHelper instanceof ScreenHelper sh) {
					sh.clear();
				}
			} catch (Exception e) {
				LOGGER.error("Failed to clear ScreenHelper", e);
			}
		});

		Internal.setRuntime(null);
		RegistryUtil.setRegistryAccess(null);

		delegatingJeiHelpers.setDelegate(null);
		delegatingRecipeManager.setDelegate(null);

		synchronized (JeiStarter.class) {
			if (loadingExecutor != null && !loadingExecutor.isShutdown()) {
				loadingExecutor.shutdownNow();
			}
		}
		PluginCaller.shutdown();

		SafeIngredientUtil.clearCrashingCache();
		TypedItemStack.clearCache();
	}

	public LoadingState getLoadingState() {
		return loadingState;
	}
}
