package mezz.jei.fabric.startup;

import mezz.jei.api.IModPlugin;
import mezz.jei.common.Internal;
import mezz.jei.common.config.IServerConfig;
import mezz.jei.common.network.ClientPacketRouter;
import mezz.jei.common.network.IConnectionToServer;
import mezz.jei.fabric.events.JeiLifecycleEvents;
import mezz.jei.fabric.network.ClientNetworkHandler;
import mezz.jei.fabric.network.ConnectionToServer;
import mezz.jei.gui.config.InternalKeyMappings;
import mezz.jei.gui.overlay.LoadingOverlayRenderer;
import mezz.jei.library.startup.JeiStarter;
import mezz.jei.library.startup.StartData;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

public class ClientLifecycleHandler {
	private static final Logger LOGGER = LogManager.getLogger();

	private final JeiStarter jeiStarter;
	private boolean running;
	private boolean waitingForWorldLoad = false;

	public ClientLifecycleHandler(IServerConfig serverConfig) {
		IConnectionToServer serverConnection = new ConnectionToServer();
		Internal.setServerConnection(serverConnection);

		InternalKeyMappings keyMappings = new InternalKeyMappings(KeyBindingHelper::registerKeyBinding);
		Internal.setKeyMappings(keyMappings);

		ClientPacketRouter packetRouter = new ClientPacketRouter(serverConnection, serverConfig);
		ClientNetworkHandler.registerClientPacketHandler(packetRouter);

		List<IModPlugin> plugins = FabricPluginFinder.getModPlugins();
		StartData startData = new StartData(
			plugins,
			serverConnection,
			keyMappings
		);

		this.jeiStarter = new JeiStarter(startData);
	}

	public void registerEvents() {
		JeiLifecycleEvents.AFTER_RECIPES_UPDATED.register(() -> {
			if (running) {
				stopJei();
			}
			// Wait for world to load before starting JEI
			waitingForWorldLoad = true;
			LOGGER.info("JEI: Recipe sync complete, waiting for world load...");
		});
		ScreenEvents.AFTER_INIT.register((minecraft, screen, scaledWidth, scaledHeight) -> {
			// waitingForWorldLoad means the start is deliberately pending on the world load tick
			// below, so this fallback only covers the case where the recipe event never fired.
			if (!running && !waitingForWorldLoad && screen instanceof AbstractContainerScreen && minecraft.player != null) {
				LOGGER.error("A Screen is opening but JEI hasn't started yet because the update recipes event didn't happen.");
				startJei();
			}
		});
		JeiLifecycleEvents.GAME_STOP.register(this::stopJei);

		// Listen for client ticks to detect when the world is fully loaded
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			if (waitingForWorldLoad) {
				if (client.level != null && client.player != null) {
					LOGGER.info("JEI: World is fully loaded, starting JEI...");
					waitingForWorldLoad = false;
					startJei();
				}
			}
		});

		// Register loading overlay renderer (permanent, independent of runtime)
		ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) ->
			ScreenEvents.afterRender(screen).register((s, guiGraphics, mouseX, mouseY, tickDelta) ->
				LoadingOverlayRenderer.renderLoadingOverlay(s, guiGraphics)
			)
		);
	}

	public ResourceManagerReloadListener getReloadListener() {
		return (resourceManager) -> {
			if (running) {
				Minecraft minecraft = Minecraft.getInstance();
				if (!minecraft.isSameThread()) {
					// we may receive reload events on the server thread in single-player, ignore them
					return;
				}
				stopJei();
				startJei();
			}
		};
	}

	private void startJei() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			LOGGER.error("Failed to start JEI, there is no Minecraft client level.");
			return;
		}
		if (running) {
			LOGGER.error("Failed to start JEI, it is already running.");
			return;
		}

		this.jeiStarter.start();
		running = true;
	}

	private void stopJei() {
		if (!running) {
			return;
		}
		LOGGER.info("Stopping JEI");
		this.jeiStarter.stop();
		running = false;
	}
}
