package dev.tickmark.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The slow but exact multiplayer check (PLAN.md 3.4): the server sends a container's contents only
 * while the player has it open. The contents when the player closes it are the baseline; the contents
 * when they next open it, minus that baseline, are what the farm added in between (the player's own
 * clicks while it is open fall outside that span). One log per container block; plain fields for
 * Gson, kept with the counter so it works across sessions (server game time keeps running).
 */
public final class SnapshotLog {
	/** Contents at the last close and the server game tick then; null before the first close. */
	public Map<String, Long> base;
	public long baseTick = Long.MIN_VALUE;
	/** Gain measured at the last open, and the ticks it spans; empty before the second visit. */
	public Map<String, Long> gain = new LinkedHashMap<>();
	public long gainTicks;
	/** Game tick of the open that measured {@link #gain}. */
	public long measuredAt = Long.MIN_VALUE;

	/** The player opened the container: measure against the last close. Returns true when measured. */
	public boolean opened(Map<String, Long> contents, long tick) {
		if (base == null || tick <= baseTick) {
			return false;
		}
		Map<String, Long> g = new LinkedHashMap<>();
		for (Map.Entry<String, Long> e : contents.entrySet()) {
			long d = e.getValue() - base.getOrDefault(e.getKey(), 0L);
			if (d > 0) {
				g.put(e.getKey(), d);
			}
		}
		gain = g;
		gainTicks = tick - baseTick;
		measuredAt = tick;
		return true;
	}

	/** The player closed the container: this is the new baseline. */
	public void closed(Map<String, Long> contents, long tick) {
		base = new LinkedHashMap<>(contents);
		baseTick = tick;
	}

	public boolean measured() {
		return gainTicks > 0;
	}

	/** Items per unit at 20 TPS over the measured span; NaN before a measurement. */
	public double rate(String item, RateUnit unit) {
		return gainTicks <= 0 ? Double.NaN : (double) gain.getOrDefault(item, 0L) * unit.ticks() / gainTicks;
	}
}
