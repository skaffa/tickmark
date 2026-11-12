package dev.tickmark.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

class EntityLedgerTest {
	private static final String IRON = "minecraft:iron_ingot";
	private static final String KELP = "minecraft:kelp";

	private final EntityLedger l = new EntityLedger();
	private int tick;

	/** One client tick: packets first (the runnable), then the entity scan, then settling. */
	private void step(Runnable packets, EntityLedger.Seen... seen) {
		packets.run();
		tick++;
		l.tick(tick);
		for (EntityLedger.Seen s : seen) {
			l.see(s);
		}
		l.settle();
	}

	private void step(EntityLedger.Seen... seen) {
		step(() -> {
		}, seen);
	}

	private static EntityLedger.Seen onHopper(int id, String item, int count) {
		return new EntityLedger.Seen(id, item, count, true, true, 40);
	}

	private void idle(int n) {
		for (int i = 0; i < n; i++) {
			step();
		}
	}

	@Test
	void hopperPickupIsCollected() {
		step(onHopper(1, IRON, 3));
		step(() -> l.removed(new EntityLedger.Removal(1, false)));
		idle(3);
		EntityLedger.Events e = l.take();
		assertEquals(Map.of(IRON, 3L), e.gains);
		assertEquals(0, e.lost);
		assertEquals(0, e.unknown);
	}

	@Test
	void itemSeenOnlyAtItsRemovalIsCollected() {
		// an idle hopper takes the item the tick after it spawns: the client learns of it from the removal
		step(() -> {
			l.see(onHopper(7, IRON, 1));
			l.removed(new EntityLedger.Removal(7, false));
		});
		idle(3);
		assertEquals(Map.of(IRON, 1L), l.take().gains);
	}

	@Test
	void fallingItemReachesTheHopperBetweenScans() {
		// last scan: still in the air above the hopper
		step(new EntityLedger.Seen(3, IRON, 1, true, false, 10));
		// removal: by now it is on the hopper
		step(() -> l.removed(new EntityLedger.Removal(3, false, true)));
		idle(3);
		EntityLedger.Events e = l.take();
		assertEquals(Map.of(IRON, 1L), e.gains);
		assertEquals(0, e.unknown);
	}

	@Test
	void partialHopperPickupIsCollected() {
		step(onHopper(1, KELP, 64));
		step(onHopper(1, KELP, 63));
		step(onHopper(1, KELP, 62));
		idle(3);
		assertEquals(Map.of(KELP, 2L), l.take().gains);
	}

	@Test
	void fullMergeIsNotCounted() {
		step(onHopper(1, IRON, 5), onHopper(2, IRON, 3));
		// 2 merges into 1: 1 grows by 3, 2 is removed, in the same server tick
		step(() -> l.removed(new EntityLedger.Removal(2, false)), onHopper(1, IRON, 8));
		idle(3);
		EntityLedger.Events e = l.take();
		assertTrue(e.gains.isEmpty(), "merge counted: " + e.gains);
		// then the hopper takes the merged stack
		step(() -> l.removed(new EntityLedger.Removal(1, false)));
		idle(3);
		assertEquals(Map.of(IRON, 8L), l.take().gains);
	}

	@Test
	void partialMergeIntoFullStackIsNotCounted() {
		step(onHopper(1, KELP, 60), onHopper(2, KELP, 10));
		// 4 move from 2 to 1 (capped at 64): 1 grows, 2 shrinks, nothing removed
		step(onHopper(1, KELP, 64), onHopper(2, KELP, 6));
		idle(3);
		assertTrue(l.take().gains.isEmpty());
	}

	@Test
	void playerPickupIsLostNotCollected() {
		step(onHopper(1, IRON, 2));
		step(() -> {
			l.taken(1, 2);
			l.removed(new EntityLedger.Removal(1, false));
		});
		idle(3);
		EntityLedger.Events e = l.take();
		assertTrue(e.gains.isEmpty());
		assertEquals(2, e.lost);
	}

