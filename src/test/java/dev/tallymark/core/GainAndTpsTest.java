package dev.tallymark.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Map;

class GainAndTpsTest {
	@Test
	void firstReadingIsBaseline() {
		GainTracker g = new GainTracker(false);
		assertFalse(g.primed());
		assertTrue(g.update(Map.of("iron", 10L)).isEmpty());
		assertTrue(g.primed());
		assertEquals(Map.of("iron", 5L), g.update(Map.of("iron", 15L)));
	}

	@Test
	void dropsMoveTheBaselineInGainMode() {
		GainTracker g = new GainTracker(false);
		g.update(Map.of("iron", 100L));
		// player takes 64 out
		assertTrue(g.update(Map.of("iron", 36L)).isEmpty());
		// farm adds 4: only 4 counted, not the 64 taken earlier
		assertEquals(Map.of("iron", 4L), g.update(Map.of("iron", 40L)));
		// item gone entirely, then back
		assertTrue(g.update(Map.of()).isEmpty());
		assertEquals(Map.of("iron", 2L), g.update(Map.of("iron", 2L)));
	}

	@Test
	void netModeKeepsNegatives() {
		GainTracker g = new GainTracker(true);
		g.update(Map.of("iron", 10L, "gold", 3L));
		assertEquals(Map.of("iron", -4L, "gold", -3L), g.update(Map.of("iron", 6L)));
	}

	@Test
	void rebaseForgetsHistory() {
		GainTracker g = new GainTracker(false);
		g.update(Map.of("iron", 10L));
		g.rebase();
		assertTrue(g.update(Map.of("iron", 500L)).isEmpty());
	}

	@Test
	void tpsFromTimePackets() {
		TpsEstimator t = new TpsEstimator();
		assertTrue(Double.isNaN(t.tps()));
		long ns = 0;
		long game = 1000;
		// a packet every 20 server ticks, server at 14 TPS
		for (int i = 0; i < 40; i++) {
			t.add(game, ns);
			game += 20;
			ns += Math.round(20 / 14.0 * 1e9);
		}
		assertEquals(14.0, t.tps(), 0.01);
		assertTrue(t.slow(20, 0.05));
		assertFalse(t.slow(14, 0.05));
	}

	@Test
	void tpsWindowDropsOldPoints() {
		TpsEstimator t = new TpsEstimator(10_000_000_000L);
		long ns = 0;
		long game = 0;
		for (int i = 0; i < 30; i++) { // 30 s at 10 TPS
			t.add(game, ns);
			game += 20;
			ns += 2_000_000_000L;
		}
		for (int i = 0; i < 30; i++) { // then 30 s at 20 TPS
			t.add(game, ns);
			game += 20;
			ns += 1_000_000_000L;
		}
		assertEquals(20.0, t.tps(), 0.01);
	}

	@Test
	void tpsRestartsWhenTimeGoesBack() {
		TpsEstimator t = new TpsEstimator();
		t.add(5000, 0);
		t.add(5020, 1_000_000_000L);
		t.add(10, 2_000_000_000L); // new world
		assertTrue(Double.isNaN(t.tps()));
	}
}
