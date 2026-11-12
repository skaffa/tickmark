package dev.tickmark.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Map;

class SeriesTest {
	private static final String IRON = "minecraft:iron_ingot";

	/** Feeds steady output: {@code perStep} items every {@code step} ticks, real time {@code msPerTick}. */
	private static void feed(Series s, long from, long to, long step, long perStep, double msPerTick) {
		for (long t = from; t < to; t += step) {
			long end = Math.min(to, t + step);
			s.add(Sample.of(t, end, Math.round((end - t) * msPerTick), Map.of(IRON, perStep)));
		}
	}

	@Test
	void steadyRateIsExact() {
		Series s = new Series();
		// 1 item every 20 ticks = 3600/h, for one game hour
		feed(s, 0, 72_000, 20, 1, 50);
		Series.Window w = s.total();
		assertEquals(72_000, w.measuredTicks());
		assertEquals(3600, w.count(IRON));
		assertEquals(3600.0, w.rate(IRON, RateUnit.PER_HOUR), 1e-9);
		assertEquals(60.0, w.rate(IRON, RateUnit.PER_MINUTE), 1e-9);
		assertEquals(86_400.0, w.rate(IRON, RateUnit.PER_DAY), 1e-9);
		assertEquals(20.0, w.tps(), 1e-9);
	}

	@Test
	void windowsCoverWholeMinutes() {
		Series s = new Series();
		// 30 minutes at 1/20 ticks, then 30 minutes at 2/20 ticks
		feed(s, 0, 36_000, 20, 1, 50);
		feed(s, 36_000, 72_000, 20, 2, 50);
		long now = 72_000;
		Series.Window w5 = s.window(now, 5 * 1200);
		assertEquals(5 * 1200, w5.measuredTicks());
		assertEquals(7200.0, w5.rate(IRON, RateUnit.PER_HOUR), 1e-9);
		Series.Window w60 = s.window(now, 60 * 1200);
		assertEquals(72_000, w60.measuredTicks());
		assertEquals(5400.0, w60.rate(IRON, RateUnit.PER_HOUR), 1e-9);
		assertEquals(5, w5.buckets().size());
	}

	@Test
	void partialCurrentMinuteCountsOnlyMeasuredTicks() {
		Series s = new Series();
		feed(s, 0, 1800, 20, 1, 50); // one and a half minutes
		Series.Window w = s.window(1800, 1200);
		assertEquals(600, w.measuredTicks());
		assertEquals(30, w.count(IRON));
		assertEquals(3600.0, w.rate(IRON, RateUnit.PER_HOUR), 1e-9);
	}

	@Test
	void gapsAreLeftOutOfTimeAndItems() {
		Series s = new Series();
		feed(s, 0, 12_000, 20, 1, 50); // 10 min measured
		s.add(new Sample(12_000, 24_000, 600_000, 0, true, Map.of(IRON, 999L), 0, 0)); // 10 min not loaded
		feed(s, 24_000, 36_000, 20, 1, 50);
		Series.Window w = s.total();
		assertEquals(24_000, w.measuredTicks());
		assertEquals(12_000, w.gapTicks());
		assertEquals(1200, w.count(IRON));
		assertEquals(3600.0, w.rate(IRON, RateUnit.PER_HOUR), 1e-9);
	}

	@Test
	void lagChangesRealRateNotGameRate() {
		Series s = new Series();
		// server at 10 TPS: each tick takes 100 ms
		feed(s, 0, 36_000, 20, 1, 100);
		Series.Window w = s.total();
		assertEquals(3600.0, w.rate(IRON, RateUnit.PER_HOUR), 1e-9);
		assertEquals(1800.0, w.realRate(IRON, RateUnit.PER_HOUR), 1e-9);
		assertEquals(10.0, w.tps(), 1e-9);
	}

	@Test
	void sampleAcrossMinuteBoundaryIsSplit() {
		Series s = new Series();
		s.add(Sample.of(1100, 1300, 10_000, Map.of(IRON, 4L)));
		var b = s.buckets();
		assertEquals(2, b.size());
		assertEquals(100, b.get(0).measuredTicks);
		assertEquals(100, b.get(1).measuredTicks);
		assertEquals(5_000, b.get(0).wallMillis);
		assertEquals(5_000, b.get(1).wallMillis);
		// items land in the minute where the sample ended
		assertEquals(0, b.get(0).count(IRON));
		assertEquals(4, b.get(1).count(IRON));
	}

	@Test
	void historyIsCappedAtOneDay() {
		Series s = new Series();
		long ticks = (Series.MAX_BUCKETS + 100) * Series.BUCKET_TICKS;
		feed(s, 0, ticks, 1200, 60, 50);
		assertEquals(Series.MAX_BUCKETS, s.buckets().size());
		// totals keep everything
		assertEquals(ticks, s.total().measuredTicks());
		assertEquals((Series.MAX_BUCKETS + 100) * 60L, s.total().count(IRON));
	}

	@Test
	void longGapAwayDoesNotCreateMoreThanOneDayOfBuckets() {
		Series s = new Series();
		feed(s, 0, 1200, 20, 1, 50);
		long away = 10L * 24 * 72_000; // 10 game days
		s.add(Sample.gap(1200, 1200 + away, away * 50));
		assertTrue(s.buckets().size() <= Series.MAX_BUCKETS);
		assertEquals(away, s.total().gapTicks());
		assertEquals(1200, s.total().measuredTicks());
	}

	@Test
	void emptyOrBackwardSamplesAreIgnored() {
		Series s = new Series();
		s.add(Sample.of(100, 100, 0, Map.of(IRON, 5L)));
		s.add(Sample.of(200, 100, 0, Map.of(IRON, 5L)));
		assertEquals(0, s.total().count(IRON));
		assertTrue(Double.isNaN(s.total().rate(IRON, RateUnit.PER_HOUR)));
	}

	@Test
	void lostAndUnknownAreKeptApart() {
		Series s = new Series();
		s.add(new Sample(0, 1200, 60_000, 0, false, Map.of(IRON, 50L), 3, 2));
		Series.Window w = s.total();
		assertEquals(50, w.count(IRON));
		assertEquals(3, w.lost());
		assertEquals(2, w.unknown());
	}

	@Test
	void resetClearsEverything() {
		Series s = new Series();
		feed(s, 0, 2400, 20, 1, 50);
		s.reset();
		assertEquals(0, s.total().measuredTicks());
		assertTrue(s.buckets().isEmpty());
		assertEquals(Long.MIN_VALUE, s.lastTick());
	}

	@Test
	void itemsSortedByCount() {
		Series s = new Series();
		s.add(Sample.of(0, 1200, 60_000, Map.of("a", 1L, "b", 10L, "c", 5L)));
		assertEquals(java.util.List.of("b", "c", "a"), s.total().itemsByCount());
	}
}
