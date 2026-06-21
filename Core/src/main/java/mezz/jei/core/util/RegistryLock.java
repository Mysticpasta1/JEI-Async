package mezz.jei.core.util;

public class RegistryLock {
	private static final Object LOCK = new Object();

	public static Object get() {
		return LOCK;
	}

	private RegistryLock() {}
}
