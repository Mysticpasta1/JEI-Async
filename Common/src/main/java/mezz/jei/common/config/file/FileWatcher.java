package mezz.jei.common.config.file;

import mezz.jei.core.QuantifiedIntegration.QuantifiedIntegration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class FileWatcher {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

	private final String taskName;
	private final Map<Path, WatchedFile> callbacks = new ConcurrentHashMap<>();
	private final AtomicBoolean running = new AtomicBoolean(false);
	private volatile CompletableFuture<?> currentPoll;

	public FileWatcher(String threadName) {
		this.taskName = normalizeTaskName(threadName);
	}

	/**
	 * @param path     a config file to watch
	 * @param callback a callback to call when the file changes.
	 */
	public void addCallback(Path path, Runnable callback) {
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(callback, "callback");
		Path normalizedPath = path.toAbsolutePath().normalize();
		callbacks.put(normalizedPath, new WatchedFile(callback, readModifiedTime(normalizedPath)));
	}

	public void start() {
		if (running.compareAndSet(false, true)) {
			schedulePoll(Duration.ZERO);
		}
	}

	public void stop() {
		running.set(false);
		CompletableFuture<?> poll = currentPoll;
		if (poll != null) {
			poll.cancel(false);
		}
	}

	private void schedulePoll(Duration delay) {
		if (!running.get()) {
			return;
		}
		CompletableFuture<Void> poll = new CompletableFuture<>();
		currentPoll = poll;
		Executor qapiDelayedExecutor = CompletableFuture.delayedExecutor(
			Math.max(0L, delay.toMillis()),
			TimeUnit.MILLISECONDS,
			QuantifiedIntegration.executor("jei-file-watch-delay")
		);
		qapiDelayedExecutor.execute(() -> {
			if (!running.get() || poll.isCancelled()) {
				return;
			}
			try {
				pollOnce();
				poll.complete(null);
			} catch (Throwable throwable) {
				poll.completeExceptionally(throwable);
				LOGGER.error("JEI file watcher poll failed", throwable);
			} finally {
				schedulePoll(POLL_INTERVAL);
			}
		});
	}

	private void pollOnce() {
		List<Runnable> changedCallbacks = new ArrayList<>();
		callbacks.forEach((path, watchedFile) -> {
			FileTime currentModifiedTime = readModifiedTime(path);
			FileTime previousModifiedTime = watchedFile.modifiedTime;
			if (!Objects.equals(currentModifiedTime, previousModifiedTime)) {
				watchedFile.modifiedTime = currentModifiedTime;
				changedCallbacks.add(watchedFile.callback);
			}
		});

		for (Runnable callback : changedCallbacks) {
			callback.run();
		}
	}

	private static FileTime readModifiedTime(Path path) {
		try {
			if (!Files.exists(path)) {
				return null;
			}
			return Files.getLastModifiedTime(path);
		} catch (IOException e) {
			LOGGER.debug("Unable to read modified time for {}", path, e);
			return null;
		}
	}

	private static String normalizeTaskName(String value) {
		String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._:-]+", "-");
		return normalized.isBlank() ? "watcher" : normalized;
	}

	private static final class WatchedFile {
		private final Runnable callback;
		private volatile FileTime modifiedTime;

		private WatchedFile(Runnable callback, FileTime modifiedTime) {
			this.callback = callback;
			this.modifiedTime = modifiedTime;
		}
	}
}