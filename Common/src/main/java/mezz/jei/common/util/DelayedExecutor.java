package mezz.jei.common.util;

import mezz.jei.core.QuantifiedIntegration.QuantifiedIntegration;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

final class DelayedExecutor implements IDelayedExecutor {
	private static @Nullable DelayedExecutor INSTANCE;

	public static DelayedExecutor getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new DelayedExecutor();
		}
		return INSTANCE;
	}

	private final Executor delayedExecutor;

	private DelayedExecutor() {
		Executor qapiDelayedExecutor = CompletableFuture.delayedExecutor(
			0,
			java.util.concurrent.TimeUnit.MILLISECONDS,
			QuantifiedIntegration.executor("jei-delayed-run")
		);
		this.delayedExecutor = qapiDelayedExecutor;
	}

	@Override
	public Future<?> schedule(Runnable command, Duration delay) {
		return CompletableFuture.runAsync(
			command,
			CompletableFuture.delayedExecutor(
				delay.toMillis(),
				java.util.concurrent.TimeUnit.MILLISECONDS,
				delayedExecutor
			)
		);
	}
}
