package dev.tallymark.core;

import java.util.Map;

/**
 * One measurement step of a counter: the game ticks it covers, the real time they took, and the items
 * that arrived.
 *
 * @param fromTick  game time at the start (exclusive)
 * @param toTick    game time at the end (inclusive); a step with {@code toTick <= fromTick} is ignored
 * @param wallMillis real milliseconds the step took (used for the real-time rate and the TPS note)
 * @param endEpochMillis wall clock time at the end of the step, for the CSV
 * @param gap       true when the counter could not be measured (chunks not loaded, paused world, other
 *                  dimension, out of range): the ticks and items are left out of the rate
 * @param gains     items that arrived, by item id; never negative
 * @param lost      items seen to disappear without reaching the counter (despawn, lava): shown, not rated
 * @param unknown   items that disappeared for a reason Tallymark could not tell
 */
public record Sample(long fromTick, long toTick, long wallMillis, long endEpochMillis, boolean gap,
		Map<String, Long> gains, long lost, long unknown) {

	public Sample {
		gains = gains == null ? Map.of() : Map.copyOf(gains);
		if (wallMillis < 0) {
			wallMillis = 0;
		}
	}

	public static Sample of(long fromTick, long toTick, long wallMillis, Map<String, Long> gains) {
		return new Sample(fromTick, toTick, wallMillis, 0, false, gains, 0, 0);
	}

	public static Sample gap(long fromTick, long toTick, long wallMillis) {
		return new Sample(fromTick, toTick, wallMillis, 0, true, Map.of(), 0, 0);
	}

	public long ticks() {
		return Math.max(0, toTick - fromTick);
	}
}
