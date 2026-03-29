package mezz.jei.library.load;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import mezz.jei.api.IModPlugin;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class IncompatiblePluginStore {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type STRING_SET_TYPE = new TypeToken<HashSet<String>>() {}.getType();

	private final Path filePath;
	private final Set<String> incompatiblePlugins;

	public IncompatiblePluginStore(Path configDir) {
		this.filePath = configDir.resolve("async_incompatible_plugins.json");
		this.incompatiblePlugins = loadFromFile();
	}

	public boolean isIncompatible(IModPlugin plugin) {
		ResourceLocation uid = plugin.getPluginUid();
		return incompatiblePlugins.contains(uid.toString());
	}

	public void markIncompatible(IModPlugin plugin) {
		ResourceLocation uid = plugin.getPluginUid();
		String uidString = uid.toString();
		if (incompatiblePlugins.add(uidString)) {
			LOGGER.warn("Marking plugin as async-incompatible: {}", uidString);
			saveToFile();
		}
	}

	private Set<String> loadFromFile() {
		if (!Files.exists(filePath)) {
			return new HashSet<>();
		}
		try (Reader reader = Files.newBufferedReader(filePath)) {
			Set<String> result = GSON.fromJson(reader, STRING_SET_TYPE);
			if (result != null) {
				LOGGER.info("Loaded {} async-incompatible plugins from {}", result.size(), filePath);
				return new HashSet<>(result);
			}
		} catch (IOException | com.google.gson.JsonSyntaxException e) {
			LOGGER.error("Failed to load incompatible plugins file: {}", filePath, e);
		}
		return new HashSet<>();
	}

	private void saveToFile() {
		try {
			Files.createDirectories(filePath.getParent());
			try (Writer writer = Files.newBufferedWriter(filePath)) {
				GSON.toJson(incompatiblePlugins, writer);
			}
		} catch (IOException e) {
			LOGGER.error("Failed to save incompatible plugins file: {}", filePath, e);
		}
	}
}
