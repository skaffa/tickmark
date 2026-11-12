package dev.tickmark.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entity counting for one counter in multiplayer (PLAN.md 3.2). The client sees item entities, their
 * stack sizes and their removal, but never why they were removed (26.3 {@code RemoveEntitiesPacket}
 * carries ids only). The ledger infers the cause:
 *
 * <ul>
 * <li>A take packet for the entity: picked up by a player or mob, so not output ("lost").</li>
 * <li>Another entity of the same item grew by the same amount within {@value #SETTLE} ticks: a merge,
 * not counted (stack sizes are conserved).</li>
 * <li>Out of the client's sight (chunk gone, too far from the player): "unknown", and the period is
 * marked as a gap.</li>
 * <li>Resting on or next to a hopper, hopper minecart or container: "collected".</li>
 * <li>Client age near the 6000-tick lifetime: despawned ("lost").</li>
 * <li>Anything else (lava, cactus, void): "unknown".</li>
 * </ul>
 *
 * A count drop on a live entity next to a collector, without a take packet, is a partial hopper
 * pickup and is collected too. In "arrived" mode (an area without hoppers) every entity is counted
 * once, with its stack size, when it first enters the zone.
 */
public final class EntityLedger {
	/** Ticks a removal waits for a matching merge before it is judged. */
	public static final int SETTLE = 2;
	/** Client age (ticks the client has seen the entity) treated as despawn: lifetime 6000 minus margin. */
	public static final int DESPAWN_AGE = 5900;

	public enum Rule {
		/** Count items that disappear into a hopper or container (default). */
		COLLECTED,
		/** Count items once when they enter the zone (collection areas without hoppers). */
		ARRIVED
	}

	/** What the client knows about one item entity this tick. */
	public record Seen(int id, String item, int count, boolean inZone, boolean nearCollector, int clientAge) {
	}

	/**
	 * Why an entity left the client, as far as the client can tell at that moment.
	 *
	 * @param nearCollector where the entity is at removal: a falling item is above the hopper at the
	 *                      last scan and only reaches it between scans
	 */
	public record Removal(int id, boolean outOfSight, boolean nearCollector) {
		public Removal(int id, boolean outOfSight) {
			this(id, outOfSight, false);
		}
	}

	/** Totals the ledger hands to the sampler, then forgets. */
	public static final class Events {
		public final Map<String, Long> gains = new LinkedHashMap<>();
		public long lost;
		public long unknown;
		/** True when items left the client's sight: the sample should be a gap. */
		public boolean blind;

		void gain(String item, long n) {
			if (n > 0) {
				gains.merge(item, n, Long::sum);
			}
		}
	}

	private static final class Tracked {
		String item;
		int count;
		boolean inZone;
		boolean nearCollector;
		int clientAge;
		boolean arrivedCounted;
	}

	private record Pending(int tick, String item, int count, boolean nearCollector, int clientAge, boolean inZone, boolean outOfSight,
			boolean taken) {
	}

	private record Credit(int tick, String item, int amount) {
	}

	private Rule rule = Rule.COLLECTED;
	private final Map<Integer, Tracked> tracked = new HashMap<>();
	private final Map<Integer, Integer> takenThisTick = new HashMap<>();
	private final List<Pending> pending = new ArrayList<>();
	private final List<Credit> credits = new ArrayList<>();
	private Events events = new Events();
	private int now;

	public void rule(Rule r) {
		this.rule = r;
	}

	public Rule rule() {
		return rule;
	}

	public boolean tracks(int id) {
		return tracked.containsKey(id);
	}

	/** Starts a client tick. */
	public void tick(int clientTick) {
		now = clientTick;
	}

	/** Counts items directly (the inventory method: the local player picked them up). */
	public void add(String item, long n) {
		events.gain(item, n);
	}

	/** A take packet: entity {@code id} was picked up by a living entity ({@code amount} items). */
	public void taken(int id, int amount) {
		takenThisTick.merge(id, amount, Integer::sum);
	}

	/** Called for every item entity in or around the zone, every tick, after the packets of that tick. */
	public void see(Seen s) {
		Tracked t = tracked.get(s.id());
		if (t == null) {
			t = new Tracked();
			t.item = s.item();
			t.count = s.count();
			tracked.put(s.id(), t);
			update(t, s);
			return;
		}
		int d = s.count() - t.count;
		Integer taken = takenThisTick.remove(s.id());
		if (d > 0) {
			// grew: it absorbed another stack (a merge); credit for a removal that is waiting
			credits.add(new Credit(now, t.item, d));
		} else if (d < 0 && taken != null) {
			events.lost += -d;
		} else if (d < 0) {
			// shrank without a take packet: a partial hopper pickup, or part of the stack merged into
			// another one that was full; wait for a matching growth like a removal does
			pending.add(new Pending(now, t.item, -d, t.nearCollector, 0, t.inZone, false, false));
		}
		t.count = s.count();
		update(t, s);
	}

	private void update(Tracked t, Seen s) {
		t.nearCollector = s.nearCollector();
		t.clientAge = s.clientAge();
		boolean entered = s.inZone() && !t.inZone;
		t.inZone = s.inZone();
		if (entered && !t.arrivedCounted && rule == Rule.ARRIVED) {
			t.arrivedCounted = true;
			events.gain(t.item, t.count);
		}
	}

	/** The entity was removed on the client (removal packet or chunk unload). */
	public void removed(Removal r) {
		Tracked t = tracked.remove(r.id());
		if (t == null) {
			return;
		}
		Integer taken = takenThisTick.remove(r.id());
		pending.add(new Pending(now, t.item, t.count, t.nearCollector || r.nearCollector(), t.clientAge, t.inZone, r.outOfSight(),
				taken != null));
	}

	/** Entities not seen this tick that were not removed either (moved out of the search area). */
	public void forgetExcept(java.util.Set<Integer> seen) {
		tracked.keySet().retainAll(seen);
	}

	/** Ends a client tick: judges removals and shrinks that have waited {@value #SETTLE} ticks. */
	public void settle() {
		settle(false);
	}

	/** Everything judged since the last call; the sampler takes it when it closes a sample. */
	public Events take() {
		Events out = events;
		events = new Events();
		return out;
	}

	/** Judges every waiting removal now (before a sample is closed for good, e.g. leaving the world). */
	public Events drainAll() {
		settle(true);
		Events out = events;
		events = new Events();
		return out;
	}

	private void settle(boolean all) {
		Iterator<Pending> it = pending.iterator();
		while (it.hasNext()) {
			Pending p = it.next();
			if (!all && now - p.tick < SETTLE) {
				continue;
			}
			it.remove();
			if (consumeCredit(p)) {
				continue; // merged into another stack: conserved
			}
			if (p.taken) {
				events.lost += p.count;
			} else if (p.outOfSight) {
				events.unknown += p.count;
				events.blind = true;
			} else if (p.nearCollector) {
				if (rule == Rule.COLLECTED) {
					events.gain(p.item, p.count);
				}
			} else if (p.clientAge >= DESPAWN_AGE) {
				events.lost += p.count;
			} else if (p.inZone || rule == Rule.COLLECTED) {
				events.unknown += p.count;
			}
		}
		credits.removeIf(c -> now - c.tick > SETTLE + 1);
		takenThisTick.clear();
	}

	private boolean consumeCredit(Pending p) {
		for (Iterator<Credit> it = credits.iterator(); it.hasNext();) {
			Credit c = it.next();
			if (c.item.equals(p.item) && c.amount == p.count && Math.abs(c.tick - p.tick) <= SETTLE) {
				it.remove();
				return true;
			}
		}
		return false;
	}

	public void clear() {
		tracked.clear();
		takenThisTick.clear();
		pending.clear();
		credits.clear();
		events = new Events();
	}
}
