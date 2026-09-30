package dev.everhost;

import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod.EventBusSubscriber(modid = "everhost")
public final class LagGuard {
	private static final Logger LOGGER = LoggerFactory.getLogger("EverHost Lag Guard");
	private static final Path SETTINGS_PATH = Path.of("everhost-lagguard.properties");
	private static final LagPolicy POLICY = new LagPolicy();
	private static LagGuardSettings settings = LagGuardSettings.load(SETTINGS_PATH);
	private static long settingsLoadedAt;
	private static long joinProtectionUntil;
	private static int ticks;
	private static int lastAppliedView = -1;
	private static int lastAppliedSimulation = -1;

	private LagGuard() {
	}

	@SubscribeEvent
	public static void onPlayerJoined(PlayerEvent.PlayerLoggedInEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer)) return;
		reloadSettings();
		if (settings.enabled() && settings.joinProtection()) {
			joinProtectionUntil = Math.max(joinProtectionUntil, System.currentTimeMillis() + 30_000L);
		}
	}

	@SubscribeEvent
	public static void onServerTick(TickEvent.ServerTickEvent event) {
		if (event.phase != TickEvent.Phase.END || ++ticks % 20 != 0) return;
		reloadSettings();
		MinecraftServer server = event.getServer();
		if (!settings.enabled()) {
			if (POLICY.stage() > 0) {
				POLICY.reset();
				applyDistances(server, settings.normalView(), settings.normalSimulation());
			}
			return;
		}

		int mspt = Math.max(0, Math.round(server.getAverageTickTime()));
		int maxPing = server.getPlayerList().getPlayers().stream().mapToInt(player -> Math.max(0, player.latency)).max().orElse(0);
		boolean tickOverload = mspt >= settings.warningMspt();
		boolean networkOverload = maxPing >= settings.warningPingMs();
		boolean joinProtection = System.currentTimeMillis() < joinProtectionUntil;
		int previous = POLICY.stage();
		int stage = POLICY.update(tickOverload || networkOverload, joinProtection, settings.recoverySeconds());
		int view = LagPolicy.distance(settings.normalView(), settings.minimumView(), stage);
		int simulation = LagPolicy.distance(settings.normalSimulation(), settings.minimumSimulation(), stage);
		applyDistances(server, view, simulation);
		String reason = tickOverload ? "server tick time" : networkOverload ? "connection delay" : joinProtection ? "player join" : "recovering";
		if (stage != previous) {
			LOGGER.warn("Lag Guard stage {}: {} ms/tick, {} ms maximum ping, view {}, simulation {}, reason {}",
				stage, mspt, maxPing, view, simulation, reason);
		}
		RequiredModsNetwork.sendLagHeartbeat(settings.notices(), stage, mspt, maxPing, view, simulation, reason);
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
