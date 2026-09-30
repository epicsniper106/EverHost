package dev.everhost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class LagPolicyTest {
	@Test
	void sustainedTroubleEscalatesAndHealthyTimeRecoversGradually() {
		LagPolicy policy = new LagPolicy();
		assertEquals(1, policy.update(false, true, 10));
		for (int index = 0; index < 3; index++) policy.update(true, false, 10);
		assertEquals(2, policy.stage());
		for (int index = 0; index < 3; index++) policy.update(true, false, 10);
		assertEquals(3, policy.stage());
		for (int index = 0; index < 9; index++) policy.update(false, false, 10);
		assertEquals(3, policy.stage());
		policy.update(false, false, 10);
		assertEquals(2, policy.stage());
	}

	@Test
	void distanceStagesNeverCrossConfiguredFloor() {
		assertEquals(32, LagPolicy.distance(32, 4, 0));
		assertEquals(24, LagPolicy.distance(32, 4, 1));
		assertEquals(16, LagPolicy.distance(32, 4, 2));
		assertEquals(4, LagPolicy.distance(32, 4, 3));
		assertEquals(4, LagPolicy.distance(4, 8, 3));
		assertEquals(6, LagPolicy.distance(8, 4, 1));
		assertEquals(4, LagPolicy.distance(8, 4, 2));
	}
}
