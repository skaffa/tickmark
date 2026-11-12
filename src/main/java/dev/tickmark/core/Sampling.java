package dev.tickmark.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns content readings of a counter into samples (PLAN.md 3.1 and 4). Minecraft-free so the rules
 * for baselines, gaps, pauses and time away are unit-tested.
 */
public final class Sampling {
	/**
	 * One reading.
	 *
	 * @param gameTick game time when it was read (server time in exact mode)
	 * @param nanos    {@code System.nanoTime()} when it was read
	 * @param epochMillis wall clock, for the CSV
	 * @param ticking  the counter's chunks were loaded and ticking
	 * @param contents item id to count, or null when the containers could not be read
	 * @param full     every slot full: the farm is capped
	 */
	public record Reading(long gameTick, long nanos, long epochMillis, boolean ticking, Map<String, Long> contents,
			boolean full) {
	}

	private Sampling() {
	}

	/** Applies a reading; returns the sample added, or null when none was added. */
	public static Sample apply(Counter c, Reading r) {
		if (c.paused) {
			if (c.lastTick != Long.MIN_VALUE) {
				c.restart(true);
			}
			return null;
		}
		Map<String, Long> contents = r.contents() == null ? null : filter(c, r.contents());
		boolean gap = !r.ticking() || contents == null;
		if (c.lastTick == Long.MIN_VALUE) {
			Sample away = null;
			long saved = c.series.lastTick();
			if (!c.quietRestart && saved != Long.MIN_VALUE && r.gameTick() > saved) {
				away = Sample.gap(saved, r.gameTick(), 0);
				c.series.add(away);
			}
			c.quietRestart = false;
			c.lastTick = r.gameTick();
			c.lastNanos = r.nanos();
			if (contents != null) {
				c.tracker().update(contents);
			}
			c.inGap = gap;
			c.full = r.full();
			return away;
		}
		long ticks = r.gameTick() - c.lastTick;
		if (ticks <= 0) {
			// paused world or the same tick: keep the real clock in step so the pause is not lag
			c.lastNanos = r.nanos();
			return null;
		}
		long wall = Math.max(0, (r.nanos() - c.lastNanos) / 1_000_000L);
		Map<String, Long> gains = Map.of();
		if (contents != null) {
			Map<String, Long> g = c.tracker().update(contents);
			if (!gap) {
				gains = g;
			}
		}
		Sample s = new Sample(c.lastTick, r.gameTick(), wall, r.epochMillis(), gap, gains, 0, 0);
		c.series.add(s);
		c.lastTick = r.gameTick();
		c.lastNanos = r.nanos();
		c.inGap = gap;
		c.full = r.full();
		return s;
	}

	private static Map<String, Long> filter(Counter c, Map<String, Long> contents) {
		if (c.filter == null || c.filter.isEmpty()) {
			return contents;
		}
		Map<String, Long> out = new LinkedHashMap<>();
		for (String id : c.filter) {
			Long v = contents.get(id);
			if (v != null) {
				out.put(id, v);
			}
		}
		return out;
	}
}
