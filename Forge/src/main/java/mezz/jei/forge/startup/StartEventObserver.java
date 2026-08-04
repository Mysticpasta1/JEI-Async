package mezz.jei.forge.startup;

import mezz.jei.common.Internal;
import mezz.jei.common.network.IConnectionToServer;
import mezz.jei.forge.events.PermanentEventSubscriptions;
import mezz.jei.gui.overlay.LoadingOverlayRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;

/**
 * This class observes events and determines when it's the right time to start JEI.
 *
 * JEI needs to see {@link ClientPlayerNetworkEvent.LoggingIn} before it is ready to start. When
 * the connection can provide server recipe content, JEI also waits for {@link RecipesUpdatedEvent}
 * so it does not briefly start with fallback client recipes.
 *
 * Connections that never provide synced recipes continue with fallback recipes.
 * Datapack reloads can fire another recipe event after JEI has started; if that event provides
 * synced recipes, JEI restarts using the synced recipes.
 *
 * Once those events have arrived, JEI additionally waits for the world to finish loading before
 * starting, so that background loading never races against a half-built client level.
 */
public class StartEventObserver implements ResourceManagerReloadListener {
	private static final Logger LOGGER = LogManager.getLogger();

	private enum State {
		LISTENING, WAITING_FOR_WORLD, JEI_STARTED
	}

	private final IConnectionToServer serverConnection;
	private final Runnable startRunnable;
	private final Runnable stopRunnable;
	private WeakReference<Connection> currentConnection = new WeakReference<>(null);
	private State state = State.LISTENING;
	private boolean observedLogin;
	private boolean observedRecipeSync;

	public StartEventObserver(IConnectionToServer serverConnection, Runnable startRunnable, Runnable stopRunnable) {
		this.serverConnection = serverConnection;
		this.startRunnable = startRunnable;
		this.stopRunnable = stopRunnable;
	}

	public void register(PermanentEventSubscriptions subscriptions) {
		subscriptions.register(EventPriority.LOWEST, ClientPlayerNetworkEvent.LoggingIn.class, this::onLoggingIn);
		subscriptions.register(EventPriority.LOWEST, RecipesUpdatedEvent.class, this::onRecipesUpdatedEvent);

		subscriptions.register(ClientPlayerNetworkEvent.LoggingOut.class, event -> {
			if (event.getPlayer() != null) {
				logReceivedEvent(event);
				Internal.clearClientRecipes();
				transitionState(State.LISTENING);
			}
		});

		subscriptions.register(TickEvent.ClientTickEvent.class, event -> {
			if (event.phase != TickEvent.Phase.START) {
				return;
			}
			Minecraft minecraft = Minecraft.getInstance();
			if (this.state == State.WAITING_FOR_WORLD && isWorldLoaded()) {
				LOGGER.info("JEI StartEventObserver: World is fully loaded");
				transitionState(State.JEI_STARTED);
			} else if (this.state != State.LISTENING && minecraft.level == null) {
				// The world went away while JEI was starting or already loading.
				LOGGER.info("JEI detected world unload during startup");
				transitionState(State.LISTENING);
			}
		});

		subscriptions.register(ScreenEvent.Render.Post.class, event -> LoadingOverlayRenderer.renderLoadingOverlay(event.getScreen(), event.getGuiGraphics()));

		subscriptions.register(ScreenEvent.RenderInventoryMobEffects.class, event -> {
			if (Internal.isLoading()) {
				event.setCanceled(true);
			}
		});

		subscriptions.register(ScreenEvent.Init.Pre.class, event -> {
			if (this.state != State.JEI_STARTED) {
				Screen screen = event.getScreen();
				Minecraft minecraft = screen.getMinecraft();
				if (screen instanceof AbstractContainerScreen && minecraft != null && minecraft.player != null) {
					LOGGER.error("""
							A Screen is opening but JEI hasn't started yet.
							Normally, JEI is started after these events have fired: {}.
							Something has caused one or more of these events to fail, so JEI is starting very late.
							Missing events: {}""",
						getRequiredStartEventsString(),
						getMissingStartEventsString()
					);
					transitionState(State.LISTENING);
					transitionState(State.WAITING_FOR_WORLD);
					transitionState(State.JEI_STARTED);
				}
			}
		});
	}

