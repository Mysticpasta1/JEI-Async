package mezz.jei.library.load;

import com.google.common.base.Stopwatch;
import mezz.jei.api.IAsyncCompatiblePlugin;
import mezz.jei.api.IModPlugin;
import mezz.jei.common.config.DebugConfig;
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
import java.util.function.Consumer;
import java.util.function.Function;

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
				thread.setDaemon(true);
				return thread;
			});
		}
		return executor;
	}

	public static void callPlugins(
		String title,
		List<IModPlugin> plugins,
		Consumer<IModPlugin> func,
		boolean useAsyncFallback,
		@Nullable IncompatiblePluginStore incompatiblePluginStore
	) {
		callPlugins(title, plugins, func, useAsyncFallback, incompatiblePluginStore, null);
	}

	public static void callPlugins(
		String title,
		List<IModPlugin> plugins,
		Consumer<IModPlugin> func,
		boolean useAsyncFallback,
		@Nullable IncompatiblePluginStore incompatiblePluginStore,
		@Nullable Consumer<Runnable> mainThreadRunner
	) {
		if (useAsyncFallback && incompatiblePluginStore != null) {
			callOnPluginsWithFallback(title, plugins, func, incompatiblePluginStore,
				mainThreadRunner != null ? p -> mainThreadRunner : p -> null
			);
		} else {
			callOnPlugins(title, plugins, func, mainThreadRunner != null ? p -> mainThreadRunner : p -> null, incompatiblePluginStore);
		}
	}

	public static void callOnPlugins(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func, @Nullable Consumer<Runnable> mainThreadRunner, @Nullable IncompatiblePluginStore store) {
		callOnPlugins(title, plugins, func,
			mainThreadRunner != null ? p -> mainThreadRunner : p -> null,
			store
		);
	}

	public static void callOnPlugins(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func, @Nullable Function<IModPlugin, Consumer<Runnable>> mainThreadRunnerResolver, @Nullable IncompatiblePluginStore store) {
		callOnPluginsInternal(title, plugins, func, mainThreadRunnerResolver, store, true);
	}

	public static void callOnPluginsNonBlocking(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func, @Nullable IncompatiblePluginStore store, @Nullable Function<IModPlugin, Consumer<Runnable>> mainThreadRunnerResolver) {
		callOnPluginsInternal(title, plugins, func, mainThreadRunnerResolver, store, false);
	}

	private static void callOnPluginsInternal(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func, @Nullable Function<IModPlugin, Consumer<Runnable>> mainThreadRunnerResolver, @Nullable IncompatiblePluginStore store, boolean blocking) {
		LOGGER.info("{}...", title);
		Stopwatch stopwatch = Stopwatch.createStarted();

		if (!DebugConfig.isAsyncLoadingEnabled()) {
			for (IModPlugin plugin : plugins) {
				ResourceLocation pluginLocation = plugin.getPluginUid();
				try {
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
			if (plugin instanceof IAsyncCompatiblePlugin asyncPlugin && asyncPlugin.canExecuteAsync() && (store != null && !store.isIncompatible(plugin, title))) {
				asyncPlugins.add(plugin);
			} else {
				syncPlugins.add(plugin);
			}
		}

		List<CompletableFuture<Void>> futures = asyncPlugins.parallelStream()
				.map(plugin -> CompletableFuture.runAsync(() -> {
					try {
						func.accept(plugin);
					} catch (net.minecraft.server.RunningOnDifferentThreadException e) {
						LOGGER.warn("Plugin {} ran on wrong thread during {} (not async-safe, will retry synchronously):", plugin.getPluginUid(), title);
						if (store != null) {
							store.markIncompatible(plugin, title);
						}
					} catch (Throwable e) {
						LOGGER.warn("Async plugin {} failed during {} (will retry synchronously):", plugin.getPluginUid(), title, e);
						if (store != null) {
							store.markIncompatible(plugin, title);
						}
					}
				}, getExecutor()))
				.toList();

		for (IModPlugin plugin : syncPlugins) {
			ResourceLocation pluginLocation = plugin.getPluginUid();
			Consumer<Runnable> pluginRunner = mainThreadRunnerResolver != null ? mainThreadRunnerResolver.apply(plugin) : null;
			if (pluginRunner != null) {
				if (blocking) {
					CompletableFuture<Void> syncFuture = new CompletableFuture<>();
					pluginRunner.accept(() -> {
						try {
							func.accept(plugin);
							syncFuture.complete(null);
						} catch (Throwable e) {
							LOGGER.error("Plugin failed: {}", pluginLocation, e);
							syncFuture.completeExceptionally(e);
						}
					});
					try {
						syncFuture.get();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						LOGGER.error("Interrupted while dispatching plugin {} to main thread:", pluginLocation, e);
					} catch (ExecutionException e) {
						LOGGER.error("Plugin {} failed on main thread:", pluginLocation, e.getCause());
						if (store != null) {
							store.markIncompatible(plugin, title);
						}
					}
				} else {
					pluginRunner.accept(() -> {
						try {
							func.accept(plugin);
						} catch (Throwable e) {
							LOGGER.error("Plugin failed: {}", pluginLocation, e);
							if (store != null) {
								store.markIncompatible(plugin, title);
							}
						}
					});
				}
			} else {
				try {
					func.accept(plugin);
				} catch (Throwable e) {
					LOGGER.error("Plugin failed: {}", pluginLocation, e);
					if (store != null) {
						store.markIncompatible(plugin, title);
					}
				}
			}
		}

		if (blocking) {
			try {
				CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
			} catch (java.util.concurrent.CompletionException e) {
				LOGGER.warn("One or more async plugins failed during {} (already handled)", title);
			}
			stopwatch.stop();
			LOGGER.info("{} took {}", title, stopwatch);
		} else {
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
	}

	public static void waitForPendingRuntimeFuture() {
		CompletableFuture<Void> future = pendingRuntimeFuture;
		if (future != null && !future.isDone()) {
			try {
				future.get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (ExecutionException e) {
				LOGGER.error("Pending runtime callbacks failed", e.getCause());
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
			IncompatiblePluginStore incompatiblePluginStore,
			@Nullable Consumer<Runnable> mainThreadRunner
	) {
		callOnPluginsWithFallback(title, plugins, func, incompatiblePluginStore,
			mainThreadRunner != null ? p -> mainThreadRunner : p -> null
		);
	}

	public static void callOnPluginsWithFallback(
			String title,
			List<IModPlugin> plugins,
			Consumer<IModPlugin> func,
			IncompatiblePluginStore incompatiblePluginStore,
			@Nullable Function<IModPlugin, Consumer<Runnable>> mainThreadRunnerResolver
	) {
		LOGGER.info("{} (with async fallback)...", title);
		Stopwatch stopwatch = Stopwatch.createStarted();

		// Separate into known-incompatible and async-capable plugins
		List<IModPlugin> incompatiblePlugins = new ArrayList<>();
		List<IModPlugin> compatiblePlugins = new ArrayList<>();
		for (IModPlugin plugin : plugins) {
			if (incompatiblePluginStore.isIncompatible(plugin, title)) {
				incompatiblePlugins.add(plugin);
			} else {
				compatiblePlugins.add(plugin);
			}
		}

		// Execute compatible plugins (with main-thread dispatch for sync plugins)
		callOnPlugins(title, compatiblePlugins, func, mainThreadRunnerResolver, incompatiblePluginStore);

		// Re-execute compatible plugins that were marked incompatible during async execution
		List<IModPlugin> newlyIncompatible = compatiblePlugins.stream()
				.filter(p -> incompatiblePluginStore.isIncompatible(p, title))
				.toList();
		if (!newlyIncompatible.isEmpty()) {
			LOGGER.warn("Re-executing {} failed plugins synchronously for {}...", newlyIncompatible.size(), title);
			for (IModPlugin plugin : newlyIncompatible) {
				ResourceLocation pluginLocation = plugin.getPluginUid();
				Consumer<Runnable> pluginRunner = mainThreadRunnerResolver != null ? mainThreadRunnerResolver.apply(plugin) : null;
				if (pluginRunner != null) {
					CompletableFuture<Void> fallbackFuture = new CompletableFuture<>();
					pluginRunner.accept(() -> {
						try {
							func.accept(plugin);
						} catch (Throwable e) {
							LOGGER.warn("Plugin {} failed again during synchronous fallback for {}:", pluginLocation, title, e);
						} finally {
							fallbackFuture.complete(null);
						}
					});
					try {
						fallbackFuture.get();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						LOGGER.error("Interrupted during fallback for plugin {}:", pluginLocation, e);
					} catch (ExecutionException e) {
						LOGGER.warn("Plugin {} failed again on main thread during fallback for {}:", pluginLocation, title, e.getCause());
					}
				} else {
					try {
						func.accept(plugin);
					} catch (Throwable e) {
						LOGGER.warn("Plugin {} failed again during synchronous fallback for {}:", pluginLocation, title, e);
					}
				}
			}
		}

		// Execute incompatible plugins (resolve runner per-plugin)
		if (!incompatiblePlugins.isEmpty()) {
			LOGGER.info("Executing {} incompatible plugins synchronously for {}...", incompatiblePlugins.size(), title);
			for (IModPlugin plugin : incompatiblePlugins) {
				ResourceLocation pluginLocation = plugin.getPluginUid();
				Consumer<Runnable> pluginRunner = mainThreadRunnerResolver != null ? mainThreadRunnerResolver.apply(plugin) : null;
				if (pluginRunner != null) {
					CompletableFuture<Void> syncFuture = new CompletableFuture<>();
					pluginRunner.accept(() -> {
						try {
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
						func.accept(plugin);
					} catch (Throwable e) {
						LOGGER.error("Plugin failed: {}", pluginLocation, e);
					}
				}
			}
		}

		LOGGER.info("{} took {}", title, stopwatch);
	}
}
