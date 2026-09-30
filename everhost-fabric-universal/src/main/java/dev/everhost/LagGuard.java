package dev.everhost;

import java.nio.file.Path;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

public final class LagGuard {
	private static final Path SETTINGS_PATH = Path.of("everhost-lagguard.properties");
	private static final LagPolicy POLICY = new LagPolicy();
	private static LagGuardSettings settings = LagGuardSettings.load(SETTINGS_PATH);
	private static long settingsLoadedAt;
	private static long joinProtectionUntil;
	private static int ticks;
	private static int lastAppliedView = -1;
	private static int lastAppliedSimulation = -1;

	private LagGuard() {}

	public static void initialize() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			reloadSettings();
			if (settings.enabled() && settings.joinProtection()) {
				joinProtectionUntil = Math.max(joinProtectionUntil, System.currentTimeMillis() + 30_000L);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(LagGuard::tick);
	}

	private static void tick(MinecraftServer server) {
		if (++ticks % 20 != 0) return;
		reloadSettings();
		if (!settings.enabled()) {
			if (POLICY.stage() > 0) {
				POLICY.reset();
				applyDistances(server, settings.normalView(), settings.normalSimulation());
			}
			return;
		}

		int mspt = Math.max(0, Math.round(FabricVersionCompat.averageTickMillis(server)));
		int maxPing = server.getPlayerList().getPlayers().stream()
			.mapToInt(player -> Math.max(0, FabricVersionCompat.playerLatency(player))).max().orElse(0);
		boolean tickOverload = mspt >= settings.warningMspt();
		boolean networkOverload = maxPing >= settings.warningPingMs();
		boolean joinProtection = System.currentTimeMillis() < joinProtectionUntil;
		int previous = POLICY.stage();
		int stage = POLICY.update(tickOverload || networkOverload, joinProtection, settings.recoverySeconds());
		int view = LagPolicy.distance(settings.normalView(), settings.minimumView(), stage);
		int simulation = LagPolicy.distance(settings.normalSimulation(), settings.minimumSimulation(), stage);
		applyDistances(server, view, simulation);
		String reason = tickOverload ? "server tick time" : networkOverload ? "connection delay" : joinProtection ? "player join" : "recovering";
		if (settings.notices() && (stage != previous || stage > 0 && ticks % 40 == 0)) {
			Component notice = Component.literal(stage > 0
				? "EverHost Lag Guard: reducing chunk work (" + mspt + " ms/tick, " + maxPing + " ms ping, view " + view + ", sim " + simulation + ")"
				: "EverHost Lag Guard: connection recovered; normal chunk settings restored");
			server.getPlayerList().getPlayers().forEach(player -> player.displayClientMessage(notice, true));
		}
	}

	private static void applyDistances(MinecraftServer server, int view, int simulation) {
		if (view != lastAppliedView) {
			server.getPlayerList().setViewDistance(view);
			lastAppliedView = view;
		}
		if (simulation != lastAppliedSimulation) {
			server.getPlayerList().setSimulationDistance(simulation);
			lastAppliedSimulation = simulation;
		}
	}

	private static void reloadSettings() {
		long now = System.currentTimeMillis();
		if (now - settingsLoadedAt >= 5000L) {
			settings = LagGuardSettings.load(SETTINGS_PATH);
			settingsLoadedAt = now;
		}
	}
}
