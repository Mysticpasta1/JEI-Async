package mezz.jei.library.load;

import com.google.common.base.Stopwatch;
import mezz.jei.api.IAsyncCompatiblePlugin;
import mezz.jei.api.IModPlugin;
import mezz.jei.common.config.DebugConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

public class PluginCaller {
	private static final Logger LOGGER = LogManager.getLogger();

	@Nullable
	private static volatile ExecutorService executor;
	@Nullable
	private static volatile CompletableFuture<Void> pendingRuntimeFuture;

	private static ExecutorService getExecutor() {
		ExecutorService current = executor;
		if (current != null && !current.isShutdown()) {
			return current;
		}
		synchronized (PluginCaller.class) {
			current = executor;
			if (current == null || current.isShutdown()) {
				// A cached pool creates one thread per concurrently-submitted plugin, which in a
				// large pack means hundreds of threads and hundreds of megabytes of thread stacks.
				// Plugin registration is CPU-bound, so a pool sized to the CPU count loads just as
				// fast for a tiny fraction of the memory.
				int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
				ThreadPoolExecutor pool = new ThreadPoolExecutor(
					threads, threads,
					30L, TimeUnit.SECONDS,
					new LinkedBlockingQueue<>(),
					r -> {
						Thread thread = new Thread(r, "JEI Plugin Loader");
						// daemon so a stuck plugin can never hold the JVM open
						thread.setDaemon(true);
						return thread;
					}
				);
				// let the pool shrink back to zero once loading is done
				pool.allowCoreThreadTimeOut(true);
				executor = pool;
				current = pool;
			}
			return current;
		}
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
			callOnPluginsWithFallback(title, plugins, func, incompatiblePluginStore, mainThreadRunner);
		} else {
			callOnPlugins(title, plugins, func, mainThreadRunner, incompatiblePluginStore);
		}
	}

	public static void callOnPlugins(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func) {
		callOnPlugins(title, plugins, func, (Consumer<Runnable>) null, null);
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

	/**
	 * Non-blocking variant that dispatches async-compatible plugins to the executor
	 * and runs sync-only plugins on the calling thread.
	 * Unlike callOnPlugins, it does NOT block waiting for async plugin futures.
	 * Use this when calling from the main/render thread to avoid blocking ticks.
	 */
	public static void callOnPluginsNonBlocking(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func, @Nullable IncompatiblePluginStore store, @Nullable Function<IModPlugin, Consumer<Runnable>> mainThreadRunnerResolver) {
		callOnPluginsInternal(title, plugins, func, mainThreadRunnerResolver, store, false);
	}

	private static void callOnPluginsInternal(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func, @Nullable Function<IModPlugin, Consumer<Runnable>> mainThreadRunnerResolver, @Nullable IncompatiblePluginStore store, boolean blocking) {
		LOGGER.info("{}...", title);
		Stopwatch stopwatch = Stopwatch.createStarted();

		if (!DebugConfig.isAsyncLoadingEnabled()) {
			for (IModPlugin plugin : plugins) {
				runPlugin(plugin, func, title, null);
			}
			LOGGER.info("{} took {}", title, stopwatch);
			return;
		}

		List<IModPlugin> syncPlugins = new ArrayList<>();
		List<CompletableFuture<Void>> futures = new ArrayList<>();

		for (IModPlugin plugin : plugins) {
			if (plugin instanceof IAsyncCompatiblePlugin asyncPlugin && asyncPlugin.canExecuteAsync() && (store != null && !store.isIncompatible(plugin, title))) {
				futures.add(CompletableFuture.runAsync(() -> {
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
				}, getExecutor()));
			} else {
				syncPlugins.add(plugin);
			}
		}

		runSyncPlugins(title, syncPlugins, func, mainThreadRunnerResolver, store, blocking);

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
				// whenComplete (not thenRun) so a failure still releases the reference; otherwise
				// this static field pins the whole JeiRuntime for the rest of the game session.
				pendingRuntimeFuture = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
					.whenComplete((ignored, throwable) -> {
						pendingRuntimeFuture = null;
						if (throwable != null) {
							LOGGER.warn("One or more async plugins failed during {} (already handled)", title);
						} else {
							LOGGER.info("{} completed (async portion)", title);
						}
					});
			}
			stopwatch.stop();
			LOGGER.info("{} dispatched (sync done, async in background)", title);
		}
	}

	/**
	 * Runs the sync-only plugins for one loading phase.
	 * <p>
	 * Plugins that share a main-thread runner are dispatched as a single batch. The previous
	 * behaviour dispatched each plugin separately and blocked on it, which cost a full main-thread
	 * round trip per plugin per phase, so the loader spent most of its time parked instead of
	 * loading. Batching also means the main thread picks up all of them in one drain of its task
	 * queue, without any pacing or delay between them.
	 */
	private static void runSyncPlugins(
		String title,
		List<IModPlugin> syncPlugins,
		Consumer<IModPlugin> func,
		@Nullable Function<IModPlugin, Consumer<Runnable>> mainThreadRunnerResolver,
		@Nullable IncompatiblePluginStore store,
		boolean blocking
	) {
		if (syncPlugins.isEmpty()) {
			return;
		}

		if (mainThreadRunnerResolver == null) {
			for (IModPlugin plugin : syncPlugins) {
				runPlugin(plugin, func, title, store);
			}
			return;
		}

		// Group by runner identity so plugins sharing a runner go over in one dispatch.
		// IdentityHashMap because the runners are lambdas with no useful equals().
		Map<Consumer<Runnable>, List<IModPlugin>> batches = new IdentityHashMap<>();
		List<IModPlugin> directPlugins = null;
		for (IModPlugin plugin : syncPlugins) {
			Consumer<Runnable> pluginRunner = mainThreadRunnerResolver.apply(plugin);
			if (pluginRunner == null) {
				if (directPlugins == null) {
					directPlugins = new ArrayList<>();
				}
				directPlugins.add(plugin);
			} else {
				batches.computeIfAbsent(pluginRunner, k -> new ArrayList<>()).add(plugin);
			}
		}

		if (directPlugins != null) {
			for (IModPlugin plugin : directPlugins) {
				runPlugin(plugin, func, title, store);
			}
		}

		for (Map.Entry<Consumer<Runnable>, List<IModPlugin>> entry : batches.entrySet()) {
			dispatchBatch(title, entry.getKey(), entry.getValue(), func, store, blocking);
		}
	}

	private static void dispatchBatch(
		String title,
		Consumer<Runnable> mainThreadRunner,
		List<IModPlugin> batch,
		Consumer<IModPlugin> func,
		@Nullable IncompatiblePluginStore store,
		boolean blocking
	) {
		Runnable work = () -> {
			for (IModPlugin plugin : batch) {
				runPlugin(plugin, func, title, store);
			}
		};

		if (!blocking) {
			// Non-blocking callers want this off the current frame even when they are already on
			// the main thread, so always hand it to the runner.
			mainThreadRunner.accept(work);
			return;
		}

		// Already on the main thread: queueing and then waiting for ourselves is a guaranteed
		// deadlock, since only this thread can drain the queue. Just run it here.
		if (isOnMainThread()) {
			work.run();
			return;
		}

		CompletableFuture<Void> finished = new CompletableFuture<>();
		mainThreadRunner.accept(() -> {
			try {
				work.run();
			} finally {
				finished.complete(null);
			}
		});
		awaitMainThread(title, batch.size(), finished);
	}

	/**
	 * Waits for a batch that was handed to the main thread.
	 * <p>
	 * Deliberately unbounded. Neither how long the main thread takes to reach the queued batch nor
	 * how long the plugins in it take is something the loader can put a meaningful number on, and
	 * giving up early is worse than waiting: the batch stays queued and runs later regardless, so
	 * the loader would carry on reading registration state that the main thread is still writing.
	 * Loading is cancellable, which interrupts this thread, so this wait is never unrecoverable.
	 */
	private static void awaitMainThread(String title, int pluginCount, CompletableFuture<Void> finished) {
		try {
			finished.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new MainThreadDispatchException("Interrupted while running " + pluginCount + " plugins on the main thread for " + title, e);
		} catch (ExecutionException e) {
			// work.run() swallows plugin failures itself, so this only fires if the dispatch broke.
			throw new MainThreadDispatchException("Failed to run " + pluginCount + " plugins on the main thread for " + title, e.getCause());
		}
	}

	/**
	 * Thrown when a batch of sync-only plugins could not be run on the main thread.
	 * <p>
	 * Loading deliberately cannot continue past this. The queued batch is still sitting in the main
	 * thread's task queue and may run at any moment, writing to the very registration objects the
	 * loader would go on to read, so carrying on produces a half-built runtime that fails much later
	 * and much further from the cause.
	 */
	public static class MainThreadDispatchException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public MainThreadDispatchException(String message, @Nullable Throwable cause) {
			super(message, cause);
		}
	}

	private static boolean isOnMainThread() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft != null && minecraft.isSameThread();
	}

	private static void runPlugin(IModPlugin plugin, Consumer<IModPlugin> func, String title, @Nullable IncompatiblePluginStore store) {
		try {
			func.accept(plugin);
		} catch (Throwable e) {
			ResourceLocation pluginLocation = plugin.getPluginUid();
			LOGGER.error("Plugin failed: {}", pluginLocation, e);
			if (store != null) {
				store.markIncompatible(plugin, title);
			}
		}
	}

	/**
	 * Waits for async {@code onRuntimeAvailable} callbacks, bounded so that shutting down cannot
	 * hang the client. This is called from the main thread, and an async plugin is free to be
	 * dispatching work back to the main thread, so an unbounded wait here can deadlock outright.
	 */
	public static void waitForPendingRuntimeFuture() {
		CompletableFuture<Void> future = pendingRuntimeFuture;
		if (future == null || future.isDone()) {
			return;
		}
		try {
			future.get(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException e) {
			LOGGER.error("Pending runtime callbacks failed", e.getCause());
		} catch (TimeoutException e) {
			LOGGER.warn("Timed out waiting for pending runtime callbacks, continuing shutdown");
			future.cancel(true);
		} finally {
			pendingRuntimeFuture = null;
		}
	}

	public static void shutdown() {
		ExecutorService current;
		synchronized (PluginCaller.class) {
			current = executor;
			// clear the reference even if shutdown takes a while, so the pool and everything
			// its queued tasks capture can be collected
			executor = null;
		}
		pendingRuntimeFuture = null;
		if (current != null && !current.isShutdown()) {
			current.shutdownNow();
			try {
				if (!current.awaitTermination(5, TimeUnit.SECONDS)) {
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

		// Single pass to split known-incompatible plugins from the rest.
		List<IModPlugin> compatiblePlugins = new ArrayList<>(plugins.size());
		List<IModPlugin> incompatiblePlugins = null;
		for (IModPlugin plugin : plugins) {
			if (incompatiblePluginStore.isIncompatible(plugin, title)) {
				if (incompatiblePlugins == null) {
					incompatiblePlugins = new ArrayList<>();
				}
				incompatiblePlugins.add(plugin);
			} else {
				compatiblePlugins.add(plugin);
			}
		}

		// Execute compatible plugins (with main-thread dispatch for sync plugins)
		callOnPlugins(title, compatiblePlugins, func, mainThreadRunnerResolver, incompatiblePluginStore);

		// Re-execute compatible plugins that were marked incompatible during async execution.
		// These plugins failed during the async batch and need to be re-run synchronously
		// as a fallback so they still get a chance to register their content.
		List<IModPlugin> newlyIncompatible = null;
		for (IModPlugin plugin : compatiblePlugins) {
			if (incompatiblePluginStore.isIncompatible(plugin, title)) {
				if (newlyIncompatible == null) {
					newlyIncompatible = new ArrayList<>();
				}
				newlyIncompatible.add(plugin);
			}
		}
		if (newlyIncompatible != null) {
			LOGGER.warn("Re-executing {} failed plugins synchronously for {}...", newlyIncompatible.size(), title);
			runFallbackBatch(title, newlyIncompatible, func, mainThreadRunnerResolver);
		}

		// Execute incompatible plugins (resolve runner per-plugin)
		if (incompatiblePlugins != null) {
			LOGGER.info("Executing {} incompatible plugins synchronously for {}...", incompatiblePlugins.size(), title);
			runFallbackBatch(title, incompatiblePlugins, func, mainThreadRunnerResolver);
		}

		stopwatch.stop();
		LOGGER.info("{} (with async fallback) took {}", title, stopwatch);
	}

	/**
	 * Runs plugins on the main thread as a single batch per runner, never marking anything
	 * incompatible (they already are) and never leaving the caller parked indefinitely.
	 */
	private static void runFallbackBatch(
		String title,
		List<IModPlugin> plugins,
		Consumer<IModPlugin> func,
		@Nullable Function<IModPlugin, Consumer<Runnable>> mainThreadRunnerResolver
	) {
		if (mainThreadRunnerResolver == null) {
			for (IModPlugin plugin : plugins) {
				runPlugin(plugin, func, title, null);
			}
			return;
		}

		Map<Consumer<Runnable>, List<IModPlugin>> batches = new IdentityHashMap<>();
		for (IModPlugin plugin : plugins) {
			Consumer<Runnable> pluginRunner = mainThreadRunnerResolver.apply(plugin);
			if (pluginRunner == null) {
				runPlugin(plugin, func, title, null);
			} else {
				batches.computeIfAbsent(pluginRunner, k -> new ArrayList<>()).add(plugin);
			}
		}

		for (Map.Entry<Consumer<Runnable>, List<IModPlugin>> entry : batches.entrySet()) {
			dispatchBatch(title, entry.getKey(), entry.getValue(), func, null, true);
		}
	}
}