	private void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
		if (!observeConnectionEvent(event)) {
			return;
		}
		this.observedLogin = true;
		startIfReady();
	}

	private void onRecipesUpdatedEvent(RecipesUpdatedEvent event) {
		if (!observeConnectionEvent(event)) {
			return;
		}
		this.observedRecipeSync = true;
		if (this.state == State.JEI_STARTED && Internal.hasClientSyncedRecipes()) {
			restart();
		} else {
			startIfReady();
		}
	}

	private void startIfReady() {
		if (this.state != State.LISTENING || !this.observedLogin) {
			return;
		}
		if (shouldWaitForRecipes() && !this.observedRecipeSync) {
			return;
		}
		transitionState(State.WAITING_FOR_WORLD);
		LOGGER.info("JEI StartEventObserver: All required events received, waiting for world load...");

		// The world may already be loaded by the time the events arrive; don't wait for a tick then.
		if (isWorldLoaded()) {
			transitionState(State.JEI_STARTED);
		}
	}

	private static boolean isWorldLoaded() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level != null && minecraft.player != null;
	}

	private <T extends Event> boolean observeConnectionEvent(T event) {
		Connection observingConnection = this.currentConnection.get();
		Connection currentConnection = getCurrentConnection();
		if (currentConnection != observingConnection) {
			clearObservedStartEvents();
			this.currentConnection = new WeakReference<>(currentConnection);
		}
		if (currentConnection == null) {
			LOGGER.debug("JEI StartEventObserver received {} too early, ignoring", event.getClass());
			return false;
		}
		logReceivedEvent(event);
		return true;
	}

	private boolean shouldWaitForRecipes() {
		return serverConnection.isJeiOnServer() ||
			serverConnection.isSameModLoader();
	}

	private String getRequiredStartEventsString() {
		if (shouldWaitForRecipes()) {
			return "[%s, %s]".formatted(ClientPlayerNetworkEvent.LoggingIn.class.getName(), RecipesUpdatedEvent.class.getName());
		}
		return "[%s]".formatted(ClientPlayerNetworkEvent.LoggingIn.class.getName());
	}

	private String getMissingStartEventsString() {
		StringBuilder missingEvents = new StringBuilder("[");
		if (!observedLogin) {
			missingEvents.append(ClientPlayerNetworkEvent.LoggingIn.class.getName());
		}
		if (shouldWaitForRecipes() && !observedRecipeSync) {
			if (missingEvents.length() > 1) {
				missingEvents.append(", ");
			}
			missingEvents.append(RecipesUpdatedEvent.class.getName());
		}
		return missingEvents.append("]").toString();
	}

	private static <T extends Event> void logReceivedEvent(T event) {
		LOGGER.debug("JEI StartEventObserver received event: {}", event.getClass());
	}

	@Nullable
	private static Connection getCurrentConnection() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientPacketListener packetListener = minecraft.getConnection();
		if (packetListener != null) {
			return packetListener.getConnection();
		} else {
			return null;
		}
	}

	@Override
	public void onResourceManagerReload(ResourceManager resourceManager) {
		LOGGER.debug("JEI StartEventObserver detected resource manager reload.");
		restart();
	}

	private void restart() {
		if (this.state != State.JEI_STARTED) {
			return;
		}
		transitionState(State.LISTENING);
		transitionState(State.WAITING_FOR_WORLD);
		transitionState(State.JEI_STARTED);
	}

	private void transitionState(State newState) {
		LOGGER.debug("JEI StartEventObserver transitioning state from {} to {}", this.state, newState);

		switch (newState) {
			case LISTENING -> {
				if (this.state == State.JEI_STARTED) {
					this.stopRunnable.run();
				}
			}
			case WAITING_FOR_WORLD -> {
				if (this.state != State.LISTENING) {
					throw new IllegalStateException("Attempted Illegal state transition from " + this.state + " to " + newState);
				}
				// These mods initialize static state from class init that is not safe to trigger
				// from JEI's background loading threads, so force them onto the main thread first.
				forceProjectEClassLoad();
				forceMekanismClassLoad();
				forceJERClassLoad();
			}
			case JEI_STARTED -> {
				if (this.state != State.WAITING_FOR_WORLD) {
					throw new IllegalStateException("Attempted Illegal state transition from " + this.state + " to " + newState);
				}
				// Start JEI in background - this is non-blocking now
				this.startRunnable.run();
				LOGGER.info("JEI startup initiated in background. The world is running.");
			}
		}

		this.state = newState;
		clearObservedStartEvents();
	}

	private void clearObservedStartEvents() {
		this.observedLogin = false;
		this.observedRecipeSync = false;
	}

	private void forceMekanismClassLoad() {
		try {
			Class<?> mekaProxyClass = Class.forName("mekanism.api.security.ISecurityUtils");
			mekaProxyClass.getField("INSTANCE");
			LOGGER.info("Mekanism ISecurityUtils loaded successfully");
		} catch (ClassNotFoundException e) {
			LOGGER.info("Mekanism ISecurityUtils not found (Mekanism may not be installed)");
		} catch (Throwable e) {
			LOGGER.info("Mekanism ISecurityUtils load error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}

	private void forceProjectEClassLoad() {
		try {
			Class<?> emcProxyClass = Class.forName("moze_intel.projecte.api.proxy.IEMCProxy");
			emcProxyClass.getField("INSTANCE");
			LOGGER.info("ProjectE IEMCProxy loaded successfully");
		} catch (ClassNotFoundException e) {
			LOGGER.info("ProjectE IEMCProxy not found (ProjectE may not be installed)");
		} catch (Throwable e) {
			LOGGER.info("ProjectE IEMCProxy load error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}

	private void forceJERClassLoad() {
		try {
			Class.forName("jeresources.compatibility.Compatibility");
			Class.forName("jeresources.compatibility.minecraft.MinecraftCompat");
			Class.forName("jeresources.util.LootTableHelper");
			Class.forName("jeresources.config.Settings");
			Class.forName("jeresources.proxy.CommonProxy");
			Class.forName("jeresources.platform.Services");
			Class.forName("jeresources.reference.Reference");
			LOGGER.info("JustEnoughResources classes loaded successfully");
		} catch (ClassNotFoundException e) {
			LOGGER.info("JustEnoughResources not found (JER may not be installed)");
		} catch (Throwable e) {
			LOGGER.info("JustEnoughResources load error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}
}
