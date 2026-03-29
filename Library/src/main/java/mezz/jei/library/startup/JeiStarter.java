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
import mezz.jei.core.util.LoggedTimer;
import mezz.jei.library.color.ColorHelper;
import mezz.jei.library.config.ColorNameConfig;
import mezz.jei.library.config.EditModeConfig;
import mezz.jei.library.config.ModIdFormatConfig;
import mezz.jei.library.config.RecipeCategorySortingConfig;
import mezz.jei.library.focus.FocusFactory;
import mezz.jei.library.helpers.CodecHelper;
import mezz.jei.library.ingredients.subtypes.SubtypeManager;
import mezz.jei.library.load.IncompatiblePluginStore;
import mezz.jei.library.load.LoadingState;
import mezz.jei.library.load.PluginCaller;
import mezz.jei.library.load.PluginHelper;
import mezz.jei.library.load.PluginLoader;
import mezz.jei.library.load.registration.RuntimeRegistration;
import mezz.jei.library.plugins.jei.JeiInternalPlugin;
import mezz.jei.library.plugins.vanilla.VanillaPlugin;
import mezz.jei.library.recipes.RecipeManager;
import mezz.jei.library.runtime.JeiHelpers;
import mezz.jei.library.runtime.JeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public final class JeiStarter {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final ExecutorService LOADING_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "JEI Background Loader");
		t.setDaemon(true);
		return t;
	});

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

	private final AtomicReference<CompletableFuture<Void>> loadingFuture = new AtomicReference<>();
	private volatile boolean cancelled = false;
	private volatile LoadingState loadingState = LoadingState.NOT_STARTED;

	public JeiStarter(StartData data) {
		ErrorUtil.checkNotEmpty(data.plugins(), "plugins");
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

		PluginCaller.callOnPlugins("Sending ConfigManager", plugins, p -> p.onConfigManagerAvailable(configManager));
	}

	public void start() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			LOGGER.error("Failed to start JEI, there is no Minecraft client level.");
			return;
		}

		// Main thread: capture RegistryAccess (requires minecraft.level)
		RegistryAccess registryAccess = minecraft.level.registryAccess();
		RegistryUtil.setRegistryAccess(registryAccess);

		if (!DebugConfig.isAsyncLoadingEnabled()) {
			// Sync mode: run everything on main thread (unchanged behavior)
			doLoadingSync();
			return;
		}

		// Async mode: launch background task
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
			}
		}, LOADING_EXECUTOR);
		loadingFuture.set(future);
	}

	/**
	 * Synchronous loading path - runs everything on the main thread.
	 * This is the original behavior, used when async loading is disabled.
	 */
	private void doLoadingSync() {
		LoggedTimer totalTime = new LoggedTimer();
		totalTime.start("Starting JEI");
		this.configManager.onJeiStarted();

		JeiRuntime jeiRuntime = buildRuntime(false);

		PluginCaller.callOnPlugins("Sending Runtime", plugins, p -> p.onRuntimeAvailable(jeiRuntime));
		Internal.setRuntime(jeiRuntime);

		totalTime.stop();
	}

	/**
	 * Asynchronous loading path - runs on background thread.
	 * On completion, schedules runtime finalization on the main thread.
	 */
	private void doLoadingAsync() {
		LoggedTimer totalTime = new LoggedTimer();
		totalTime.start("Starting JEI (background)");
		this.configManager.onJeiStarted();

		JeiRuntime jeiRuntime = buildRuntime(true);

		if (cancelled) {
			LOGGER.info("JEI background loading was cancelled");
			return;
		}

		totalTime.stop();

		// Schedule runtime finalization on main thread
		Minecraft.getInstance().execute(() -> {
			if (cancelled) {
				return;
			}
			PluginCaller.callOnPlugins("Sending Runtime", plugins, p -> p.onRuntimeAvailable(jeiRuntime));
			Internal.setRuntime(jeiRuntime);
			LOGGER.info("JEI has finished background loading and is now available.");
		});
	}

	/**
	 * Build the JEI runtime. This is the main body of work.
	 * Can run on either the main thread (sync mode) or background thread (async mode).
	 *
	 * @param useAsyncFallback if true, use per-plugin fallback for error recovery
	 */
	private JeiRuntime buildRuntime(boolean useAsyncFallback) {
		loadingState = LoadingState.LOADING_SUBTYPES;
		IColorHelper colorHelper = new ColorHelper(colorNameConfig);
		IIngredientFilterConfig ingredientFilterConfig = jeiClientConfigs.getIngredientFilterConfig();
		SubtypeManager subtypeManager = PluginLoader.registerSubtypes(data, useAsyncFallback, incompatiblePluginStore);

		if (cancelled) {
			throw new CancelledException();
		}

		loadingState = LoadingState.LOADING_INGREDIENTS;
		IIngredientManager ingredientManager = PluginLoader.registerIngredients(data, subtypeManager, colorHelper, ingredientFilterConfig, useAsyncFallback, incompatiblePluginStore);

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

		if (cancelled) {
			throw new CancelledException();
		}

		loadingState = LoadingState.LOADING_CATEGORIES;
		RecipeManager recipeManager = PluginLoader.createRecipeManager(
			plugins,
			vanillaPlugin,
			recipeCategorySortingConfig,
			jeiHelpers,
			ingredientManager,
			useAsyncFallback,
			incompatiblePluginStore
		);

		if (cancelled) {
			throw new CancelledException();
		}

		loadingState = LoadingState.BUILDING_RUNTIME;
		IRecipeTransferManager recipeTransferManager = PluginLoader.createRecipeTransferManager(
			vanillaPlugin,
			plugins,
			jeiHelpers,
			data.serverConnection(),
			useAsyncFallback,
			incompatiblePluginStore
		);

		LoggedTimer timer = new LoggedTimer();
		timer.start("Building runtime");
		IScreenHelper screenHelper = PluginLoader.createGuiScreenHelper(plugins, jeiHelpers, ingredientManager, useAsyncFallback, incompatiblePluginStore);

		RuntimeRegistration runtimeRegistration = new RuntimeRegistration(
			recipeManager,
			jeiHelpers,
			editModeConfig,
			ingredientManager,
			recipeTransferManager,
			screenHelper
		);

		if (useAsyncFallback) {
			PluginCaller.callOnPluginsWithFallback("Registering Runtime", plugins, p -> p.registerRuntime(runtimeRegistration), incompatiblePluginStore);
		} else {
			PluginCaller.callOnPlugins("Registering Runtime", plugins, p -> p.registerRuntime(runtimeRegistration));
		}

		JeiRuntime jeiRuntime = new JeiRuntime(
			recipeManager,
			ingredientManager,
			Internal.getKeyMappings(),
			jeiHelpers,
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

	public void stop() {
		LOGGER.info("Stopping JEI");
		cancelled = true;
		loadingState = LoadingState.NOT_STARTED;

		CompletableFuture<Void> future = loadingFuture.getAndSet(null);
		if (future != null && !future.isDone()) {
			future.cancel(true);
			LOGGER.info("Cancelled JEI background loading");
		}

		List<IModPlugin> plugins = data.plugins();
		PluginCaller.callOnPlugins("Sending Runtime Unavailable", plugins, IModPlugin::onRuntimeUnavailable);
		Internal.setRuntime(null);
		RegistryUtil.setRegistryAccess(null);
	}

	public LoadingState getLoadingState() {
		return loadingState;
	}

	private static class CancelledException extends RuntimeException {
		CancelledException() {
			super("JEI loading was cancelled");
		}
	}
}
