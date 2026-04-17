package net.vital.plugins.walker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("walker")
public interface WalkerConfig extends Config
{
	@ConfigItem(
		keyName = "destination",
		name = "Destination",
		description = "Target coordinates: worldX worldY plane (e.g. 3244 1344 0)"
	)
	default String destination()
	{
		return "";
	}

	@ConfigItem(
		keyName = "minStepDistance",
		name = "Min Step Distance",
		description = "Minimum tiles per walk step"
	)
	default int minStepDistance()
	{
		return 8;
	}

	@ConfigItem(
		keyName = "maxStepDistance",
		name = "Max Step Distance",
		description = "Maximum tiles per walk step"
	)
	default int maxStepDistance()
	{
		return 15;
	}
}
