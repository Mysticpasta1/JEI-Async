package mezz.jei.library.runtime;

import mezz.jei.api.helpers.IColorHelper;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.helpers.IModIdHelper;
import mezz.jei.api.helpers.IPlatformFluidHelper;
import mezz.jei.api.helpers.IStackHelper;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.vanilla.IVanillaRecipeFactory;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IIngredientVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

public class DelegatingJeiHelpers implements IJeiHelpers {
	private static final long WAIT_TIMEOUT_SECONDS = 30;

	private volatile IJeiHelpers delegate;
	private volatile CompletableFuture<IJeiHelpers> delegateFuture = new CompletableFuture<>();

	public DelegatingJeiHelpers(IJeiHelpers delegate) {
		this.delegate = delegate;
		if (delegate != null) {
			delegateFuture.complete(delegate);
		}
	}

	public void setDelegate(IJeiHelpers delegate) {
		this.delegate = delegate;
		if (delegate != null) {
			// Without this, every waiter below sat out the full timeout and then threw anyway,
			// because nothing else ever completed the future.
			delegateFuture.complete(delegate);
		} else {
			// Swap in a fresh future rather than leaving the old one holding a completed value:
			// that value is the previous JeiHelpers, and through it the entire previous runtime.
			delegateFuture = new CompletableFuture<>();
		}
	}

	public IJeiHelpers getDelegate() {
		return delegate;
	}

	public boolean hasDelegate() {
		return delegate != null;
	}

	private IJeiHelpers blockingGetDelegate() {
		IJeiHelpers current = delegate;
		if (current != null) {
			return current;
		}
		// Never park the render thread waiting on a background loader: it would freeze the client
		// for the whole timeout, and if that loader is itself waiting on the main thread it would
		// never finish at all. Fail fast instead; callers on the main thread are expected to check
		// hasDelegate()/Internal.isLoading() first.
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null && minecraft.isSameThread()) {
			throw new IllegalStateException("JEI helpers are not available yet (still loading)");
		}
		try {
			return delegateFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for JEI helpers", e);
		} catch (ExecutionException | TimeoutException e) {
			throw new IllegalStateException("JEI helpers not available yet", e);
		}
	}

	@Override
	public IGuiHelper getGuiHelper() {
		return blockingGetDelegate().getGuiHelper();
	}

	@Override
	public IStackHelper getStackHelper() {
		return blockingGetDelegate().getStackHelper();
	}

	@Override
	public IModIdHelper getModIdHelper() {
		return blockingGetDelegate().getModIdHelper();
	}

	@Override
	public IFocusFactory getFocusFactory() {
		return blockingGetDelegate().getFocusFactory();
	}

	@Override
	public IColorHelper getColorHelper() {
		return blockingGetDelegate().getColorHelper();
	}

	@Override
	public IPlatformFluidHelper<?> getPlatformFluidHelper() {
		return blockingGetDelegate().getPlatformFluidHelper();
	}

	@Override
	public <T> Optional<RecipeType<T>> getRecipeType(ResourceLocation uid, Class<? extends T> recipeClass) {
		return blockingGetDelegate().getRecipeType(uid, recipeClass);
	}

	@Override
	public Optional<RecipeType<?>> getRecipeType(ResourceLocation uid) {
		return blockingGetDelegate().getRecipeType(uid);
	}

	@Override
	public Stream<RecipeType<?>> getAllRecipeTypes() {
		return blockingGetDelegate().getAllRecipeTypes();
	}

	@Override
	public IIngredientManager getIngredientManager() {
		return blockingGetDelegate().getIngredientManager();
	}

	@Override
	public IVanillaRecipeFactory getVanillaRecipeFactory() {
		return blockingGetDelegate().getVanillaRecipeFactory();
	}

	@Override
	public IIngredientVisibility getIngredientVisibility() {
		return blockingGetDelegate().getIngredientVisibility();
	}
}
