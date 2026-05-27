package mezz.jei.forge.startup;

import mezz.jei.common.Internal;
import mezz.jei.forge.events.PermanentEventSubscriptions;
import mezz.jei.gui.overlay.LoadingOverlayRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.Event;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * This class observes events and determines when it's the right time to start JEI.
 * <p>
 * JEI needs to see both the {@link TagsUpdatedEvent} and {@link RecipesUpdatedEvent}
 * before it is ready to start.
 * <p>
 * Depending on the configuration (Integrated server, vanilla server, modded server),
 * these events might come in any order.
 * <p>
 * Additionally, JEI waits for the world to finish loading before completing initialization.
 * This ensures that the world is fully loaded before JEI finishes, preventing issues with
 * world-dependent operations during JEI startup.
 */
public class StartEventObserver {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final Set<Class<? extends Event>> requiredEvents = Set.of(TagsUpdatedEvent.class, RecipesUpdatedEvent.class);

	private enum State {
		DISABLED, ENABLED, EVENTS_RECEIVED, JEI_STARTED
	}

	private final Set<Class<? extends Event>> observedEvents = new HashSet<>();
	private final Runnable startRunnable;
	private final Runnable stopRunnable;
	private State state = State.DISABLED;
	private boolean worldLoaded = false;

	public StartEventObserver(Runnable startRunnable, Runnable stopRunnable) {
		this.startRunnable = startRunnable;
		this.stopRunnable = stopRunnable;
	}

	public void register(PermanentEventSubscriptions subscriptions) {
		requiredEvents
			.forEach(eventClass -> subscriptions.register(eventClass, this::onEvent));

		subscriptions.register(ClientPlayerNetworkEvent.LoggingIn.class, event -> {
			LOGGER.info("JEI StartEventObserver received {}", event.getClass());
			if (this.state == State.DISABLED) {
				transitionState(State.ENABLED);
			}
		});

		subscriptions.register(ClientPlayerNetworkEvent.LoggingOut.class, event -> {
			if (event.getPlayer() != null) {
				LOGGER.info("JEI StartEventObserver received {}", event.getClass());
				transitionState(State.DISABLED);
			}
		});

		// Listen for client ticks to detect when the world is fully loaded
		subscriptions.register(TickEvent.ClientTickEvent.class, event -> {
			if (event.phase == TickEvent.Phase.START && this.state == State.EVENTS_RECEIVED) {
				Minecraft minecraft = Minecraft.getInstance();
				if (minecraft.level != null && minecraft.player != null) {
					// World is loaded and player is ready
					worldLoaded = true;
					LOGGER.info("JEI StartEventObserver: World is fully loaded");
					transitionState(State.JEI_STARTED);
				}
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
				if (screen instanceof AbstractContainerScreen && minecraft.player != null) {
					LOGGER.error("""
							A Screen is opening but JEI hasn't started yet.
							Normally, JEI is started after ClientPlayerNetworkEvent.LoggedInEvent, TagsUpdatedEvent, and RecipesUpdatedEvent.
							Something has caused one or more of these events to fail, so JEI is starting very late.""");
					transitionState(State.DISABLED);
					transitionState(State.ENABLED);
					transitionState(State.JEI_STARTED);
				}
			}
		});

		subscriptions.register(TickEvent.ClientTickEvent.class, event -> {
			if (event.phase == TickEvent.Phase.START) {
				Minecraft minecraft = Minecraft.getInstance();
				// Check if world was unloaded while JEI was starting or loading
				if ((this.state == State.EVENTS_RECEIVED || this.state == State.JEI_STARTED) && minecraft.level == null) {
					LOGGER.info("JEI detected world unload during startup");
					transitionState(State.DISABLED);
				}
			}
		});
	}

	/**
	 * Observe an event and start JEI if we have observed all the required events.
	 * JEI will wait for the world to finish loading before completing initialization.
	 */
	private <T extends Event> void onEvent(T event) {
		if (this.state == State.DISABLED) {
			return;
		}
		LOGGER.info("JEI StartEventObserver received {}", event.getClass());
		Class<? extends Event> eventClass = event.getClass();
		if (requiredEvents.contains(eventClass) &&
			observedEvents.add(eventClass) &&
			observedEvents.containsAll(requiredEvents)
		) {
			if (this.state == State.JEI_STARTED) {
				restart();
			} else {
				// All required events received, but wait for world load
				transitionState(State.EVENTS_RECEIVED);
				LOGGER.info("JEI StartEventObserver: All required events received, waiting for world load...");

				// Check if world is already loaded (edge case)
				Minecraft minecraft = Minecraft.getInstance();
				if (minecraft.level != null && minecraft.player != null) {
					worldLoaded = true;
					transitionState(State.JEI_STARTED);
				}
			}
		}
	}

	private void restart() {
		if (this.state != State.JEI_STARTED) {
			return;
		}
		transitionState(State.DISABLED);
		transitionState(State.ENABLED);
		transitionState(State.JEI_STARTED);
	}

	private void transitionState(State newState) {
		LOGGER.info("JEI StartEventObserver transitioning state from " + this.state + " to " + newState);

		switch (newState) {
			case DISABLED -> {
				if (this.state == State.JEI_STARTED) {
					this.stopRunnable.run();
				}
				this.worldLoaded = false;
			}
			case ENABLED -> {
				if (this.state != State.DISABLED) {
					throw new IllegalStateException("Attempted Illegal state transition from " + this.state + " to " + newState);
				}
				// Force ProjectE IEMCProxy to load on the main thread before JEI starts loading
				forceProjectEClassLoad();
				// Force Mekanism ISecurityUtils to load on the main thread before JEI starts loading
				forceMekanismClassLoad();
				// Force JER Compatibility to load on the main thread before JEI starts loading
				forceJERClassLoad();
			}
			case EVENTS_RECEIVED -> {
				if (this.state != State.ENABLED) {
					throw new IllegalStateException("Attempted Illegal state transition from " + this.state + " to " + newState);
				}
				// Force ProjectE IEMCProxy to load on the main thread before JEI starts loading
				forceProjectEClassLoad();
				// Force Mekanism ISecurityUtils to load on the main thread before JEI starts loading
				forceMekanismClassLoad();
				// Force JER Compatibility to load on the main thread before JEI starts loading
				forceJERClassLoad();
			}
			case JEI_STARTED -> {
				if (this.state != State.ENABLED && this.state != State.EVENTS_RECEIVED) {
					throw new IllegalStateException("Attempted Illegal state transition from " + this.state + " to " + newState);
				}
				if (this.state == State.EVENTS_RECEIVED && !worldLoaded) {
					// Not ready yet, wait for client tick
					return;
				}
				// Force ProjectE IEMCProxy to load on the main thread before JEI starts loading
				forceProjectEClassLoad();
				// Force Mekanism ISecurityUtils to load on the main thread before JEI starts loading
				forceMekanismClassLoad();
				// Force JER Compatibility to load on the main thread before JEI starts loading
				forceJERClassLoad();
				// Start JEI in background - this is non-blocking now
				this.startRunnable.run();
				LOGGER.info("JEI startup initiated in background. The world is running.");
			}
		}

		this.state = newState;
		this.observedEvents.clear();
	}

	private void forceMekanismClassLoad() {
		try {
			Class<?> mekaProxyClass = Class.forName("mekanism.api.security.ISecurityUtils");
			mekaProxyClass.getField("INSTANCE");
			LOGGER.info("Mekanism ISecurityUtils loaded successfully");
		} catch (ClassNotFoundException e) {
			LOGGER.info("Mekanism ISecurityUtils not found (ProjectE may not be installed)");
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
