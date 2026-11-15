package dev.tickmark.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A counter's history: per-game-minute buckets for the last 24 game hours, plus totals since the last
 * reset. All time is game time, so server lag does not lower the rate. Gap ticks
 * are kept apart from measured ticks and never count as time with zero output.
 *
 * <p>Plain fields so Gson can save it; not thread-safe.
 */
public final class Series {
	/** One bucket = one game minute at 20 TPS. */
	public static final long BUCKET_TICKS = 1200;
	/** 24 game hours of buckets. */
	public static final int MAX_BUCKETS = 1440;

	private final List<Bucket> buckets = new ArrayList<>();
	private final Bucket totals = new Bucket(-1);
	/** Game tick of the last sample's end, or {@code Long.MIN_VALUE} before the first one. */
	private long lastTick = Long.MIN_VALUE;

	public void add(Sample s) {
		long ticks = s.ticks();
		if (ticks <= 0) {
			return;
		}
		long from = s.fromTick();
		long to = s.toTick();
		// Only the last 24 h of a long sample (a day away) can land in buckets; the rest goes to totals.
		long start = Math.max(from, to - MAX_BUCKETS * BUCKET_TICKS);
		long skipped = start - from;
		long wallSkipped = skipped == 0 ? 0 : s.wallMillis() * skipped / ticks;
		addTime(totals, skipped, wallSkipped, s.gap());

		long wallLeft = s.wallMillis() - wallSkipped;
		long t = start;
		while (t < to) {
			long idx = Math.floorDiv(t, BUCKET_TICKS);
			long end = Math.min(to, (idx + 1) * BUCKET_TICKS);
			long n = end - t;
			long wall = end == to ? wallLeft : s.wallMillis() * n / ticks;
			wallLeft -= wall;
			Bucket b = bucket(idx);
			if (b != null) {
				addTime(b, n, wall, s.gap());
				if (s.endEpochMillis() > 0) {
					b.endEpochMillis = Math.max(b.endEpochMillis, s.endEpochMillis());
				}
			}
			addTime(totals, n, wall, s.gap());
			t = end;
		}
		if (!s.gap()) {
			Bucket last = bucket(Math.floorDiv(to - 1, BUCKET_TICKS));
			for (Map.Entry<String, Long> e : s.gains().entrySet()) {
				long v = e.getValue() == null ? 0 : e.getValue();
				if (v <= 0) {
					continue;
				}
				if (last != null) {
					last.items.merge(e.getKey(), v, Long::sum);
				}
				totals.items.merge(e.getKey(), v, Long::sum);
			}
			if (last != null) {
				last.lost += s.lost();
				last.unknown += s.unknown();
			}
			totals.lost += s.lost();
			totals.unknown += s.unknown();
		}
		lastTick = Math.max(lastTick, to);
		prune();
	}

	private static void addTime(Bucket b, long ticks, long wall, boolean gap) {
		if (gap) {
			b.gapTicks += ticks;
		} else {
			b.measuredTicks += ticks;
			b.wallMillis += wall;
		}
	}

	/** Finds or creates the bucket; null when it is older than the kept history. */
	private Bucket bucket(long idx) {
		for (int i = buckets.size() - 1; i >= 0; i--) {
			Bucket b = buckets.get(i);
			if (b.index == idx) {
				return b;
			}
			if (b.index < idx) {
				Bucket nb = new Bucket(idx);
				buckets.add(i + 1, nb);
				return nb;
			}
		}
		if (!buckets.isEmpty() && idx < buckets.getLast().index - MAX_BUCKETS + 1) {
			return null;
		}
		Bucket nb = new Bucket(idx);
		buckets.addFirst(nb);
		return nb;
	}

	private void prune() {
		if (buckets.isEmpty()) {
			return;
		}
		long oldest = buckets.getLast().index - MAX_BUCKETS + 1;
		buckets.removeIf(b -> b.index < oldest);
	}

	public void reset() {
		buckets.clear();
		totals.measuredTicks = 0;
		totals.gapTicks = 0;
		totals.wallMillis = 0;
		totals.endEpochMillis = 0;
		totals.lost = 0;
		totals.unknown = 0;
		totals.items.clear();
		lastTick = Long.MIN_VALUE;
	}

	/** Game tick where the last sample ended, or {@code Long.MIN_VALUE}. */
	public long lastTick() {
		return lastTick;
	}

	public List<Bucket> buckets() {
		return List.copyOf(buckets);
	}

	/** Everything since the last reset. */
	public Window total() {
		return new Window(totals.measuredTicks, totals.gapTicks, totals.wallMillis, Map.copyOf(totals.items),
				totals.lost, totals.unknown, List.copyOf(buckets));
	}

	/**
	 * The last {@code windowTicks} game ticks before {@code nowTick}, in whole game minutes: the
	 * current (partial) minute plus enough earlier ones to reach the window length.
	 */
	public Window window(long nowTick, long windowTicks) {
		long last = Math.floorDiv(nowTick - 1, BUCKET_TICKS);
		long n = Math.max(1, (windowTicks + BUCKET_TICKS - 1) / BUCKET_TICKS);
		long first = last - n + 1;
		long measured = 0;
		long gap = 0;
		long wall = 0;
		long lost = 0;
		long unknown = 0;
		Map<String, Long> items = new LinkedHashMap<>();
		List<Bucket> in = new ArrayList<>();
		for (Bucket b : buckets) {
			if (b.index < first || b.index > last) {
				continue;
			}
			in.add(b);
			measured += b.measuredTicks;
			gap += b.gapTicks;
			wall += b.wallMillis;
			lost += b.lost;
			unknown += b.unknown;
			b.items.forEach((k, v) -> items.merge(k, v, Long::sum));
		}
		return new Window(measured, gap, wall, items, lost, unknown, in);
	}

	/** Summed statistics over a span of buckets. */
	public record Window(long measuredTicks, long gapTicks, long wallMillis, Map<String, Long> items, long lost,
			long unknown, List<Bucket> buckets) {

		public long count(String item) {
			return items.getOrDefault(item, 0L);
		}

		public long totalCount() {
			long sum = 0;
			for (long v : items.values()) {
				sum += v;
			}
			return sum;
		}

		/** Items per unit at 20 TPS (from game time); NaN when nothing was measured. */
		public double rate(String item, RateUnit unit) {
			return measuredTicks <= 0 ? Double.NaN : (double) count(item) * unit.ticks() / measuredTicks;
		}

		/** Items per unit of real time; NaN when no real time was recorded. */
		public double realRate(String item, RateUnit unit) {
			return wallMillis <= 0 ? Double.NaN : (double) count(item) * unit.millis() / wallMillis;
		}

		/** Average server ticks per real second over the measured time; NaN when unknown. */
		public double tps() {
			return wallMillis <= 0 ? Double.NaN : measuredTicks * 1000.0 / wallMillis;
		}

		/** Item ids, highest count first. */
		public List<String> itemsByCount() {
			List<String> ids = new ArrayList<>(items.keySet());
			ids.sort((a, b) -> Long.compare(count(b), count(a)));
			return ids;
		}
	}
}
