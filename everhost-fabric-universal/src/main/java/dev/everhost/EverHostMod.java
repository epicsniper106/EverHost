package dev.everhost;

import dev.everhost.install.ModDistributionServer;
import dev.everhost.universal.RequiredModsManifest;
import dev.everhost.universal.RequiredModsManifest.Requirement;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EverHostMod implements ModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("EverHost");
	private static final int MAX_CLIENT_MODS = 8192;
	private static final int MAX_CLIENT_HASHES = 8192;
	private static final int DIRECT_SOURCE_MAGIC = 0x45484D33;

	@Override
	public void onInitialize() {
		ModDistributionServer.start(Path.of(""));
		LagGuard.initialize();
		ServerLoginNetworking.registerGlobalReceiver(requirementsChannel(), (server, handler, understood, response, synchronizer, sender) -> {
			List<Requirement> required = loadRequirements();
			if (required.isEmpty()) return;
			if (!understood) {
				handler.disconnect(Component.literal(RequiredModsManifest.missingDisconnectMessage(required)));
				return;
			}
			try {
				int count = response.readVarInt();
				if (count < 0 || count > MAX_CLIENT_MODS) throw new IllegalArgumentException("invalid mod count");
				Set<String> installed = new LinkedHashSet<>();
				for (int index = 0; index < count; index++) installed.add(response.readUtf(256));
				Set<String> hashes = new LinkedHashSet<>();
				if (response.readableBytes() > 0) {
					int hashCount = response.readVarInt();
					if (hashCount < 0 || hashCount > MAX_CLIENT_HASHES) throw new IllegalArgumentException("invalid JAR hash count");
					for (int index = 0; index < hashCount; index++) hashes.add(response.readUtf(64).toLowerCase(java.util.Locale.ROOT));
				}
				List<Requirement> missing = new ArrayList<>();
				for (Requirement requirement : required) {
					if (!requirement.satisfiedBy(installed, hashes)) missing.add(requirement);
				}
				if (!missing.isEmpty()) {
					handler.disconnect(Component.literal(RequiredModsManifest.missingDisconnectMessage(missing)));
				}
			} catch (RuntimeException exception) {
				LOGGER.warn("Rejected a client with an invalid required-mod response", exception);
				handler.disconnect(Component.literal("EverHost could not verify this client's installed mods. Reinstall EverHost and try again."));
			}
		});

		ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
			List<Requirement> requirements = loadRequirements();
			if (!requirements.isEmpty()) {
				var request = PacketByteBufs.create();
				request.writeVarInt(requirements.size());
				for (Requirement requirement : requirements) writeRequirement(request, requirement);
				request.writeInt(DIRECT_SOURCE_MAGIC);
				request.writeVarInt(requirements.size());
				for (Requirement requirement : requirements) {
					request.writeUtf(requirement.serverDownloadUrl(), 4096);
					request.writeUtf(requirement.serverToken(), 64);
				}
				sender.sendPacket(requirementsChannel(), request);
			}
		});
	}

	@SuppressWarnings("unchecked")
	public static <T> T requirementsChannel() {
		return (T)FabricVersionCompat.REQUIREMENTS_CHANNEL;
	}

	private static List<Requirement> loadRequirements() {
		try {
			return RequiredModsManifest.read(Path.of("").toAbsolutePath().normalize());
		} catch (IOException exception) {
			LOGGER.warn("Could not read the EverHost required-mod manifest", exception);
			return List.of();
		}
	}

	private static void writeRequirement(net.minecraft.network.FriendlyByteBuf output, Requirement requirement) {
		output.writeUtf(requirement.name(), 512);
		output.writeVarInt(requirement.modIds().size());
		for (String modId : requirement.modIds()) output.writeUtf(modId, 256);
		output.writeUtf(requirement.version(), 512);
		output.writeUtf(requirement.fileName(), 512);
		output.writeVarLong(requirement.curseProjectId());
		output.writeVarLong(requirement.curseFileId());
		output.writeUtf(requirement.downloadUrl(), 4096);
		output.writeUtf(requirement.sha256(), 64);
		output.writeUtf(requirement.curseSha1(), 40);
		output.writeVarLong(requirement.fileSize());
	}
}
