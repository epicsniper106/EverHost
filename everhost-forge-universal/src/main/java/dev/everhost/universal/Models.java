package dev.everhost.universal;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Models {
	private Models() {
	}

	public enum LoaderType {
		FABRIC("Fabric"), FORGE("Forge"), UNKNOWN("Unknown");

		private final String display;

		LoaderType(String display) {
			this.display = display;
		}

		@Override
		public String toString() {
			return display;
		}
	}

	public enum ModSide {
		SERVER("Server compatible"), CLIENT("Client only"), UNCERTAIN("Review needed");

		private final String display;

		ModSide(String display) {
			this.display = display;
		}

		@Override
		public String toString() {
			return display;
		}
	}

	public record Profile(
		String name,
		Path path,
		String minecraftVersion,
		LoaderType loader,
		String loaderVersion,
		List<WorldInfo> worlds,
		List<ModInfo> mods,
		int pluginCount
	) {
		@Override
		public String toString() {
			return name + "  |  " + minecraftVersion + "  |  " + loader;
		}
	}

	public record WorldInfo(String name, Path path, long lastModified) {
		@Override
		public String toString() {
			return name;
		}
	}

	public static final class ModInfo {
		public final String name;
		public final Path path;
		public final ModSide side;
		public final String reason;
		public final Set<String> modIds;
		public final Set<String> fabricDependencies;
		public final Set<Long> curseDependencies;
		public final long curseProjectId;
		public final long curseFileId;
		public final String exactVersion;
		public final String downloadUrl;
		public final String curseSha1;
		public final long downloadSize;
		public final boolean distributionAllowed;
		public final boolean pluginBridge;
		public boolean selected;
		public boolean clientRequired;

		ModInfo(
			String name,
			Path path,
			ModSide side,
			String reason,
			Set<String> modIds,
			Set<String> fabricDependencies,
			Set<Long> curseDependencies,
			long curseProjectId,
			long curseFileId,
			String exactVersion,
			String downloadUrl,
			String curseSha1,
			long downloadSize,
			boolean distributionAllowed,
			boolean pluginBridge,
			boolean clientRequired
		) {
			this.name = name;
			this.path = path;
			this.side = side;
			this.reason = reason;
			this.modIds = new LinkedHashSet<>(modIds);
			this.fabricDependencies = new LinkedHashSet<>(fabricDependencies);
			this.curseDependencies = new LinkedHashSet<>(curseDependencies);
			this.curseProjectId = curseProjectId;
			this.curseFileId = curseFileId;
			this.exactVersion = exactVersion == null ? "" : exactVersion;
			this.downloadUrl = downloadUrl == null ? "" : downloadUrl;
			this.curseSha1 = curseSha1 == null ? "" : curseSha1;
			this.downloadSize = downloadSize;
			this.distributionAllowed = distributionAllowed;
			this.pluginBridge = pluginBridge;
			this.selected = side == ModSide.SERVER;
			this.clientRequired = clientRequired && side == ModSide.SERVER;
		}
	}
}
