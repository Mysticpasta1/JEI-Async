package mezz.jei.common.config;

import mezz.jei.common.config.file.IConfigCategoryBuilder;
import mezz.jei.common.config.file.IConfigSchemaBuilder;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public final class DebugConfig {
	@Nullable
	private static DebugConfig instance;

	public static void create(IConfigSchemaBuilder schema) {
		instance = new DebugConfig(schema);
	}

	private final Supplier<Boolean> debugIngredientsEnabled;
	private final Supplier<Boolean> debugGuisEnabled;
	private final Supplier<Boolean> debugInputsEnabled;
	private final Supplier<Boolean> debugInfoTooltipsEnabled;
	private final Supplier<Boolean> logSuffixTreeStats;
	private final Supplier<Boolean> enableAsyncLoading;
	private final Supplier<Boolean> enableTooltipCache;
	private final Supplier<Boolean> enableParallelSearch;
	private final Supplier<Integer> searchThreadCount;

	private DebugConfig(IConfigSchemaBuilder schema) {
		IConfigCategoryBuilder advanced = schema.addCategory("debug");
		debugIngredientsEnabled = advanced.addBoolean(
			"debugIngredientsEnabled",
			false,
			"Log added and updated ingredients in JEI's ingredient filter."
		);
		debugGuisEnabled = advanced.addBoolean(
			"DebugGuis",
			false,
			"Debug GUIs enabled."
		);
		debugInputsEnabled = advanced.addBoolean(
			"DebugInputs",
			false,
			"Debug inputs enabled."
		);
		debugInfoTooltipsEnabled = advanced.addBoolean(
			"debugInfoTooltipsEnabled",
			false,
			"Add debug information to ingredient tooltips when advanced tooltips are enabled."
		);
		logSuffixTreeStats = advanced.addBoolean(
			"logSuffixTreeStats",
			false,
			"Log information about the suffix trees used for searching, to help debug JEI."
		);
		enableAsyncLoading = advanced.addBoolean(
			"enableAsyncLoading",
			true,
			"Enable asynchronous loading features for improved performance. Set to false ONLY if you experience compatibility issues with specific mods."
		);
		enableTooltipCache = advanced.addBoolean(
			"enableTooltipCache",
			true,
			"Enable tooltip caching for improved performance. Set to false ONLY if you experience tooltip-related crashes or issues."
		);
		enableParallelSearch = advanced.addBoolean(
			"enableParallelSearch",
			true,
			"Enable parallel search processing for improved performance. Set to false ONLY if you experience search-related crashes or issues."
		);
		searchThreadCount = advanced.addInteger(
			"searchThreadCount",
			4,
			1,
			64,
			"Number of threads to use for search and filtering operations. Increasing this can speed up search on multi-core CPUs, but may increase memory usage."
		);
	}

	public static boolean isDebugIngredientsEnabled() {
		if (instance == null) {
			return false;
		}
		return instance.debugIngredientsEnabled.get();
	}

	public static boolean isDebugGuisEnabled() {
		if (instance == null) {
			return false;
		}
		return instance.debugGuisEnabled.get();
	}

	public static boolean isDebugInputsEnabled() {
		if (instance == null) {
			return false;
		}
		return instance.debugInputsEnabled.get();
	}

	public static boolean isDebugInfoTooltipsEnabled() {
		if (instance == null) {
			return false;
		}
		return instance.debugInfoTooltipsEnabled.get();
	}

	public static boolean isLogSuffixTreeStatsEnabled() {
		if (instance == null) {
			return false;
		}
		return instance.logSuffixTreeStats.get();
	}

	public static boolean isAsyncLoadingEnabled() {
		if (instance == null) {
			return true; // Default to enabled
		}
		return instance.enableAsyncLoading.get();
	}

	public static boolean isTooltipCacheEnabled() {
		if (instance == null) {
			return true; // Default to enabled
		}
		return instance.enableTooltipCache.get();
	}

	public static boolean isParallelSearchEnabled() {
		if (instance == null) {
			return true; // Default to enabled
		}
		return instance.enableParallelSearch.get();
	}

	public static int getSearchThreadCount() {
		if (instance == null) {
			return 4;
		}
		return instance.searchThreadCount.get();
	}
}