	@Test
	void outOfSightIsUnknownAndBlind() {
		step(onHopper(1, IRON, 2));
		step(() -> l.removed(new EntityLedger.Removal(1, true)));
		idle(3);
		EntityLedger.Events e = l.take();
		assertTrue(e.gains.isEmpty());
		assertEquals(2, e.unknown);
		assertTrue(e.blind);
	}

	@Test
	void despawnFarFromHoppersIsLost() {
		step(new EntityLedger.Seen(1, IRON, 1, true, false, 5950));
		step(() -> l.removed(new EntityLedger.Removal(1, false)));
		idle(3);
		EntityLedger.Events e = l.take();
		assertEquals(1, e.lost);
		assertEquals(0, e.unknown);
	}

	@Test
	void removalAwayFromCollectorsIsUnknown() {
		step(new EntityLedger.Seen(1, IRON, 1, true, false, 100));
		step(() -> l.removed(new EntityLedger.Removal(1, false)));
		idle(3);
		assertEquals(1, l.take().unknown);
	}

	@Test
	void arrivedRuleCountsEachEntityOnceOnEntry() {
		l.rule(EntityLedger.Rule.ARRIVED);
		step(new EntityLedger.Seen(1, KELP, 1, false, false, 0)); // falling, outside the zone
		step(new EntityLedger.Seen(1, KELP, 1, true, false, 1)); // enters
		step(new EntityLedger.Seen(1, KELP, 1, false, false, 2)); // bounces out
		step(new EntityLedger.Seen(1, KELP, 1, true, false, 3)); // and back in: not again
		step(new EntityLedger.Seen(1, KELP, 1, true, false, 4), new EntityLedger.Seen(2, KELP, 2, true, false, 0));
		// 2 merges into 1 later: no extra count
		step(() -> l.removed(new EntityLedger.Removal(2, false)), new EntityLedger.Seen(1, KELP, 3, true, false, 5));
		idle(3);
		assertEquals(Map.of(KELP, 3L), l.take().gains);
	}

	@Test
	void forgetDropsEntitiesThatLeftTheSearchArea() {
		step(onHopper(1, IRON, 1), onHopper(2, IRON, 1));
		l.forgetExcept(Set.of(1));
		assertTrue(l.tracks(1));
		assertFalse(l.tracks(2));
	}

	@Test
	void eventsFeedSamples() {
		Counter c = new Counter();
		Sampling.begin(c, 1000, 0);
		Sample s = Sampling.events(c, 1020, 1_000_000_000L, 0, false, Map.of(IRON, 2L), 1, 0);
		assertEquals(20, s.ticks());
		assertEquals(1000, s.wallMillis());
		assertEquals(2, c.series.total().count(IRON));
		assertEquals(1, c.series.total().lost());
		Sample g = Sampling.events(c, 1040, 2_000_000_000L, 0, true, Map.of(IRON, 5L), 0, 3);
		assertTrue(g.gap());
		assertEquals(2, c.series.total().count(IRON));
		assertEquals(20, c.series.total().gapTicks());
	}

	@Test
	void snapshotsMeasureBetweenCloseAndNextOpen() {
		SnapshotLog log = new SnapshotLog();
		assertFalse(log.opened(Map.of(IRON, 10L), 100));
		// player took 10 out, closes with 0 at tick 200
		log.closed(Map.of(), 200);
		// 12000 ticks later: 600 iron
		assertTrue(log.opened(Map.of(IRON, 600L, KELP, 0L), 12_200));
		assertEquals(Map.of(IRON, 600L), log.gain);
		assertEquals(12_000, log.gainTicks);
		assertEquals(3600.0, log.rate(IRON, RateUnit.PER_HOUR), 1e-9);
		Counter c = new Counter();
		c.snapshots.put("0 64 0", log);
		SnapshotLog other = new SnapshotLog();
		other.closed(Map.of(), 0);
		other.opened(Map.of(IRON, 120L), 72_000);
		c.snapshots.put("1 64 0", other);
		assertEquals(3720.0, c.snapshotRate(IRON, RateUnit.PER_HOUR), 1e-9);
	}
}
