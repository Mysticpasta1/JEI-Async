package mezz.jei.library.load;

import com.google.common.base.Stopwatch;
import mezz.jei.api.IAsyncCompatiblePlugin;
import mezz.jei.api.IModPlugin;
import mezz.jei.common.config.DebugConfig;
import mezz.jei.core.QuantifiedIntegration.QuantifiedIntegration;
import mezz.jei.library.plugins.vanilla.VanillaPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class PluginCaller {
	private static final Logger LOGGER = LogManager.getLogger();


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
				.map(plugin -> QuantifiedIntegration.runAsync("jei-plugin-" + title + "-" + plugin.getPluginUid(), () -> {
					try {
						func.accept(plugin);
					} catch (Throwable e) {
						LOGGER.error("Async plugin {} failed during {}:", plugin.getPluginUid(), title, e);
						if (e instanceof RuntimeException runtimeException) {
							throw runtimeException;
						}
						if (e instanceof Error error) {
							throw error;
						}
						throw new RuntimeException(e);
					}
				}))
				.toList();

		// 2. Execute sync plugins
		for (IModPlugin plugin : syncPlugins) {
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

		List<IModPlugin> knownIncompatible = new ArrayList<>();
		List<IModPlugin> asyncPlugins = new ArrayList<>();
		for (IModPlugin plugin : plugins) {
			if (incompatiblePluginStore.isIncompatible(plugin, title)) {
				knownIncompatible.add(plugin);
			} else {
				asyncPlugins.add(plugin);
			}
		}

		List<IModPlugin> newlyFailed = Collections.synchronizedList(new ArrayList<>());

		if (!asyncPlugins.isEmpty()) {
			List<CompletableFuture<Void>> futures = asyncPlugins.stream()
				.map(plugin -> QuantifiedIntegration.runAsync("jei-plugin-fallback-" + title + "-" + plugin.getPluginUid(), () -> {
					ResourceLocation pluginUid = plugin.getPluginUid();
					try {
						func.accept(plugin);
					} catch (RuntimeException | LinkageError e) {
						if (plugin instanceof VanillaPlugin) {
							throw e;
						}
						LOGGER.warn("{} - plugin {} failed on background thread, will retry on main thread", title, pluginUid, e);
						incompatiblePluginStore.markIncompatible(plugin, title);
						newlyFailed.add(plugin);
					}
				}))
				.toList();

			try {
				CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.MINUTES);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				LOGGER.error("Interrupted while waiting for async fallback tasks during {}:", title, e);
			} catch (ExecutionException e) {
				LOGGER.error("Async fallback tasks failed during {}:", title, e.getCause());
			} catch (TimeoutException e) {
				LOGGER.error("Async fallback tasks timed out after 5 minutes during {}:", title);
			}
		}

		List<IModPlugin> mainThreadPlugins = new ArrayList<>(knownIncompatible.size() + newlyFailed.size());
		mainThreadPlugins.addAll(knownIncompatible);
		mainThreadPlugins.addAll(newlyFailed);

		if (!mainThreadPlugins.isEmpty()) {
			if (!knownIncompatible.isEmpty()) {
				LOGGER.info("Executing {} known-incompatible plugins synchronously for {}...", knownIncompatible.size(), title);
			}
			executeOnMainThreadBlocking(() -> {
				for (IModPlugin plugin : mainThreadPlugins) {
					ResourceLocation pluginLocation = plugin.getPluginUid();
					try {
						func.accept(plugin);
					} catch (RuntimeException | LinkageError e) {
						if (plugin instanceof VanillaPlugin) {
							throw e;
						}
						LOGGER.error("Plugin failed on main thread: {}", pluginLocation, e);
					}
				}
			});
		}

		stopwatch.stop();
		LOGGER.info("{} (with async fallback) took {}", title, stopwatch);
	}

	private static void executeOnMainThreadBlocking(Runnable task) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.isSameThread()) {
			task.run();
			return;
		}

		CompletableFuture<Void> future = new CompletableFuture<>();
		minecraft.execute(() -> {
			try {
				task.run();
				future.complete(null);
			} catch (Throwable e) {
				future.completeExceptionally(e);
			}
		});

		try {
			future.get();
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException re) {
				throw re;
			}
			if (cause instanceof LinkageError le) {
				throw le;
			}
			if (cause instanceof Error err) {
				throw err;
			}
			throw new RuntimeException(cause);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted while waiting for main thread execution", e);
		}
	}
}
