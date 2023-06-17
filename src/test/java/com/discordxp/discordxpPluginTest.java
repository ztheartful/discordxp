package com.discordxp;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class discordxpPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(discordxpPlugin.class);
		RuneLite.main(args);
	}
}