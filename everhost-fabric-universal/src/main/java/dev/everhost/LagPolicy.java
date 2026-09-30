package dev.everhost;

/** Pure adaptive policy kept separate from Forge so its escalation and recovery can be tested. */
public final class LagPolicy {
	private int stage;
	private int overloadSamples;
	private int healthySamples;

	public int update(boolean overloaded, boolean joinProtection, int recoverySeconds) {
		if (joinProtection && stage == 0) stage = 1;
		if (overloaded) {
			healthySamples = 0;
			if (++overloadSamples >= 3) {
				stage = Math.min(3, stage + 1);
				overloadSamples = 0;
			}
		} else {
			overloadSamples = 0;
			if (!joinProtection && stage > 0 && ++healthySamples >= Math.max(10, recoverySeconds)) {
				stage--;
				healthySamples = 0;
			}
		}
		return stage;
	}

	public int stage() {
		return stage;
	}

	public void reset() {
		stage = 0;
		overloadSamples = 0;
		healthySamples = 0;
	}

	public static int distance(int normal, int minimum, int stage) {
		int floor = Math.min(normal, Math.max(2, minimum));
		return switch (Math.max(0, Math.min(3, stage))) {
			case 0 -> normal;
			case 1 -> Math.max(floor, (normal * 3 + 3) / 4);
			case 2 -> Math.max(floor, (normal + 1) / 2);
			default -> floor;
		};
	}
}
