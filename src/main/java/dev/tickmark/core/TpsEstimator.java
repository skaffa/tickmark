package dev.tickmark.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Estimates the server's ticks per second from game time stamps and the real time they arrived.
 * In multiplayer the stamps come from {@code ClientboundSetTimePacket}, which a
 * 26.3 server sends every 20 ticks.
 */
public final class TpsEstimator {
	private record Point(long gameTime, long nanos) {
	}

	private final long windowNanos;
	private final Deque<Point> points = new ArrayDeque<>();

	public TpsEstimator(long windowNanos) {
		this.windowNanos = windowNanos;
	}

	/** 30 seconds of history. */
	public TpsEstimator() {
		this(30_000_000_000L);
	}

	public void add(long gameTime, long nanos) {
		Point lastPoint = points.peekLast();
		if (lastPoint != null && (gameTime < lastPoint.gameTime || nanos <= lastPoint.nanos)) {
			// time went back (new world, reconnect) or the clock is broken: start over
			points.clear();
		}
		points.addLast(new Point(gameTime, nanos));
		while (points.size() > 2 && nanos - points.peekFirst().nanos > windowNanos) {
			points.removeFirst();
		}
	}

	public void clear() {
		points.clear();
	}

	/** Ticks per real second over the window; NaN until two stamps at least one second apart exist. */
	public double tps() {
		if (points.size() < 2) {
			return Double.NaN;
		}
		Point a = points.peekFirst();
		Point b = points.peekLast();
		long dn = b.nanos - a.nanos;
		if (dn < 1_000_000_000L) {
			return Double.NaN;
		}
		return (b.gameTime - a.gameTime) * 1e9 / dn;
	}

	/** True when the server runs more than {@code tolerance} (e.g. 0.05) below the target tick rate. */
	public boolean slow(double targetTps, double tolerance) {
		double t = tps();
		return !Double.isNaN(t) && t < targetTps * (1 - tolerance);
	}
}
