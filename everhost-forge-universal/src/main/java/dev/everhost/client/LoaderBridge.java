package dev.everhost.client;

import java.lang.reflect.Method;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import net.minecraftforge.fml.ModList;

final class LoaderBridge {
	private LoaderBridge() {}

	static Path gameDirectory() {
		return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
	}

	static boolean isModLoaded(String modId) {
		return fabricModLoaded(modId) || forgeModLoaded("net.minecraftforge.fml.ModList", modId)
			|| forgeModLoaded("net.neoforged.fml.ModList", modId);
	}

	static String codeClasspath() throws IOException {
		// Forge code-source URLs can point into its virtual union filesystem.
		// A standalone JVM needs the physical mod file, not that virtual path.
		Path installed = installedModPath();
		if (containsDaemon(installed)) return installed.toString();
		throw new IOException("Cannot locate EverHost's background host in the installed jar. Reinstall the matching EverHost edition.");
	}

	static Path installedModPath() throws IOException {
		var mod = ModList.get().getModFileById("everhost");
		if (mod != null) {
			Path installed = mod.getFile().getFilePath().toAbsolutePath().normalize();
			if (Files.isRegularFile(installed)) return installed;
		}
		try {
			URI location = LoaderBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI();
			Path path = Path.of(location).toAbsolutePath().normalize();
			if (Files.isRegularFile(path)) return path;
		} catch (Exception ignored) {
		}
		throw new IOException("Cannot locate the installed EverHost JAR");
	}

	static boolean containsDaemon(Path path) throws IOException {
		if (path.getFileSystem() != FileSystems.getDefault()) return false;
		String entry = "dev/everhost/daemon/HostDaemon.class";
		if (Files.isDirectory(path)) return Files.isRegularFile(path.resolve(entry));
		if (!Files.isRegularFile(path)) return false;
		try (JarFile jar = new JarFile(path.toFile())) {
			return jar.getJarEntry(entry) != null;
		}
	}

	private static boolean fabricModLoaded(String modId) {
		try {
			Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
			Object loader = loaderClass.getMethod("getInstance").invoke(null);
			return (boolean)loaderClass.getMethod("isModLoaded", String.class).invoke(loader, modId);
		} catch (ReflectiveOperationException | LinkageError exception) {
			return false;
		}
	}

	private static boolean forgeModLoaded(String className, String modId) {
		try {
			Class<?> modListClass = Class.forName(className);
			Method get = modListClass.getMethod("get");
			Object modList = get.invoke(null);
			return (boolean)modListClass.getMethod("isLoaded", String.class).invoke(modList, modId);
		} catch (ReflectiveOperationException | LinkageError exception) {
			return false;
		}
	}
}
