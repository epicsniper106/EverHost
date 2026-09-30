package dev.everhost.universal;

import dev.everhost.universal.Models.LoaderType;
import dev.everhost.universal.Models.ModSide;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class JarInspector {
	private static final Pattern TOML_MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*[\"']([A-Za-z0-9_.-]+)[\"']");
	private static final Pattern TOML_SIDE = Pattern.compile("(?m)^\\s*side\\s*=\\s*[\"']([A-Za-z]+)[\"']");

	private JarInspector() {
	}

	static Inspection inspect(Path jar, LoaderType loader) {
		Set<String> ids = new LinkedHashSet<>();
		Set<String> dependencies = new LinkedHashSet<>();
		ModSide side = ModSide.UNCERTAIN;
		String reason = "The mod does not declare a reliable server/client side.";
		boolean clientRequired = false;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry fabric = zip.getEntry("fabric.mod.json");
			if (fabric != null) {
				Object parsed = MiniJson.parse(read(zip, fabric));
				Map<String, Object> root = object(parsed);
				String id = string(root.get("id"));
				if (!id.isBlank()) {
					ids.add(id);
				}
				Map<String, Object> depends = object(root.get("depends"));
				for (String key : depends.keySet()) {
					if (!Set.of("minecraft", "java", "fabricloader", "fabric-loader").contains(key)) {
						dependencies.add(key);
					}
				}
				String environment = string(root.get("environment")).toLowerCase(Locale.ROOT);
				Map<String, Object> entrypoints = object(root.get("entrypoints"));
				if ("client".equals(environment)) {
					side = ModSide.CLIENT;
					reason = "fabric.mod.json marks this mod as client-only.";
				} else if ("server".equals(environment)) {
					side = ModSide.SERVER;
					reason = "fabric.mod.json marks this mod as server-only.";
				} else if ("*".equals(environment) || entrypoints.containsKey("main") || entrypoints.containsKey("server")) {
					side = ModSide.SERVER;
					reason = "Fabric metadata declares a common or server entrypoint.";
					clientRequired = !entrypoints.containsKey("server") || entrypoints.containsKey("client");
				}
			}

			ZipEntry forge = zip.getEntry("META-INF/mods.toml");
			if (forge == null) {
				forge = zip.getEntry("META-INF/neoforge.mods.toml");
			}
			if (forge != null && loader == LoaderType.FORGE) {
				String toml = read(zip, forge);
				String modsSection = toml.split("(?m)^\\s*\\[\\[dependencies\\.", 2)[0];
				Matcher idsMatcher = TOML_MOD_ID.matcher(modsSection);
				while (idsMatcher.find()) {
					ids.add(idsMatcher.group(1));
				}
				String dependenciesSection = toml.substring(Math.min(toml.length(), modsSection.length()));
				dependencies.clear();
				// Dependency side and mandatory apply to each table, not the whole mod.
				for (String table : dependenciesSection.split("(?m)^\\s*\\[\\[")) {
					if (!table.startsWith("dependencies.")) continue;
					if (!Pattern.compile("(?m)^\\s*mandatory\\s*=\\s*true\\b").matcher(table).find()) continue;
					Matcher dependencySide = TOML_SIDE.matcher(table);
					if (dependencySide.find() && "CLIENT".equalsIgnoreCase(dependencySide.group(1))) continue;
					Matcher dependencyMatcher = TOML_MOD_ID.matcher(table);
					if (dependencyMatcher.find()) {
						String dependency = dependencyMatcher.group(1);
						if (!Set.of("minecraft", "forge", "neoforge", "java").contains(dependency)) dependencies.add(dependency);
					}
				}
				Matcher sideMatcher = TOML_SIDE.matcher(toml);
				boolean clientOnly = false;
				boolean serverOrBoth = false;
				while (sideMatcher.find()) {
					String declared = sideMatcher.group(1).toUpperCase(Locale.ROOT);
					clientOnly |= "CLIENT".equals(declared);
					serverOrBoth |= "SERVER".equals(declared) || "BOTH".equals(declared);
				}
				if (clientOnly && !serverOrBoth) {
					side = ModSide.CLIENT;
					reason = "Forge metadata only declares client-side dependencies.";
				}
			}
		} catch (IOException | RuntimeException exception) {
			reason = "Metadata could not be read: " + exception.getMessage();
		}

		if (ids.isEmpty()) {
			String filename = jar.getFileName().toString();
			ids.add(filename.substring(0, Math.max(1, filename.length() - 4)).toLowerCase(Locale.ROOT));
		}
		if (ids.contains("oculus")) {
			side = ModSide.CLIENT;
			reason = "Oculus renders client shaders and cannot run on a dedicated server.";
		} else if (ids.contains("e4mc")) {
			side = ModSide.SERVER;
			reason = "e4mc provides the dedicated server's public tunnel.";
			clientRequired = false;
		}
		return new Inspection(side, reason, ids, dependencies, clientRequired);
	}

	private static String read(ZipFile zip, ZipEntry entry) throws IOException {
		try (InputStream input = zip.getInputStream(entry)) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object value) {
		return value instanceof Map<?, ?> ? (Map<String, Object>)value : Map.of();
	}

	private static String string(Object value) {
		return value instanceof String string ? string : "";
	}

	record Inspection(ModSide side, String reason, Set<String> modIds, Set<String> dependencies, boolean clientRequired) {
	}
}
