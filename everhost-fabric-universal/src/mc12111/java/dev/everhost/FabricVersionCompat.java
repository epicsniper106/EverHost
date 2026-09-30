package dev.everhost;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class FabricVersionCompat {
	static final Identifier REQUIREMENTS_CHANNEL = Identifier.fromNamespaceAndPath("everhost", "required_mods");

	private FabricVersionCompat() {}

	static float averageTickMillis(MinecraftServer server) {
		return server.getAverageTickTimeNanos() / 1_000_000.0F;
	}

	static int playerLatency(ServerPlayer player) {
		return player.connection.latency();
	}
}
