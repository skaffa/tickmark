package dev.tickmark.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class CountersTest {
	private static final String OW = "minecraft:overworld";
	private static final String IRON = "minecraft:iron_ingot";

	@TempDir
	Path tmp;

	private static Sampling.Reading read(long tick, long ms, long iron) {
		return new Sampling.Reading(tick, ms * 1_000_000L, 0, true, Map.of(IRON, iron), false);
	}

	@Test
	void adjacentContainersJoinTheLastCounter() {
		CounterSet set = new CounterSet();
		Counter a = set.mark(OW, List.of(new Pos(0, 64, 0)), false).orElseThrow().counter();
		var joined = set.mark(OW, List.of(new Pos(1, 64, 0)), false).orElseThrow();
		assertFalse(joined.created());
		assertSame(a, joined.counter());
		assertEquals(Counter.Kind.GROUP, a.kind);
		// not touching: new counter
		var far = set.mark(OW, List.of(new Pos(5, 64, 0)), false).orElseThrow();
		assertTrue(far.created());
		// touching, but forced new
		var forced = set.mark(OW, List.of(new Pos(6, 64, 0)), true).orElseThrow();
		assertTrue(forced.created());
		// already marked
		assertTrue(set.mark(OW, List.of(new Pos(0, 64, 0)), false).isEmpty());
		// other dimension never joins
		assertTrue(set.mark("minecraft:the_nether", List.of(new Pos(7, 64, 0)), false).orElseThrow().created());
		assertEquals(4, set.counters.size());
	}

	@Test
	void doubleChestIsOneCounter() {
		CounterSet set = new CounterSet();
		Counter c = set.mark(OW, List.of(new Pos(0, 64, 0), new Pos(0, 64, 1)), false).orElseThrow().counter();
		assertEquals(2, c.positions.size());
		assertEquals(1, set.counters.size());
	}

	@Test
	void boxCornersInAnyOrder() {
		Counter b = Counter.box(OW, new Pos(5, 70, -2), new Pos(1, 64, 3));
		assertEquals(new Pos(1, 64, -2), b.boxMin);
		assertEquals(new Pos(5, 70, 3), b.boxMax);
		assertEquals(5L * 7 * 6, b.boxVolume());
		assertTrue(b.contains(new Pos(3, 65, 0)));
		assertFalse(b.contains(new Pos(6, 65, 0)));
	}

	@Test
	void firstReadingIsBaselineThenGainsCount() {
		Counter c = new Counter();
		assertNull(Sampling.apply(c, read(1000, 0, 50)));
		Sample s = Sampling.apply(c, read(1020, 1000, 51));
		assertNotNull(s);
		assertEquals(Map.of(IRON, 1L), s.gains());
		assertEquals(20, s.ticks());
		assertEquals(1000, s.wallMillis());
	}

	@Test
	void pausedWorldKeepsRealClockInStep() {
		Counter c = new Counter();
		Sampling.apply(c, read(1000, 0, 0));
		// world paused for 30 s: game time does not move
		assertNull(Sampling.apply(c, read(1000, 30_000, 0)));
		Sample s = Sampling.apply(c, read(1020, 31_000, 1));
		assertEquals(1000, s.wallMillis());
	}

	@Test
	void notTickingIsAGap() {
		Counter c = new Counter();
		Sampling.apply(c, read(0, 0, 10));
		Sample gap = Sampling.apply(c, new Sampling.Reading(1200, 60_000_000_000L, 0, false, Map.of(IRON, 10L), false));
		assertTrue(gap.gap());
		assertTrue(c.inGap);
		Sample after = Sampling.apply(c, read(1220, 61_000, 12));
		assertFalse(after.gap());
		assertEquals(Map.of(IRON, 2L), after.gains());
		assertEquals(1200, c.series.total().gapTicks());
		assertEquals(20, c.series.total().measuredTicks());
	}

	@Test
	void unreadableContainersAreAGapAndKeepTheBaseline() {
		Counter c = new Counter();
		Sampling.apply(c, read(0, 0, 10));
		Sample s = Sampling.apply(c, new Sampling.Reading(20, 1_000_000_000L, 0, false, null, false));
		assertTrue(s.gap());
		assertEquals(Map.of(IRON, 3L), Sampling.apply(c, read(40, 2000, 13)).gains());
	}

	@Test
	void rejoiningAddsTimeAwayAsGap() {
		Counter c = new Counter();
		Sampling.apply(c, read(0, 0, 0));
		Sampling.apply(c, read(1200, 60_000, 60));
		c.restart(); // left and rejoined
		Sample away = Sampling.apply(c, read(3600, 0, 60));
		assertNotNull(away);
		assertTrue(away.gap());
		assertEquals(2400, c.series.total().gapTicks());
	}

	@Test
	void pauseAndResumeAddNoGap() {
		Counter c = new Counter();
		Sampling.apply(c, read(0, 0, 0));
		Sampling.apply(c, read(1200, 60_000, 60));
		c.paused = true;
		assertNull(Sampling.apply(c, read(2400, 120_000, 100)));
		c.paused = false;
		c.restart(true);
		assertNull(Sampling.apply(c, read(3600, 180_000, 100)));
		Sampling.apply(c, read(4800, 240_000, 160));
		assertEquals(0, c.series.total().gapTicks());
		assertEquals(2400, c.series.total().measuredTicks());
		assertEquals(120, c.series.total().count(IRON));
	}

	@Test
	void filterKeepsOnlyListedItems() {
		Counter c = new Counter();
		c.filter.add(IRON);
		Sampling.apply(c, new Sampling.Reading(0, 0, 0, true, Map.of(IRON, 0L, "minecraft:poppy", 0L), false));
		Sample s = Sampling.apply(c, new Sampling.Reading(20, 1_000_000_000L, 0, true, Map.of(IRON, 2L, "minecraft:poppy", 5L), false));
		assertEquals(Map.of(IRON, 2L), s.gains());
	}

	@Test
	void storeRoundTripAndBrokenFile() throws Exception {
		CounterSet set = new CounterSet();
		Counter c = set.add(Counter.container(OW, List.of(new Pos(1, 2, 3))));
		c.name = "Iron farm";
		Sampling.apply(c, read(0, 0, 0));
		Sampling.apply(c, read(1200, 60_000, 60));
		set.add(Counter.box(OW, new Pos(0, 0, 0), new Pos(2, 2, 2)));
		Path dir = CounterStore.worldDir(tmp, "sp_My World");
		CounterStore.save(dir, set);
		CounterSet back = CounterStore.load(dir);
		assertEquals(2, back.counters.size());
		Counter c2 = back.get(c.id).orElseThrow();
		assertEquals("Iron farm", c2.name);
		assertEquals(List.of(new Pos(1, 2, 3)), c2.positions);
		assertEquals(60, c2.series.total().count(IRON));
		assertEquals(1200, c2.series.total().measuredTicks());
		assertEquals(Long.MIN_VALUE, c2.lastTick);
		assertEquals(3, back.nextId);
		assertEquals(new Pos(2, 2, 2), back.counters.get(1).boxMax);

		Files.writeString(dir.resolve(CounterStore.FILE_NAME), "{ broken");
		assertTrue(CounterStore.load(dir).counters.isEmpty());
		assertTrue(Files.isRegularFile(dir.resolve(CounterStore.FILE_NAME + ".broken")));
	}

	@Test
	void formatting() {
		assertEquals("3.5", Format.rate(3.46));
		assertEquals("1,284", Format.rate(1284.4));
		assertEquals("-", Format.rate(Double.NaN));
		assertEquals("45 s", Format.duration(900));
		assertEquals("1 m 59 s", Format.duration(119 * 20));
		assertEquals("2 m", Format.duration(2400));
		assertEquals("12 m", Format.duration(12 * 1200));
		assertEquals("1 h 40 m", Format.duration(100 * 1200));
		assertEquals("2 d 3 h", Format.duration((48 + 3) * 72_000L));
		assertEquals("iron_ingot", Format.shortId(IRON));
		assertEquals("create:zinc", Format.shortId("create:zinc"));
	}
}
