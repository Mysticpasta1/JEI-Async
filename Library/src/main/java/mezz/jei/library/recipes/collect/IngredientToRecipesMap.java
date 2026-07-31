package mezz.jei.library.recipes.collect;

import org.jetbrains.annotations.UnmodifiableView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps an ingredient to the recipes that use it.
 * <p>
 * This is written concurrently while plugins load, then is overwhelmingly read-only.
 * {@link #compact()} swaps the whole structure for immutable copies, so lookups afterwards need
 * neither locking nor defensive copying. Recipes added later through the API thaw it back to the
 * mutable form.
 */
public class IngredientToRecipesMap<R> {
	/**
	 * Map and its frozen flag together, so a reader sees a consistent pair from one volatile read
	 * and can never treat a mutable list as immutable.
	 */
	private record State<R>(Map<Object, List<R>> uidToRecipes, boolean frozen) {}

	private final Object writeLock = new Object();
	private volatile State<R> state = new State<>(new ConcurrentHashMap<>(), false);

	public void add(R recipe, Collection<Object> ingredientUids) {
		State<R> current = state;
		if (current.frozen()) {
			current = thaw();
		}
		Map<Object, List<R>> map = current.uidToRecipes();
		for (Object uid : ingredientUids) {
			map.compute(uid, (k, recipes) -> {
				if (recipes == null) {
					recipes = Collections.synchronizedList(new ArrayList<>());
				}
				recipes.add(recipe);
				return recipes;
			});
		}
	}

	@UnmodifiableView
	public List<R> get(Object ingredientUid) {
		State<R> current = state;
		List<R> recipes = current.uidToRecipes().get(ingredientUid);
		if (recipes == null) {
			return Collections.emptyList();
		}
		if (current.frozen()) {
			// Already immutable, hand it straight back. Copying here allocated a fresh list on
			// every recipe lookup, which is a hot path while browsing recipes.
			return recipes;
		}
		synchronized (recipes) {
			return List.copyOf(recipes);
		}
	}

	/**
	 * Replaces the concurrent, over-allocated loading structures with exactly-sized immutable ones.
	 * <p>
	 * Entries are moved one at a time and dropped from the source as they go. Building the
	 * replacement while the source was still fully reachable meant that, for a moment, two complete
	 * copies of the recipe index existed at once. In a large pack that transient peak is measured
	 * in gigabytes, and the JVM keeps the heap it committed to survive it.
	 * <p>
	 * This runs at the end of loading, before the runtime is published, so there are no concurrent
	 * readers to observe the source being drained.
	 */
	public void compact() {
		synchronized (writeLock) {
			State<R> current = state;
			if (current.frozen()) {
				return;
			}
			Map<Object, List<R>> source = current.uidToRecipes();
			// A plain HashMap sized to fit: with no more concurrent writers, a ConcurrentHashMap
			// costs considerably more per entry for a table this large.
			Map<Object, List<R>> compacted = new HashMap<>(capacityFor(source.size()));
			Iterator<Map.Entry<Object, List<R>>> iterator = source.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<Object, List<R>> entry = iterator.next();
				List<R> recipes = entry.getValue();
				List<R> copy;
				synchronized (recipes) {
					// List.copyOf gives an exactly-sized immutable list, dropping both the
					// synchronizedList wrapper and any slack in the ArrayList's backing array.
					copy = List.copyOf(recipes);
				}
				compacted.put(entry.getKey(), copy);
				// Release the loading list now rather than at the end, so the old and new copies
				// of it are never both alive.
				iterator.remove();
			}
			state = new State<>(compacted, true);
		}
	}

	/**
	 * Restores the mutable representation so that recipes added at runtime (via the recipe manager
	 * API) can still be indexed. Rare, and the next {@link #compact()} re-freezes it.
	 * Drains as it goes, for the same reason {@link #compact()} does.
	 */
	private State<R> thaw() {
		synchronized (writeLock) {
			State<R> current = state;
			if (!current.frozen()) {
				return current;
			}
			Map<Object, List<R>> source = current.uidToRecipes();
			Map<Object, List<R>> mutable = new ConcurrentHashMap<>(capacityFor(source.size()));
			Iterator<Map.Entry<Object, List<R>>> iterator = source.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<Object, List<R>> entry = iterator.next();
				mutable.put(entry.getKey(), Collections.synchronizedList(new ArrayList<>(entry.getValue())));
				iterator.remove();
			}
			State<R> thawed = new State<>(mutable, false);
			state = thawed;
			return thawed;
		}
	}

	private static int capacityFor(int size) {
		return Math.max(16, (int) (size / 0.75f) + 1);
	}
}
