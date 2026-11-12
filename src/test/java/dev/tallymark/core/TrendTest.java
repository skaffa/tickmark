package dev.tallymark.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.LongUnaryOperator;

class TrendTest {
	private static final String K = "minecraft:kelp";

	/** {@code minutes} full minutes, minute i produces perMinute.applyAsLong(i) items. */
	private static Series minutes(int minutes, LongUnaryOperator perMinute) {
		Series s = new Series();
		for (int i = 0; i < minutes; i++) {
			s.add(Sample.of(i * 1200L, (i + 1) * 1200L, 60_000, Map.of(K, perMinute.applyAsLong(i))));
		}
		return s;
	}

	@Test
	void fewPointsIsWarmingUp() {
		Trend.Result r = Trend.of(minutes(4, i -> 10).buckets(), K);
		assertEquals(Trend.Direction.WARMING_UP, r.direction());
	}

	@Test
	void flatIsStable() {
		Trend.Result r = Trend.of(minutes(15, i -> 60).buckets(), K);
		assertEquals(Trend.Direction.STABLE, r.direction());
		assertEquals(0.0, r.change(), 1e-9);
		assertEquals(0.0, r.variation(), 1e-9);
	}

	@Test
	void risingAndFalling() {
		assertEquals(Trend.Direction.RISING, Trend.of(minutes(15, i -> 40 + 4 * i).buckets(), K).direction());
		assertEquals(Trend.Direction.FALLING, Trend.of(minutes(15, i -> 100 - 4 * i).buckets(), K).direction());
	}

	@Test
	void smallDriftStaysStable() {
		// 100 -> 105 over the window: 5% < 10% threshold
		Trend.Result r = Trend.of(minutes(11, i -> 100 + i / 2).buckets(), K);
		assertEquals(Trend.Direction.STABLE, r.direction());
	}

	@Test
	void burstyFarmHasHighVariation() {
		// raid-farm style: 300 every fifth minute, nothing between
		Trend.Result r = Trend.of(minutes(20, i -> i % 5 == 0 ? 300 : 0).buckets(), K);
		assertTrue(r.variation() > 1.5, "variation " + r.variation());
	}

	@Test
	void gapMinutesDoNotCountAsZero() {
		Series s = minutes(10, i -> 50);
		s.add(Sample.gap(12_000, 24_000, 600_000));
		for (int i = 20; i < 30; i++) {
			s.add(Sample.of(i * 1200L, (i + 1) * 1200L, 60_000, Map.of(K, 50L)));
		}
		Trend.Result r = Trend.of(s.buckets(), K);
		assertEquals(Trend.Direction.STABLE, r.direction());
		assertEquals(20, r.points());
	}

	@Test
	void sparklineScalesAndMarksGaps() {
		Series s = minutes(3, i -> new long[] {0, 35, 70}[(int) i]);
		s.add(Sample.gap(3600, 4800, 60_000));
		int[] line = Trend.sparkline(s.buckets(), K, 0, 4);
		assertArrayEquals(new int[] {0, 4, 7, -1, -1}, line);
	}

	@Test
	void sparklineAllZeroIsFlatBottom() {
		int[] line = Trend.sparkline(minutes(3, i -> 0).buckets(), K, 0, 2);
		assertArrayEquals(new int[] {0, 0, 0}, line);
	}
}
