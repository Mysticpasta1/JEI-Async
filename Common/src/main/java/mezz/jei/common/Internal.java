package mezz.jei.common;

import com.google.common.base.Preconditions;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.config.IJeiClientConfigs;
import mezz.jei.common.config.IClientToggleState;
import mezz.jei.common.config.ClientToggleState;
import mezz.jei.common.gui.textures.JeiSpriteUploader;
import mezz.jei.common.gui.textures.Textures;
import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.common.network.IConnectionToServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * For JEI internal use only, these are normally accessed from the API.
 */
public final class Internal {
	@Nullable
	private static Textures textures;
	@Nullable
	private static IConnectionToServer serverConnection;
	@Nullable
	private static IInternalKeyMappings keyMappings;
	@Nullable
	private static IClientToggleState toggleState;
	@Nullable
	private static IJeiClientConfigs jeiClientConfigs;
	@Nullable
	private static volatile IJeiRuntime jeiRuntime;
	/**
	 * The JEI helpers become usable long before the runtime is published: the runtime is only
	 * set once background loading has completely finished, but the helpers exist as soon as
	 * ingredients have been registered. Code that only needs helpers should use these accessors
	 * rather than reaching through {@link #getJeiRuntime()}, which is not available while loading.
	 */
	@Nullable
	private static volatile IJeiHelpers jeiHelpers;
	private static final JeiFeatures jeiFeatures = new JeiFeatures();
	private static final AtomicReference<String> loadingProgress = new AtomicReference<>(null);

	private Internal() {

	}

	public static Textures getTextures() {
		if (textures == null) {
			Minecraft minecraft = Minecraft.getInstance();
			TextureManager textureManager = minecraft.getTextureManager();
			JeiSpriteUploader spriteUploader = new JeiSpriteUploader(textureManager);
			textures = new Textures(spriteUploader);
		}
		return textures;
	}

	public static IConnectionToServer getServerConnection() {
		Preconditions.checkState(serverConnection != null, "Server Connection has not been created yet.");
		return serverConnection;
	}

	public static void setServerConnection(IConnectionToServer serverConnection) {
		Internal.serverConnection = serverConnection;
	}

	public static IInternalKeyMappings getKeyMappings() {
		Preconditions.checkState(keyMappings != null, "Key Mappings have not been created yet.");
		return keyMappings;
	}

	public static void setKeyMappings(IInternalKeyMappings keyMappings) {
		Internal.keyMappings = keyMappings;
	}

	public static IClientToggleState getClientToggleState() {
		if (toggleState == null) {
			toggleState = new ClientToggleState();
		}
		return toggleState;
	}

	public static IJeiClientConfigs getJeiClientConfigs() {
		Preconditions.checkState(jeiClientConfigs != null, "Jei Client Configs have not been created yet.");
		return jeiClientConfigs;
	}

	public static Optional<IJeiClientConfigs> getOptionalJeiClientConfigs() {
		return Optional.ofNullable(jeiClientConfigs);
	}

	public static void setJeiClientConfigs(IJeiClientConfigs jeiClientConfigs) {
		Internal.jeiClientConfigs = jeiClientConfigs;
	}

	public static JeiFeatures getJeiFeatures() {
		return jeiFeatures;
	}

	public static void setRuntime(@Nullable IJeiRuntime jeiRuntime) {
		Internal.jeiRuntime = jeiRuntime;
	}

	public static Optional<IJeiRuntime> getOptionalJeiRuntime() {
		return Optional.ofNullable(jeiRuntime);
	}

	public static IJeiRuntime getJeiRuntime() {
		Preconditions.checkState(jeiRuntime != null, "Jei Runtime has not been created yet.");

		return jeiRuntime;
	}

	public static void setJeiHelpers(@Nullable IJeiHelpers jeiHelpers) {
		Internal.jeiHelpers = jeiHelpers;
	}

	public static Optional<IJeiHelpers> getOptionalJeiHelpers() {
		return Optional.ofNullable(jeiHelpers);
	}

	public static IJeiHelpers getJeiHelpers() {
		IJeiHelpers jeiHelpers = Internal.jeiHelpers;
		Preconditions.checkState(jeiHelpers != null, "Jei Helpers have not been created yet.");

		return jeiHelpers;
	}

	public static void setLoadingProgress(@Nullable String progress) {
		loadingProgress.set(progress);
	}

	@Nullable
	public static String getLoadingProgress() {
		return loadingProgress.get();
	}

	public static boolean isLoading() {
		return loadingProgress.get() != null;
	}
}
