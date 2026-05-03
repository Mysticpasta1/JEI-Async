package mezz.jei.library.load;

import com.google.common.base.Stopwatch;
import mezz.jei.api.IAsyncCompatiblePlugin;
import mezz.jei.api.IModPlugin;
import mezz.jei.common.config.DebugConfig;
import mezz.jei.library.plugins.vanilla.VanillaPlugin;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class PluginCaller {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
		Thread thread = new Thread(r);
		thread.setName("JEI Plugin Loader");
		return thread;
	});

	public static void callPluginsAsync(
		String title,
		List<IModPlugin> plugins,
		Consumer<IModPlugin> func,
		boolean useAsyncFallback,
		@Nullable IncompatiblePluginStore incompatiblePluginStore
	) {
		if (useAsyncFallback && incompatiblePluginStore != null) {
			callOnPluginsWithFallback(title, plugins, func, incompatiblePluginStore);
		} else {
			callOnPlugins(title, plugins, func);
		}
	}

	public static void callPlugins(
		String title,
		List<IModPlugin> plugins,
		Consumer<IModPlugin> func,
		boolean useAsyncFallback,
		@Nullable IncompatiblePluginStore incompatiblePluginStore
	) {
		callPluginsAsync(title, plugins, func, useAsyncFallback, incompatiblePluginStore);
	}

	public static void callOnPlugins(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func) {
		LOGGER.info("{}...", title);
		Stopwatch stopwatch = Stopwatch.createStarted();

		// If async loading is disabled, use simple synchronous execution
		if (!DebugConfig.isAsyncLoadingEnabled()) {
			for (IModPlugin plugin : plugins) {
				ResourceLocation pluginLocation = plugin.getPluginUid();
				try {
					if (plugin instanceof VanillaPlugin) {
						LOGGER.info("Calling VanillaPlugin...");
					}
					func.accept(plugin);
				} catch (Throwable e) {
					LOGGER.error("Plugin failed: {}", pluginLocation, e);
				}
			}
			LOGGER.info("{} took {}", title, stopwatch);
			return;
		}

		// Separate plugins into async-safe and sync-only
		List<IModPlugin> syncPlugins = new ArrayList<>();
		List<IModPlugin> asyncPlugins = new ArrayList<>();

		for (IModPlugin plugin : plugins) {
			if (plugin instanceof IAsyncCompatiblePlugin asyncPlugin && asyncPlugin.canExecuteAsync()) {
				asyncPlugins.add(plugin);
			} else {
				syncPlugins.add(plugin);
			}
		}

		// 1. Start all async plugins
		List<CompletableFuture<Void>> futures = asyncPlugins.stream()
				.map(plugin -> CompletableFuture.runAsync(() -> {
					try {
						func.accept(plugin);
					} catch (Throwable e) {
						LOGGER.error("Async plugin {} failed during {}:", plugin.getPluginUid(), title, e);
						throw e;
					}
				}, EXECUTOR))
				.toList();

		// 2. Execute sync plugins
		for (IModPlugin plugin : plugins) {
			ResourceLocation pluginLocation = plugin.getPluginUid();
			try {
				if (plugin instanceof VanillaPlugin) {
					LOGGER.info("Calling VanillaPlugin...");
				}
				func.accept(plugin);
			} catch (Throwable e) {
				LOGGER.error("Plugin failed: {}", pluginLocation, e);
			}
		}

		// 3. Wait for all async plugins to finish
		try {
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.MINUTES);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.error("Interrupted while waiting for async tasks during {}:", title, e);
		} catch (ExecutionException e) {
			LOGGER.error("Async tasks failed during {}:", title, e.getCause());
		} catch (TimeoutException e) {
			LOGGER.error("Async tasks timed out after 5 minutes during {}:", title);
		}

		stopwatch.stop();
		LOGGER.info("{} took {}", title, stopwatch);
	}

	public static void callOnPluginsWithFallback(
			String title,
			List<IModPlugin> plugins,
			Consumer<IModPlugin> func,
			IncompatiblePluginStore incompatiblePluginStore
	) {
		LOGGER.info("{} (with async fallback)...", title);
		Stopwatch stopwatch = Stopwatch.createStarted();

		// Filter out known incompatible plugins
		List<IModPlugin> compatiblePlugins = plugins.stream()
				.filter(p -> !incompatiblePluginStore.isIncompatible(p, title))
				.toList();

		List<IModPlugin> incompatiblePlugins = plugins.stream()
				.filter(p -> incompatiblePluginStore.isIncompatible(p, title))
				.toList();

		// Execute compatible plugins
		callOnPlugins(title, compatiblePlugins, func);

		// Execute incompatible plugins synchronously
		if (!incompatiblePlugins.isEmpty()) {
			LOGGER.info("Executing {} incompatible plugins synchronously for {}...", incompatiblePlugins.size(), title);
			for (IModPlugin plugin : plugins) {
				ResourceLocation pluginLocation = plugin.getPluginUid();
				try {
					if (plugin instanceof VanillaPlugin) {
						LOGGER.info("Calling VanillaPlugin...");
					}
					func.accept(plugin);
				} catch (Throwable e) {
					LOGGER.error("Plugin failed: {}", pluginLocation, e);
				}
			}
		}

		stopwatch.stop();
		LOGGER.info("{} (with async fallback) took {}", title, stopwatch);
	}
}
