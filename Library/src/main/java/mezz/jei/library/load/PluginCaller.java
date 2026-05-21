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
	@Nullable
	private static ExecutorService executor;
	@Nullable
	private static volatile CompletableFuture<Void> pendingRuntimeFuture;

	private static synchronized ExecutorService getExecutor() {
		if (executor == null || executor.isShutdown()) {
			executor = Executors.newCachedThreadPool(r -> {
				Thread thread = new Thread(r);
				thread.setName("JEI Plugin Loader");
				return thread;
			});
		}
		return executor;
	}

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
		callOnPlugins(title, plugins, func, null);
	}

	public static void callOnPlugins(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func, @Nullable Consumer<Runnable> mainThreadRunner) {
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
				}, getExecutor()))
				.toList();

		// 2. Execute sync-only plugins (skip async plugins to avoid double execution)
		//    If mainThreadRunner is provided, dispatch sync plugins to the main thread
		//    to ensure any client-only state access (Minecraft.getInstance(), etc.) is safe.
		for (IModPlugin plugin : syncPlugins) {
			ResourceLocation pluginLocation = plugin.getPluginUid();
			if (mainThreadRunner != null) {
				CompletableFuture<Void> syncFuture = new CompletableFuture<>();
				mainThreadRunner.accept(() -> {
					try {
						if (plugin instanceof VanillaPlugin) {
							LOGGER.info("Calling VanillaPlugin...");
						}
						func.accept(plugin);
					} catch (Throwable e) {
						LOGGER.error("Plugin failed: {}", pluginLocation, e);
					} finally {
						syncFuture.complete(null);
					}
				});
				try {
					syncFuture.get();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					LOGGER.error("Interrupted while dispatching sync plugin {} to main thread:", pluginLocation, e);
				} catch (ExecutionException e) {
					LOGGER.error("Sync plugin {} failed on main thread:", pluginLocation, e.getCause());
				}
			} else {
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

	/**
	 * Non-blocking variant that dispatches async-compatible plugins to the executor
	 * and runs sync-only plugins on the calling thread.
	 * Unlike callOnPlugins, it does NOT block waiting for async plugin futures.
	 * Use this when calling from the main/render thread to avoid blocking ticks.
	 */
	public static void callOnPluginsNonBlocking(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func) {
		LOGGER.info("{}...", title);
		Stopwatch stopwatch = Stopwatch.createStarted();

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

		List<IModPlugin> syncPlugins = new ArrayList<>();
		List<IModPlugin> asyncPlugins = new ArrayList<>();

		for (IModPlugin plugin : plugins) {
			if (plugin instanceof IAsyncCompatiblePlugin asyncPlugin && asyncPlugin.canExecuteAsync()) {
				asyncPlugins.add(plugin);
			} else {
				syncPlugins.add(plugin);
			}
		}

		// 1. Dispatch async plugins to executor
		List<CompletableFuture<Void>> futures = asyncPlugins.stream()
				.map(plugin -> CompletableFuture.runAsync(() -> {
					try {
						func.accept(plugin);
					} catch (Throwable e) {
						LOGGER.error("Async plugin {} failed during {}:", plugin.getPluginUid(), title, e);
					}
				}, getExecutor()))
				.toList();

		// 2. Execute sync-only plugins on the calling thread (may be main thread)
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

		// 3. Fire-and-forget async futures: do NOT block the calling thread
		if (!futures.isEmpty()) {
			pendingRuntimeFuture = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
					.thenRun(() -> {
						pendingRuntimeFuture = null;
						stopwatch.stop();
						LOGGER.info("{} completed (async portion)", title);
					});
		}

		stopwatch.stop();
		LOGGER.info("{} dispatched (sync done, async in background)", title);
	}

	/**
	 * Waits for any pending async plugin callbacks (onRuntimeAvailable) to complete.
	 * Must be called before onRuntimeUnavailable to ensure proper ordering.
	 */
	public static void waitForPendingRuntimeFuture() {
		CompletableFuture<Void> future = pendingRuntimeFuture;
		if (future != null && !future.isDone()) {
			try {
				future.get(30, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (ExecutionException e) {
				LOGGER.error("Pending runtime callbacks failed", e.getCause());
			} catch (TimeoutException e) {
				LOGGER.error("Pending runtime callbacks timed out");
			}
		}
	}

	public static synchronized void shutdown() {
		if (executor != null && !executor.isShutdown()) {
			executor.shutdownNow();
			try {
				if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
					LOGGER.warn("Plugin caller executor did not terminate in time");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	public static void callOnPluginsWithFallback(
			String title,
			List<IModPlugin> plugins,
			Consumer<IModPlugin> func,
			IncompatiblePluginStore incompatiblePluginStore
	) {
		callOnPluginsWithFallback(title, plugins, func, incompatiblePluginStore, null);
	}

	public static void callOnPluginsWithFallback(
			String title,
			List<IModPlugin> plugins,
			Consumer<IModPlugin> func,
			IncompatiblePluginStore incompatiblePluginStore,
			@Nullable Consumer<Runnable> mainThreadRunner
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

		// Execute compatible plugins (with main-thread dispatch for sync plugins)
		callOnPlugins(title, compatiblePlugins, func, mainThreadRunner);

		// Execute incompatible plugins (also dispatch to main thread if runner provided)
		if (!incompatiblePlugins.isEmpty()) {
			LOGGER.info("Executing {} incompatible plugins synchronously for {}...", incompatiblePlugins.size(), title);
			for (IModPlugin plugin : incompatiblePlugins) {
				ResourceLocation pluginLocation = plugin.getPluginUid();
				if (mainThreadRunner != null) {
					CompletableFuture<Void> syncFuture = new CompletableFuture<>();
					mainThreadRunner.accept(() -> {
						try {
							if (plugin instanceof VanillaPlugin) {
								LOGGER.info("Calling VanillaPlugin...");
							}
							func.accept(plugin);
						} catch (Throwable e) {
							LOGGER.error("Plugin failed: {}", pluginLocation, e);
						} finally {
							syncFuture.complete(null);
						}
					});
					try {
						syncFuture.get();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						LOGGER.error("Interrupted while dispatching incompatible plugin {} to main thread:", pluginLocation, e);
					} catch (ExecutionException e) {
						LOGGER.error("Incompatible plugin {} failed on main thread:", pluginLocation, e.getCause());
					}
				} else {
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
		}

		stopwatch.stop();
		LOGGER.info("{} (with async fallback) took {}", title, stopwatch);
	}
}
