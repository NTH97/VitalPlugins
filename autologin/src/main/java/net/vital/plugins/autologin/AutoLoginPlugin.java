package net.vital.plugins.autologin;

import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import vital.api.Bridge;
import vital.api.input.Keyboard;
import vital.api.world.Game;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@PluginDescriptor(
	name = "Auto Login Test",
	description = "Automatically logs in with saved credentials and TOTP",
	tags = {"login", "auto", "totp"}
)
public class AutoLoginPlugin extends Plugin
{
	@Inject
	private AutoLoginConfig config;

	private volatile boolean loginTriggered;
	private volatile boolean otpTriggered;
	private volatile boolean nativeBridgeWorking;
	private final AtomicBoolean nativeTickReceived = new AtomicBoolean(false);
	private ScheduledExecutorService scheduler;
	private ScheduledFuture<?> pollFuture;

	@Provides
	AutoLoginConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AutoLoginConfig.class);
	}

	@Override
	protected void startUp()
	{
		loginTriggered = false;
		otpTriggered = false;
		nativeTickReceived.set(false);

		// Test if the native bridge is safe to call
		nativeBridgeWorking = testNativeBridge();

		log.info("Auto Login started — user: {}, native bridge: {}",
			config.username(), nativeBridgeWorking ? "OK" : "UNAVAILABLE");

		if (!nativeBridgeWorking)
		{
			log.warn("Native bridge calls are crashing — auto login disabled until hooks are fixed");
			return;
		}

		// Try immediately
		tick();

		// Start polling
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "AutoLogin-Poll");
			t.setDaemon(true);
			return t;
		});
		pollFuture = scheduler.scheduleAtFixedRate(() -> {
			try
			{
				tick();
			}
			catch (Throwable t)
			{
				log.warn("Poll tick error: {}", t.getMessage());
				nativeBridgeWorking = false;
			}
		}, 600, 600, TimeUnit.MILLISECONDS);
	}

	private boolean testNativeBridge()
	{
		try
		{
			// This is the simplest native call — just reads a field from engine memory
			Game.getGameState();
			return true;
		}
		catch (Throwable t)
		{
			log.warn("Native bridge test failed: {}f", t.getMessage());
			return false;
		}
	}

	@Override
	protected void shutDown()
	{
		if (pollFuture != null)
		{
			pollFuture.cancel(false);
		}
		if (scheduler != null)
		{
			scheduler.shutdownNow();
			scheduler = null;
		}
		log.info("Auto Login stopped");
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		nativeTickReceived.set(true);
		tick();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		nativeTickReceived.set(true);
		tick();
	}

	private void tick()
	{
		if (!nativeBridgeWorking)
		{
			return;
		}

		try
		{
			if (Game.isLoggedIn() || Game.isLoading())
			{
				loginTriggered = false;
				otpTriggered = false;
				return;
			}

			if (!Game.isOnLoginScreen())
			{
				return;
			}

			int loginState = Game.getLoginState();

			// Handle authenticator/TOTP screen
			if (loginState == Game.LOGIN_AUTHENTICATOR)
			{
				if (otpTriggered)
				{
					return;
				}

				String otpKey = config.otpKey();
				if (otpKey.isEmpty())
				{
					return;
				}

				String code = Bridge.totpGenerateCode(otpKey);
				if (code == null || code.isEmpty())
				{
					log.warn("Failed to generate TOTP code");
					return;
				}

				log.info("Entering TOTP code: {}", code);
				Keyboard.type(code + "\r");
				otpTriggered = true;
				return;
			}

			// Handle Jagex launcher login (OAuth2)
			if (Game.isJagexLauncherLogin() && !loginTriggered)
			{
				log.info("Jagex launcher login detected, using loginJagex()");
				if (Game.loginJagex())
				{
					log.info("Jagex login triggered");
					loginTriggered = true;
				}
				return;
			}

			// Handle credential entry screen
			if (loginState != Game.LOGIN_ENTER_CREDENTIALS
					&& loginState != Game.LOGIN_INVALID_CREDENTIALS
					&& loginState != Game.LOGIN_BEEN_DISCONNECTED)
			{
				return;
			}

			if (loginTriggered)
			{
				return;
			}

			String user = config.username();
			String pass = config.password();
			if (user.isEmpty() || pass.isEmpty())
			{
				return;
			}

			Game.setUsername(user);
			Game.setPassword(pass);
			if (Game.login())
			{
				log.info("Login triggered for {}", user);
				loginTriggered = true;
			}
		}
		catch (Throwable t)
		{
			log.warn("Login tick error: {} — disabling native bridge", t.getMessage());
			nativeBridgeWorking = false;
		}
	}
}
