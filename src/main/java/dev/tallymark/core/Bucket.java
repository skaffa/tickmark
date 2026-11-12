package dev.tallymark.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One game minute ({@value Series#BUCKET_TICKS} ticks) of a counter's history. Plain fields so Gson can
 * save it.
 */
public final class Bucket {
	/** Game minute index: start tick / {@value Series#BUCKET_TICKS}. */
	public long index;
	/** Ticks in this minute that were measured (not a gap). */
	public long measuredTicks;
	/** Ticks in this minute that were a gap. */
	public long gapTicks;
	/** Real milliseconds the measured ticks took. */
	public long wallMillis;
	/** Wall clock time of the last sample in this bucket, 0 if unknown. */
	public long endEpochMillis;
	public long lost;
	public long unknown;
	public Map<String, Long> items = new LinkedHashMap<>();

	public Bucket() {
	}

	Bucket(long index) {
		this.index = index;
	}

	public long startTick() {
		return index * Series.BUCKET_TICKS;
	}

	public long count(String item) {
		return items.getOrDefault(item, 0L);
	}

	public long total() {
		long sum = 0;
		for (long v : items.values()) {
			sum += v;
		}
		return sum;
	}
}
