package mezz.jei.core.QuantifiedIntegration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.admany.quantified.api.QuantifiedAPI;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class QuantifiedIntegration {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String MOD_ID = QuantifiedIntegrationBuildInfo.MOD_ID;
	private static final String DISPLAY_NAME = QuantifiedIntegrationBuildInfo.DISPLAY_NAME;
	private static final String VERSION = QuantifiedIntegrationBuildInfo.VERSION;
	private static final ThreadLocal<Integer> ACTIVE_TASK_DEPTH = ThreadLocal.withInitial(() -> 0);
	private static final Executor SERVICE_EXECUTOR = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "JEI-Async-Service");
		t.setDaemon(true);
		return t;
	});
	private static volatile boolean registered;

	private QuantifiedIntegration() {
	}

	public static void register() {
		bindCurrentThread();
	}

	private static void bindCurrentThread() {
		try {
			boolean ok = QuantifiedAPI.register(MOD_ID, DISPLAY_NAME, VERSION);
			if (!ok) {
				throw new IllegalStateException("Quantified API rejected JEI registration");
			}
			if (!registered) {
				synchronized (QuantifiedIntegration.class) {
					if (!registered) {
						registered = true;
						LOGGER.info("JEI Async connected with Quantified API");
					}
				}
			}
		} catch (RuntimeException e) {
			throw new IllegalStateException("Quantified API is unavailable :[", e);
		}
	}

	public static Executor executor(String taskPrefix) {
		Objects.requireNonNull(taskPrefix, "taskPrefix");
		return command -> runAsync(taskPrefix, command);
	}

	public static CompletableFuture<Void> runAsync(String taskName, Runnable work) {
		Objects.requireNonNull(work, "work");
		return submit(taskName, () -> {
			work.run();
			return null;
		});
	}

	@SuppressWarnings("unchecked")
	public static <T> CompletableFuture<T> submit(String taskName, Supplier<T> work) {
		Objects.requireNonNull(taskName, "taskName");
		Objects.requireNonNull(work, "work");
		if (ACTIVE_TASK_DEPTH.get() > 0) {
			return runInline(work);
		}
		try {
			bindCurrentThread();
		} catch (RuntimeException e) {
			// API not available, run inline instead of blocking
			return runInline(work);
		}

		// Use SERVICE_EXECUTOR to perform the submission to Quantified API,
		// ensuring we never block the calling thread (especially the main thread).
		return CompletableFuture.supplyAsync(() -> {
			try {
				return QuantifiedAPI.submit(normalizeTaskName(taskName), () -> {
					enterTask();
					try {
						return work.get();
					} finally {
						exitTask();
					}
				}).join();
			} catch (RuntimeException e) {
				// Submit failed, run inline instead of blocking
				return runInline(work).join();
			}
		}, SERVICE_EXECUTOR);
	}

	public static <T> void forEach(String taskName, Collection<T> values, Consumer<T> consumer) {
		Objects.requireNonNull(values, "values");
		Objects.requireNonNull(consumer, "consumer");
		if (values.isEmpty()) {
			return;
		}

		List<CompletableFuture<Void>> futures = new ArrayList<>(values.size());
		int index = 0;
		for (T value : values) {
			int taskIndex = index++;
			futures.add(runAsync(taskName + "-" + taskIndex, () -> consumer.accept(value)));
		}
		joinAll(taskName, futures);
	}

	public static <T, R> List<R> mapOrdered(String taskName, Collection<T> values, Function<T, R> mapper) {
		Objects.requireNonNull(values, "values");
		Objects.requireNonNull(mapper, "mapper");
		if (values.isEmpty()) {
			return List.of();
		}

		List<T> orderedValues = List.copyOf(values);
		List<CompletableFuture<R>> futures = new ArrayList<>(orderedValues.size());
		for (int i = 0; i < orderedValues.size(); i++) {
			T value = orderedValues.get(i);
			int taskIndex = i;
			futures.add(submit(taskName + "-" + taskIndex, () -> mapper.apply(value)));
		}

		joinAll(taskName, futures);
		List<R> results = new ArrayList<>(futures.size());
		for (CompletableFuture<R> future : futures) {
			results.add(join(taskName, future));
		}
		return results;
	}

	public static <T> T getCached(String cacheName, String key, Supplier<T> loader, Duration ttl, long maximumSize, boolean persistence) {
		Objects.requireNonNull(cacheName, "cacheName");
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(loader, "loader");
		bindCurrentThread();
		try {
			return QuantifiedAPI.getCached(cacheName, key, loader, ttl, maximumSize, persistence);
		} catch (RuntimeException e) {
			Throwable cause = e.getCause();
			throw new IllegalStateException("Quantified API cache get failed for " + cacheName + "/" + key, cause == null ? e : cause);
		}
	}

	public static <T> void putCached(String cacheName, String key, T value, Duration ttl, long maximumSize, boolean persistence) {
		Objects.requireNonNull(cacheName, "cacheName");
		Objects.requireNonNull(key, "key");
		bindCurrentThread();
		try {
			QuantifiedAPI.putCached(cacheName, key, value, ttl, maximumSize, persistence);
		} catch (RuntimeException e) {
			Throwable cause = e.getCause();
			throw new IllegalStateException("Quantified API cache put failed for " + cacheName + "/" + key, cause == null ? e : cause);
		}
	}

	private static void joinAll(String taskName, Collection<? extends CompletableFuture<?>> futures) {
		try {
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
		} catch (CompletionException e) {
			throw new IllegalStateException("Quantified API task group failed: " + taskName, e.getCause());
		}
	}

	private static <T> T join(String taskName, CompletableFuture<T> future) {
		try {
			return future.join();
		} catch (CompletionException e) {
			throw new IllegalStateException("Quantified API task failed: " + taskName, e.getCause());
		}
	}

	private static String normalizeTaskName(String value) {
		String normalized = value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._:-]+", "-");
		if (normalized.isBlank()) {
			return "jei-task";
		}
		return normalized.length() > 96 ? normalized.substring(0, 96) : normalized;
	}

	private static void enterTask() {
		ACTIVE_TASK_DEPTH.set(ACTIVE_TASK_DEPTH.get() + 1);
	}

	private static void exitTask() {
		int depth = ACTIVE_TASK_DEPTH.get() - 1;
		if (depth <= 0) {
			ACTIVE_TASK_DEPTH.remove();
		} else {
			ACTIVE_TASK_DEPTH.set(depth);
		}
	}

	private static <T> CompletableFuture<T> runInline(Supplier<T> work) {
		enterTask();
		try {
			return CompletableFuture.completedFuture(work.get());
		} catch (Throwable throwable) {
			CompletableFuture<T> future = new CompletableFuture<>();
			future.completeExceptionally(throwable);
			return future;
		} finally {
			exitTask();
		}
	}
}
