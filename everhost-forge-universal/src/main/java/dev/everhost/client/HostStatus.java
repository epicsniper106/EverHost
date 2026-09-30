package dev.everhost.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record HostStatus(
	State state,
	String message,
	String domain,
	String worldId,
	String worldName,
	String players,
	int playerCount,
	int port,
	long startedAt,
	long heartbeat,
	String lastError,
	String lastBackup,
	String pluginBridge,
	int pluginReady,
	int pluginWarnings,
	int pluginBlocked,
	int pluginLoaded,
	int pluginFailed,
	String loadedPlugins,
	String failedPlugins
) {
	public static HostStatus read(Path path) {
		Properties values = new Properties();
		if (Files.isRegularFile(path)) {
			try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				values.load(reader);
			} catch (IOException ignored) {
			}
		}
		State state;
		try {
			state = State.valueOf(values.getProperty("state", "OFFLINE"));
		} catch (IllegalArgumentException exception) {
			state = State.ERROR;
		}
		long heartbeat = number(values, "heartbeat", 0L);
		if (heartbeat > 0L && System.currentTimeMillis() - heartbeat > 5000L && state.isBusyOrOnline()
			&& !daemonAlive(values)) {
			state = State.ERROR;
			values.setProperty("message", "The background host is not responding");
		}
		return new HostStatus(
			state,
			values.getProperty("message", "Ready to host"),
			values.getProperty("domain", ""),
			values.getProperty("worldId", ""),
			values.getProperty("worldName", ""),
			values.getProperty("players", ""),
			(int)number(values, "playerCount", 0L),
			(int)number(values, "port", 25570L),
			number(values, "startedAt", 0L),
			heartbeat,
			values.getProperty("lastError", ""),
			values.getProperty("lastBackup", ""),
			values.getProperty("pluginBridge", "Disabled"),
			(int)number(values, "pluginReady", 0L),
			(int)number(values, "pluginWarnings", 0L),
			(int)number(values, "pluginBlocked", 0L),
			(int)number(values, "pluginLoaded", 0L),
			(int)number(values, "pluginFailed", 0L),
			values.getProperty("loadedPlugins", ""),
			values.getProperty("failedPlugins", "")
		);
	}

	public static boolean daemonAlive(Properties values) {
		long pid = number(values, "daemonPid", 0L);
		return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
	}

	public HostStatus withState(State newState, String detail) {
		return new HostStatus(newState, detail, domain, worldId, worldName, players, playerCount,
			port, startedAt, heartbeat, newState == State.ERROR ? detail : lastError, lastBackup,
			pluginBridge, pluginReady, pluginWarnings, pluginBlocked, pluginLoaded, pluginFailed, loadedPlugins, failedPlugins);
	}

	private static long number(Properties values, String key, long fallback) {
		try {
			return Long.parseLong(values.getProperty(key, Long.toString(fallback)));
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	public enum State {
		OFFLINE, PROVISIONING, BACKING_UP, STARTING, ONLINE, STOPPING, ERROR;

		public boolean isBusy() {
			return this == PROVISIONING || this == BACKING_UP || this == STARTING || this == STOPPING;
		}

		public boolean isBusyOrOnline() {
			return isBusy() || this == ONLINE;
		}
	}
}
