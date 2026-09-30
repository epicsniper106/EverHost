package dev.everhost;

import dev.everhost.universal.RequiredModsManifest;
import dev.everhost.universal.RequiredModsManifest.Requirement;
import dev.everhost.client.LagGuardClient;
import dev.everhost.install.ModInstallCoordinator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.HandshakeHandler;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RequiredModsNetwork {
	private static final Logger LOGGER = LoggerFactory.getLogger("EverHost");
	private static final String PROTOCOL = "3";
	private static final int MAX_REQUIREMENTS = 8192;
	private static final int EXTENSION_MAGIC = 0x45484D33;
	private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
		.named(new ResourceLocation("everhost", "required_mods"))
		.networkProtocolVersion(() -> PROTOCOL)
		.clientAcceptedVersions(PROTOCOL::equals)
		.serverAcceptedVersions(PROTOCOL::equals)
		.simpleChannel();

	private RequiredModsNetwork() {
	}

	static void register() {
		CHANNEL.messageBuilder(RequirementsMessage.class, 0, NetworkDirection.LOGIN_TO_CLIENT)
			.loginIndex(RequirementsMessage::loginIndex, RequirementsMessage::loginIndex)
			.encoder(RequirementsMessage::encode)
			.decoder(RequirementsMessage::decode)
			.buildLoginPacketList(RequiredModsNetwork::loginPackets)
			.consumerNetworkThread(RequiredModsNetwork::handle)
			.add();
		CHANNEL.messageBuilder(RequirementsAck.class, 1, NetworkDirection.LOGIN_TO_SERVER)
			.loginIndex(RequirementsAck::loginIndex, RequirementsAck::loginIndex)
			.encoder(RequirementsAck::encode)
			.decoder(RequirementsAck::decode)
			.consumerNetworkThread(HandshakeHandler.indexFirst(RequiredModsNetwork::handleAck))
			.add();
		CHANNEL.messageBuilder(LagHeartbeat.class, 2, NetworkDirection.PLAY_TO_CLIENT)
			.encoder(LagHeartbeat::encode)
			.decoder(LagHeartbeat::decode)
			.consumerNetworkThread(RequiredModsNetwork::handleLagHeartbeat)
			.add();
	}

	static void sendLagHeartbeat(boolean notices, int stage, int mspt, int ping, int view, int simulation, String reason) {
		CHANNEL.send(PacketDistributor.ALL.noArg(), new LagHeartbeat(notices, stage, mspt, ping, view, simulation, reason));
	}

	private static void handleLagHeartbeat(LagHeartbeat message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> LagGuardClient.update(message.notices, message.stage, message.mspt, message.ping,
			message.view, message.simulation, message.reason));
		context.setPacketHandled(true);
	}

	private static List<Pair<String, RequirementsMessage>> loginPackets(boolean local) {
		List<Requirement> requirements = loadRequirements();
		if (requirements.isEmpty()) return List.of();
		return List.of(Pair.of("EverHost required client mods", new RequirementsMessage(requirements)));
	}

	private static void handle(RequirementsMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		Set<String> installed = new LinkedHashSet<>();
		ModList.get().getMods().forEach(mod -> installed.add(mod.getModId()));
		Set<Path> loadedPaths = new LinkedHashSet<>();
		for (Requirement requirement : message.requirements) {
			for (String modId : requirement.modIds()) {
				var file = ModList.get().getModFileById(modId);
				if (file != null) loadedPaths.add(file.getFile().getFilePath().toAbsolutePath().normalize());
			}
		}
		Set<String> hashes = ModInstallCoordinator.hashes(loadedPaths);
		List<Requirement> missing = ModInstallCoordinator.assess(message.requirements, installed, hashes);
		if (!missing.isEmpty()) {
			String disconnectMessage = RequiredModsManifest.missingDisconnectMessage(missing);
			LOGGER.warn("Disconnecting a client missing required mods: {}", disconnectMessage.replace('\n', ' '));
			context.getNetworkManager().disconnect(Component.literal(disconnectMessage));
		} else {
			CHANNEL.reply(new RequirementsAck(), context);
		}
		context.setPacketHandled(true);
	}

	private static void handleAck(HandshakeHandler handshake, RequirementsAck message, Supplier<NetworkEvent.Context> contextSupplier) {
		contextSupplier.get().setPacketHandled(true);
	}

	private static List<Requirement> loadRequirements() {
		try {
			return RequiredModsManifest.read(Path.of("").toAbsolutePath().normalize());
		} catch (IOException exception) {
			LOGGER.warn("Could not read the EverHost required-mod manifest", exception);
			return List.of();
		}
	}

	private static final class RequirementsMessage implements IntSupplier {
		private final List<Requirement> requirements;
		private int loginIndex;

		private RequirementsMessage(List<Requirement> requirements) {
			this.requirements = List.copyOf(requirements);
		}

		private int loginIndex() {
			return loginIndex;
		}

		private void loginIndex(int index) {
			loginIndex = index;
		}

		@Override
		public int getAsInt() {
			return loginIndex;
		}

		private void encode(FriendlyByteBuf output) {
			output.writeVarInt(requirements.size());
			for (Requirement requirement : requirements) {
				output.writeUtf(requirement.name(), 512);
				output.writeVarInt(requirement.modIds().size());
				for (String modId : requirement.modIds()) output.writeUtf(modId, 256);
			}
			output.writeInt(EXTENSION_MAGIC);
			output.writeVarInt(requirements.size());
			for (Requirement requirement : requirements) {
				output.writeUtf(requirement.version(), 512);
				output.writeUtf(requirement.fileName(), 512);
				output.writeVarLong(requirement.curseProjectId());
				output.writeVarLong(requirement.curseFileId());
				output.writeUtf(requirement.downloadUrl(), 4096);
				output.writeUtf(requirement.sha256(), 64);
				output.writeUtf(requirement.curseSha1(), 40);
				output.writeVarLong(requirement.fileSize());
				output.writeUtf(requirement.serverDownloadUrl(), 4096);
				output.writeUtf(requirement.serverToken(), 64);
			}
		}

		private static RequirementsMessage decode(FriendlyByteBuf input) {
			int count = input.readVarInt();
			if (count < 0 || count > MAX_REQUIREMENTS) throw new IllegalArgumentException("invalid requirement count");
			List<Requirement> requirements = new ArrayList<>();
			for (int index = 0; index < count; index++) {
				String name = input.readUtf(512);
				int idCount = input.readVarInt();
				if (idCount < 1 || idCount > 128) throw new IllegalArgumentException("invalid mod id count");
				Set<String> modIds = new LinkedHashSet<>();
				for (int idIndex = 0; idIndex < idCount; idIndex++) modIds.add(input.readUtf(256));
				requirements.add(new Requirement(name, modIds));
			}
			if (input.readableBytes() >= 5 && input.readInt() == EXTENSION_MAGIC) {
				int extensionCount = input.readVarInt();
				if (extensionCount != requirements.size()) throw new IllegalArgumentException("invalid requirement extension count");
				List<Requirement> exact = new ArrayList<>();
				for (int index = 0; index < extensionCount; index++) {
					Requirement base = requirements.get(index);
					exact.add(new Requirement(base.name(), base.modIds(), input.readUtf(512), input.readUtf(512),
						input.readVarLong(), input.readVarLong(), input.readUtf(4096), input.readUtf(64),
						input.readUtf(40), input.readVarLong(), input.readUtf(4096), input.readUtf(64)));
				}
				requirements = exact;
			}
			return new RequirementsMessage(requirements);
		}
	}

	private static final class RequirementsAck implements IntSupplier {
		private int loginIndex;

		private int loginIndex() {
			return loginIndex;
		}

		private void loginIndex(int index) {
			loginIndex = index;
		}

		@Override
		public int getAsInt() {
			return loginIndex;
		}

		private void encode(FriendlyByteBuf output) {
		}

		private static RequirementsAck decode(FriendlyByteBuf input) {
			return new RequirementsAck();
		}
	}

	private static final class LagHeartbeat {
		private final boolean notices;
		private final int stage;
		private final int mspt;
		private final int ping;
		private final int view;
		private final int simulation;
		private final String reason;

		private LagHeartbeat(boolean notices, int stage, int mspt, int ping, int view, int simulation, String reason) {
			this.notices = notices;
			this.stage = stage;
			this.mspt = mspt;
			this.ping = ping;
			this.view = view;
			this.simulation = simulation;
			this.reason = reason;
		}

		private void encode(FriendlyByteBuf output) {
			output.writeBoolean(notices);
			output.writeVarInt(stage);
			output.writeVarInt(mspt);
			output.writeVarInt(ping);
			output.writeVarInt(view);
			output.writeVarInt(simulation);
			output.writeUtf(reason, 64);
		}

		private static LagHeartbeat decode(FriendlyByteBuf input) {
			return new LagHeartbeat(input.readBoolean(), input.readVarInt(), input.readVarInt(), input.readVarInt(),
				input.readVarInt(), input.readVarInt(), input.readUtf(64));
		}
	}
}
