package mezz.jei.common.util;

import mezz.jei.core.QuantifiedIntegration.QuantifiedIntegration;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class DelayedExecutor implements IDelayedExecutor {
	private static @Nullable DelayedExecutor INSTANCE;

	public static DelayedExecutor getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new DelayedExecutor();
		}
		return INSTANCE;
	}

	private DelayedExecutor() {
	}

	@Override
	public Future<?> schedule(Runnable command, Duration delay) {
		Objects.requireNonNull(command, "command");
		Objects.requireNonNull(delay, "delay");
		CompletableFuture<Void> result = new CompletableFuture<>();
		Executor qapiDelayedExecutor = CompletableFuture.delayedExecutor(
			Math.max(0L, delay.toMillis()),
			TimeUnit.MILLISECONDS,
			QuantifiedIntegration.executor("jei-delayed-run")
		);
		qapiDelayedExecutor.execute(() -> {
			if (result.isCancelled()) {
				return;
			}
			try {
				command.run();
				result.complete(null);
			} catch (Throwable throwable) {
				result.completeExceptionally(throwable);
			}
		});
		return result;
	}
}