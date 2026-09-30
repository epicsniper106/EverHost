package dev.everhost;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class FabricVersionCompat {
	static final ResourceLocation REQUIREMENTS_CHANNEL = new ResourceLocation("everhost", "required_mods");

	private FabricVersionCompat() {}

	static float averageTickMillis(MinecraftServer server) {
		return server.getAverageTickTime();
	}

	static int playerLatency(ServerPlayer player) {
		return player.latency;
	}
}
