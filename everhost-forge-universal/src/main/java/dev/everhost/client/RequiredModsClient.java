package dev.everhost.client;

import dev.everhost.install.ModInstallCoordinator;
import dev.everhost.install.ModDownloadPreferences;
import dev.everhost.universal.RequiredModsManifest;
import dev.everhost.universal.RequiredModsManifest.Requirement;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.NetworkEvent;

/** Only loaded on the physical client. Hash work stays off the render and Netty threads. */
public final class RequiredModsClient {
    private static Connection activeConnection;
    private static ServerData serverData;
    private static String serverAddress;
    private static String skipOptionalOnce = "";

    private RequiredModsClient() {}

    public static void handle(List<Requirement> required, List<Requirement> optional, NetworkEvent.Context context, Runnable acknowledge) {
        Minecraft minecraft = Minecraft.getInstance();
        Connection connection = context.getNetworkManager();
        activeConnection = connection;
        serverData = connectingServer(minecraft, connection);
        String address = serverData.ip;
        serverAddress = address;
        ModInstallCoordinator.clearOffers();
        Set<String> installed = new LinkedHashSet<>();
        Set<Path> paths = new LinkedHashSet<>();
        ModList.get().getMods().forEach(mod -> installed.add(mod.getModId()));
        for (Requirement entry : java.util.stream.Stream.concat(required.stream(), optional.stream()).toList()) {
            for (String id : entry.modIds()) {
                var file = ModList.get().getModFileById(id);
                if (file != null) paths.add(file.getFile().getFilePath().toAbsolutePath().normalize());
            }
        }
        CompletableFuture.supplyAsync(() -> ModInstallCoordinator.hashes(paths))
            .whenComplete((hashes, error) -> minecraft.execute(() -> {
                if (activeConnection != connection || !connection.isConnected()) return;
                try {
                    if (error != null) throw new IllegalStateException("Could not check installed mods.", error);
                    String key = ModDownloadPreferences.serverKey(address);
                    boolean skip = key.equals(skipOptionalOnce);
                    skipOptionalOnce = "";
                    List<Requirement> missing = ModInstallCoordinator.assessCatalog(LoaderBridge.gameDirectory(), address,
                        required, skip ? List.of() : optional, installed, hashes);
                    if (!missing.isEmpty()) {
                        connection.disconnect(Component.literal(RequiredModsManifest.missingDisconnectMessage(missing)));
                    } else if (ModInstallCoordinator.optionalOffer() != null) {
                        connection.disconnect(Component.literal("Your required mods are ready. Optional mods are available from this host."));
                    } else {
                        acknowledge.run();
                    }
                } catch (Exception exception) {
                    connection.disconnect(Component.literal("EverHost could not check the server's mods: " + exception.getMessage()));
                }
            }));
    }

    public static void joinWithoutOptional(Screen parent, ModInstallCoordinator.Offer offer) {
        Minecraft minecraft = Minecraft.getInstance();
        if (serverData == null) { minecraft.setScreen(parent); return; }
        skipOptionalOnce = offer.serverKey();
        ConnectScreen.startConnecting(parent, minecraft, ServerAddress.parseString(serverAddress), serverData, false);
    }

    private static ServerData connectingServer(Minecraft minecraft, Connection connection) {
        // During Forge's login negotiation getCurrentServer() is not populated yet.
        // Read the original hostname from the login listener so choices survive DNS/IP changes.
        if (connection.getPacketListener() instanceof ClientHandshakePacketListenerImpl listener) {
            ServerData original = net.minecraftforge.fml.util.ObfuscationReflectionHelper.getPrivateValue(
                ClientHandshakePacketListenerImpl.class, listener, "f_243717_");
            if (original != null) return original;
        }
        ServerData current = minecraft.getCurrentServer();
        if (current != null) return current;
        if (connection.getRemoteAddress() instanceof java.net.InetSocketAddress remote) {
            String host = remote.getAddress().getHostAddress();
            if (host.contains(":")) host = "[" + host + "]";
            return new ServerData("EverHost server", host + ":" + remote.getPort(), false);
        }
        throw new IllegalStateException("The server address is unavailable. Please reconnect from Multiplayer.");
    }
}
