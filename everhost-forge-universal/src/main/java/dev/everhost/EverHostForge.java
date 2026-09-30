package dev.everhost;

import dev.everhost.client.EverHostClient;
import dev.everhost.install.ModDistributionServer;
import java.nio.file.Path;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(EverHostClient.MOD_ID)
public final class EverHostForge {
	public EverHostForge() {
		ModDistributionServer.start(Path.of(""));
		RequiredModsNetwork.register();
		if (FMLEnvironment.dist == Dist.CLIENT) {
			EverHostClient.bootstrap();
		}
	}
}
