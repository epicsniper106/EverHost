package dev.everhost.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ShutdownCountdownTest {
	@Test
	void tenSecondCountdownAnnouncesStartAndFinalFiveSeconds() {
		assertEquals(List.of(10, 5, 4, 3, 2, 1), ShutdownCountdown.broadcastMarks(10));
	}

	@Test
	void restartMessagesAreValidTellrawCommands() {
		String countdown = ShutdownCountdown.countdownCommand("Server restarting", 1);
		assertTrue(countdown.startsWith("tellraw @a {\"text\":\"[EverHost] Server restarting in 1 second."));
		assertTrue(countdown.contains("\"color\":\"gold\""));
		assertTrue(ShutdownCountdown.finalCommand("Server is restarting.").contains("Saving the world now."));
	}
}
