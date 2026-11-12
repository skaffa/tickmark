package dev.tickmark.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Trend, burstiness and sparkline of one item's per-minute rate (PLAN.md section 5). Only buckets
 * measured for at least half a minute take part, so gaps do not look like a falling rate.
 */
public final class Trend {
	/** Minimum usable buckets before a trend is given. */
	public static final int MIN_POINTS = 5;
	/** Default threshold: change over the window, relative to the mean, below which a rate is stable. */
	public static final double DEFAULT_THRESHOLD = 0.10;
	/** Sparkline levels 0..7; -1 is a gap. */
	public static final int LEVELS = 8;

	public enum Direction {
		WARMING_UP, STABLE, RISING, FALLING
	}

	/** @param change slope times window length divided by the mean (e.g. 0.2 = 20% rise over the window) */
	public record Result(Direction direction, double change, double variation, int points) {
	}

	private Trend() {
	}

	/** Items per tick in a bucket, or NaN when the bucket was not measured enough. */
	public static double bucketRate(Bucket b, String item) {
		if (b.measuredTicks < Series.BUCKET_TICKS / 2) {
			return Double.NaN;
		}
		return (double) b.count(item) / b.measuredTicks;
	}

	public static Result of(List<Bucket> buckets, String item) {
		return of(buckets, item, DEFAULT_THRESHOLD);
	}

	public static Result of(List<Bucket> buckets, String item, double threshold) {
		List<double[]> pts = new ArrayList<>();
		for (Bucket b : buckets) {
			double r = bucketRate(b, item);
			if (!Double.isNaN(r)) {
				pts.add(new double[] {b.index, r});
			}
		}
		int n = pts.size();
		if (n < MIN_POINTS) {
			return new Result(Direction.WARMING_UP, Double.NaN, Double.NaN, n);
		}
		double mx = 0;
		double my = 0;
		for (double[] p : pts) {
			mx += p[0];
			my += p[1];
		}
		mx /= n;
		my /= n;
		double sxy = 0;
		double sxx = 0;
		double syy = 0;
		for (double[] p : pts) {
			sxy += (p[0] - mx) * (p[1] - my);
			sxx += (p[0] - mx) * (p[0] - mx);
			syy += (p[1] - my) * (p[1] - my);
		}
		if (my <= 0) {
			return new Result(Direction.STABLE, 0, 0, n);
		}
		double slope = sxx == 0 ? 0 : sxy / sxx;
		double span = pts.getLast()[0] - pts.getFirst()[0];
		double change = slope * span / my;
		double variation = Math.sqrt(syy / n) / my;
		Direction d = change > threshold ? Direction.RISING : change < -threshold ? Direction.FALLING : Direction.STABLE;
		return new Result(d, change, variation, n);
	}

	/**
	 * Sparkline levels for the minutes {@code firstIndex..lastIndex}: 0..7 scaled to the highest
	 * minute, -1 for a minute that was a gap or missing.
	 */
	public static int[] sparkline(List<Bucket> buckets, String item, long firstIndex, long lastIndex) {
		int len = (int) Math.max(0, lastIndex - firstIndex + 1);
		double[] rates = new double[len];
		java.util.Arrays.fill(rates, Double.NaN);
		for (Bucket b : buckets) {
			if (b.index >= firstIndex && b.index <= lastIndex) {
				rates[(int) (b.index - firstIndex)] = bucketRate(b, item);
			}
		}
		double max = 0;
		for (double r : rates) {
			if (!Double.isNaN(r)) {
				max = Math.max(max, r);
			}
		}
		int[] out = new int[len];
		for (int i = 0; i < len; i++) {
			if (Double.isNaN(rates[i])) {
				out[i] = -1;
			} else if (max <= 0) {
				out[i] = 0;
			} else {
				out[i] = (int) Math.min(LEVELS - 1, Math.round(rates[i] / max * (LEVELS - 1)));
			}
		}
		return out;
	}
}
