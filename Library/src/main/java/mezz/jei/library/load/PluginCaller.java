package mezz.jei.library.load;

import com.google.common.base.Stopwatch;
import mezz.jei.api.IAsyncCompatiblePlugin;
import mezz.jei.api.IModPlugin;
import mezz.jei.common.config.DebugConfig;
import mezz.jei.core.util.TimeUtil;
import mezz.jei.library.plugins.vanilla.VanillaPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
		thread.setDaemon(true);
		return thread;
	});

	public static void callOnPlugins(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func) {
		LOGGER.info("{}...", title);
		Stopwatch stopwatch = Stopwatch.createStarted();

		// If async loading is disabled, use simple synchronous execution
		if (!DebugConfig.isAsyncLoadingEnabled()) {
			callOnPluginsSync(title, plugins, func);
			LOGGER.info("{} took {}", title, TimeUtil.toHumanString(stopwatch.elapsed()));
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

		// Execute sync plugins on main thread (100% backward compatible)
		for (IModPlugin plugin : syncPlugins) {
			try {
				func.accept(plugin);
			} catch (RuntimeException | LinkageError e) {
				if (plugin instanceof VanillaPlugin) {
					throw e;
				}
				LOGGER.error("Caught an error from mod plugin: {} {}", plugin.getClass(), plugin.getPluginUid(), e);
			}
		}

		// Execute async-safe plugins on background thread (opt-in)
		if (!asyncPlugins.isEmpty()) {
			CompletableFuture<Void> asyncTask = CompletableFuture.runAsync(() -> {
				for (IModPlugin plugin : asyncPlugins) {
					try {
						func.accept(plugin);
					} catch (RuntimeException | LinkageError e) {
						LOGGER.error("Caught an error from async mod plugin: {} {}",
							plugin.getClass(), plugin.getPluginUid(), e);
					}
				}
			}, EXECUTOR);

			// Wait for async plugins to complete (with timeout to prevent hangs)
			try {
				asyncTask.get(30, TimeUnit.SECONDS);
			} catch (TimeoutException e) {
				LOGGER.error("Async plugin execution timed out after 30 seconds. Some plugins may not have completed registration.");
				asyncTask.cancel(true);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				LOGGER.error("Async plugin execution was interrupted", e);
			} catch (ExecutionException e) {
				LOGGER.error("Async plugin execution failed", e);
			}
		}

		LOGGER.info("{} took {}", title, TimeUtil.toHumanString(stopwatch.elapsed()));
	}

	/**
	 * Execute all plugins sequentially on the current thread.
	 * Used by the background loading pipeline where the caller is already on a background thread.
	 */
	public static void callOnPluginsSequential(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func) {
		LOGGER.info("{}...", title);
		Stopwatch stopwatch = Stopwatch.createStarted();
		callOnPluginsSync(title, plugins, func);
		LOGGER.info("{} took {}", title, TimeUtil.toHumanString(stopwatch.elapsed()));
	}

	/**
	 * Execute plugins sequentially with auto-fallback for failures.
	 * If a plugin fails on the background thread, it is retried on the main thread.
	 * Failed plugins are recorded in the IncompatiblePluginStore for future runs.
	 *
	 * Known-incompatible plugins are batched into a single main-thread roundtrip
	 * to avoid per-plugin synchronization overhead (~66ms per roundtrip).
	 */
	public static void callOnPluginsWithFallback(
		String title,
		List<IModPlugin> plugins,
		Consumer<IModPlugin> func,
		IncompatiblePluginStore store
	) {
		LOGGER.info("{}...", title);
		Stopwatch stopwatch = Stopwatch.createStarted();

		// Separate into known-incompatible and async-capable plugins
		List<IModPlugin> incompatiblePlugins = new ArrayList<>();
		List<IModPlugin> asyncPlugins = new ArrayList<>();
		for (IModPlugin plugin : plugins) {
			if (store.isIncompatible(plugin)) {
				incompatiblePlugins.add(plugin);
			} else {
				asyncPlugins.add(plugin);
			}
		}

		try (PluginCallerTimer timer = new PluginCallerTimer()) {
			// Execute async-capable plugins on background thread
			List<IModPlugin> newlyFailed = new ArrayList<>();
			for (IModPlugin plugin : asyncPlugins) {
				ResourceLocation pluginUid = plugin.getPluginUid();
				timer.begin(title, pluginUid);
				try {
					func.accept(plugin);
					timer.end();
				} catch (RuntimeException | LinkageError e) {
					timer.end();
					if (plugin instanceof VanillaPlugin) {
						throw e;
					}
					LOGGER.warn("{} - plugin {} failed on background thread, will retry on main thread", title, pluginUid, e);
					store.markIncompatible(plugin);
					newlyFailed.add(plugin);
				}
			}

			// Batch all incompatible plugins into a single main-thread roundtrip
			List<IModPlugin> mainThreadPlugins = new ArrayList<>(incompatiblePlugins.size() + newlyFailed.size());
			mainThreadPlugins.addAll(incompatiblePlugins);
			mainThreadPlugins.addAll(newlyFailed);

			if (!mainThreadPlugins.isEmpty()) {
				if (incompatiblePlugins.size() > 0) {
					LOGGER.info("{} - running {} known-incompatible plugins on main thread (batched)", title, incompatiblePlugins.size());
				}
				executeOnMainThreadBlocking(() -> {
					for (IModPlugin plugin : mainThreadPlugins) {
						ResourceLocation pluginUid = plugin.getPluginUid();
						timer.begin(title + " [main-thread]", pluginUid);
						try {
							func.accept(plugin);
						} catch (RuntimeException | LinkageError e) {
							if (plugin instanceof VanillaPlugin) {
								throw e;
							}
							LOGGER.error("Plugin {} failed on main thread: {} {}", pluginUid, plugin.getClass(), pluginUid, e);
						}
						timer.end();
					}
				});
			}
		}

		LOGGER.info("{} took {}", title, TimeUtil.toHumanString(stopwatch.elapsed()));
	}

	/**
	 * Execute a task on the main thread and block the current thread until it completes.
	 * Used by background loading to retry failed plugins on the main thread.
	 */
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
			} catch (RuntimeException | LinkageError e) {
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
			throw new RuntimeException(cause);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted while waiting for main thread execution", e);
		}
	}

	/**
	 * Execute all plugins synchronously on the current thread.
	 * Used when async loading is disabled or for plugins that don't support async execution.
	 */
	private static void callOnPluginsSync(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func) {
		try (PluginCallerTimer timer = new PluginCallerTimer()) {
			for (IModPlugin plugin : plugins) {
				try {
					ResourceLocation pluginUid = plugin.getPluginUid();
					timer.begin(title, pluginUid);
					func.accept(plugin);
					timer.end();
				} catch (RuntimeException | LinkageError e) {
					if (plugin instanceof VanillaPlugin) {
						throw e;
					}
					LOGGER.error("Caught an error from mod plugin: {} {}", plugin.getClass(), plugin.getPluginUid(), e);
				}
			}
		}
	}
}
