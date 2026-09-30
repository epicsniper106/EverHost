package dev.everhost.client;

import java.lang.reflect.Method;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

final class LoaderBridge {
	private LoaderBridge() {}

	static Path gameDirectory() {
		return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
	}

	static boolean isModLoaded(String modId) {
		return fabricModLoaded(modId) || forgeModLoaded("net.minecraftforge.fml.ModList", modId)
			|| forgeModLoaded("net.neoforged.fml.ModList", modId);
	}

	static String codeClasspath() {
		try {
			return installedModPath().toString();
		} catch (IOException ignored) {
			return System.getProperty("java.class.path");
		}
	}

	static Path installedModPath() throws IOException {
		try {
			URI location = LoaderBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI();
			Path path = Path.of(location).toAbsolutePath().normalize();
			if (Files.exists(path)) return path;
		} catch (Exception exception) {
			throw new IOException("Cannot locate the installed EverHost JAR", exception);
		}
		throw new IOException("Cannot locate the installed EverHost JAR");
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
