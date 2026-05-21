package mezz.jei.core.util;

/**
 * Shared mutex used to synchronize access to Forge/Minecraft registries
 * between JEI's async loading threads and the main render thread.
 * <p>
 * Any code path that touches non-thread-safe Forge registries
 * (e.g. creative tab lists, tag managers, ingredient helpers)
 * should synchronize on this lock to avoid ConcurrentModificationException.
 * <p>
 * The lock is intentionally reentrant to allow nested locking from the same thread.
 */
public class RegistryLock {
	private static final Object LOCK = new Object();

	public static Object get() {
		return LOCK;
	}

	private RegistryLock() {}
}
