package mezz.jei.gui.ingredients;

import mezz.jei.api.helpers.IModIdHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.core.util.RegistryLock;
import mezz.jei.common.config.DebugConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public final class IngredientListElementFactory {
	private static final Logger LOGGER = LogManager.getLogger();

	private IngredientListElementFactory() {
	}

	public static List<IListElementInfo<?>> createBaseList(IIngredientManager ingredientManager, IModIdHelper modIdHelper) {
		Collection<IIngredientType<?>> ingredientTypes = ingredientManager.getRegisteredIngredientTypes();

		if (DebugConfig.isAsyncLoadingEnabled()) {
			LOGGER.info("Building ingredient list in parallel...");
			return ingredientTypes.parallelStream()
				.flatMap(ingredientType -> {
					synchronized (RegistryLock.get()) {
						return createBaseListForType(ingredientManager, ingredientType, modIdHelper).stream();
					}
				})
				.collect(Collectors.toList());
		}

		List<IListElementInfo<?>> ingredientListElements = new ArrayList<>();
		for (IIngredientType<?> ingredientType : ingredientTypes) {
			addToBaseList(ingredientListElements, ingredientManager, ingredientType, modIdHelper);
		}
		return ingredientListElements;
	}

	private static <V> List<IListElementInfo<V>> createBaseListForType(IIngredientManager ingredientManager, IIngredientType<V> ingredientType, IModIdHelper modIdHelper) {
		LOGGER.debug("Registering ingredients: {}", ingredientType.getIngredientClass().getSimpleName());

		return getIngredientList(ingredientManager, ingredientType).stream()
			.map(ingredient -> {
				Optional<ITypedIngredient<V>> typedIngredient = ingredientManager.createTypedIngredient(ingredientType, ingredient);
				return typedIngredient.map(t -> ListElementInfo.create(t, ingredientManager, modIdHelper)).orElse(null);
			})
			.filter(Objects::nonNull)
			.collect(Collectors.toList());
	}

	private static <V> Collection<V> getIngredientList(IIngredientManager ingredientManager, IIngredientType<V> ingredientType) {
		for (int i = 0; i < 5; i++) {
			try {
				synchronized (ingredientManager) { // Synchronize access to prevent concurrent modification
					Collection<V> ingredients = ingredientManager.getAllIngredients(ingredientType);
					return new ArrayList<>(ingredients);
				}
			} catch (ConcurrentModificationException e) {
				LOGGER.warn("Caught ConcurrentModificationException while copying ingredients for {}, retrying (attempt {})", ingredientType.getIngredientClass().getSimpleName(), i + 1);
				try {
					Thread.sleep(100);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
			}
		}
		// Final fallback
		synchronized (ingredientManager) {
			return new ArrayList<>(ingredientManager.getAllIngredients(ingredientType));
		}
	}

	public static <V> List<IListElementInfo<V>> createTestList(IIngredientManager ingredientManager, IIngredientType<V> ingredientType, Collection<V> ingredients, IModIdHelper modIdHelper) {
		return ingredients.stream()
			.map(i -> ingredientManager.createTypedIngredient(ingredientType, i))
			.flatMap(Optional::stream)
			.map(i -> ListElementInfo.create(i, ingredientManager, modIdHelper))
			.filter(Objects::nonNull)
			.toList();
	}

	public static List<IListElementInfo<?>> rebuildList(IIngredientManager ingredientManager, Collection<IListElement<?>> elements, IModIdHelper modIdHelper) {
		List<IListElementInfo<?>> results = new ArrayList<>();

		for (IListElement<?> element : elements) {
			IListElementInfo<?> orderedElement = ListElementInfo.createFromElement(element, ingredientManager, modIdHelper);
			if (orderedElement != null) {
				results.add(orderedElement);
			}
		}

		return results;
	}

	private static <V> void addToBaseList(List<IListElementInfo<?>> baseList, IIngredientManager ingredientManager, IIngredientType<V> ingredientType, IModIdHelper modIdHelper) {
		LOGGER.debug("Registering ingredients: {}", ingredientType.getIngredientClass().getSimpleName());
		getIngredientList(ingredientManager, ingredientType).forEach(ingredient -> {
			Optional<ITypedIngredient<V>> typedIngredient = ingredientManager.createTypedIngredient(ingredientType, ingredient);
			if (typedIngredient.isPresent()) {
				IListElementInfo<V> orderedElement = ListElementInfo.create(typedIngredient.get(), ingredientManager, modIdHelper);
				if (orderedElement != null) {
					baseList.add(orderedElement);
				}
			}
		});
	}

}
