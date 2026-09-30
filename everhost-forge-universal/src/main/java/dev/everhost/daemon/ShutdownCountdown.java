package dev.everhost.daemon;

import java.util.ArrayList;
import java.util.List;

final class ShutdownCountdown {
	private ShutdownCountdown() {}

	static boolean shouldBroadcast(int initialSeconds, int remainingSeconds) {
		return remainingSeconds == initialSeconds || remainingSeconds <= 5;
	}

	static List<Integer> broadcastMarks(int seconds) {
		List<Integer> marks = new ArrayList<>();
		for (int remaining = seconds; remaining > 0; remaining--) {
			if (shouldBroadcast(seconds, remaining)) marks.add(remaining);
		}
		return List.copyOf(marks);
	}

	static String countdownCommand(String announcement, int remainingSeconds) {
		String unit = remainingSeconds == 1 ? " second" : " seconds";
		return tellraw(announcement + " in " + remainingSeconds + unit + ".", "gold");
	}

	static String finalCommand(String message) {
		return tellraw(message + " Saving the world now.", "yellow");
	}

	private static String tellraw(String message, String color) {
		String safe = message.replace("\\", "\\\\").replace("\"", "\\\"");
		return "tellraw @a {\"text\":\"[EverHost] " + safe + "\",\"color\":\"" + color + "\",\"bold\":true}";
	}
}
