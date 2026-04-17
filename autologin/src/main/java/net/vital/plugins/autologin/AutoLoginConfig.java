package net.vital.plugins.autologin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("autologin")
public interface AutoLoginConfig extends Config
{
	@ConfigItem(
		keyName = "username",
		name = "Username",
		description = "Login username or email"
	)
	default String username()
	{
		return "";
	}

	@ConfigItem(
		keyName = "password",
		name = "Password",
		description = "Login password",
		secret = true
	)
	default String password()
	{
		return "";
	}

	@ConfigItem(
		keyName = "otpKey",
		name = "OTP Key",
		description = "TOTP secret key (base32) for authenticator",
		secret = true
	)
	default String otpKey()
	{
		return "";
	}
}
