package dev.tickmark.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A marked farm output (PLAN.md section 6): one container, a group of containers, a box area, or the
 * player's own inventory. Plain fields for Gson; {@code transient} fields are per-session state.
 */
public final class Counter {
	public enum Kind {
		CONTAINER, GROUP, BOX, SELF
	}

	public int id;
	public String name = "";
	/** Dimension id, e.g. {@code minecraft:overworld}. */
	public String dimension = "";
	public Kind kind = Kind.CONTAINER;
	/** Marked containers (CONTAINER / GROUP). */
	public List<Pos> positions = new ArrayList<>();
	/** Inclusive block corners (BOX). */
	public Pos boxMin;
	public Pos boxMax;
	public boolean paused;
	/** Count items taken out as negative output (PLAN.md 3.1, net mode). */
	public boolean net;
	/** When not empty, only these item ids are counted. */
	public List<String> filter = new ArrayList<>();
	public Series series = new Series();

	/** Diffs successive readings; rebuilt each session. */
	public transient GainTracker tracker;
	/** Game tick and {@code System.nanoTime()} of the last reading this session, or MIN_VALUE. */
	public transient long lastTick = Long.MIN_VALUE;
	public transient long lastNanos;
	/** How the counter is measured right now; null when it is not measured (e.g. multiplayer before M3). */
	public transient Method method;
	/** Last reading found the chunks not ticking. */
	public transient boolean inGap;
	/** Last reading found every slot of the containers full. */
	public transient boolean full;
	/** Set after a pause or a group change: the next first reading adds no "time away" gap. */
	public transient boolean quietRestart;

	public Counter() {
	}

	public GainTracker tracker() {
		if (tracker == null) {
			tracker = new GainTracker(net);
		}
		return tracker;
	}

	/** Forgets the session state, so the next reading is a new baseline. */
	public void restart() {
		restart(false);
	}

	/**
	 * Forgets the session state. {@code quiet}: the time until the next reading is not a gap (pause,
	 * group change); otherwise the time since the last saved sample is a gap (rejoining a world).
	 */
	public void restart(boolean quiet) {
		quietRestart = quiet;
		tracker = null;
		lastTick = Long.MIN_VALUE;
		lastNanos = 0;
		inGap = false;
		full = false;
	}

	public String label() {
		return name == null || name.isEmpty() ? "#" + id : "#" + id + " " + name;
	}

	/** Centre of the counter, for distance and labels. */
	public double[] centre() {
		if (kind == Kind.BOX && boxMin != null && boxMax != null) {
			return new double[] {(boxMin.x() + boxMax.x() + 1) / 2.0, (boxMin.y() + boxMax.y() + 1) / 2.0,
					(boxMin.z() + boxMax.z() + 1) / 2.0};
		}
		if (positions.isEmpty()) {
			return new double[] {0, 0, 0};
		}
		double x = 0;
		double y = 0;
		double z = 0;
		for (Pos p : positions) {
			x += p.x() + 0.5;
			y += p.y() + 0.5;
			z += p.z() + 0.5;
		}
		int n = positions.size();
		return new double[] {x / n, y / n, z / n};
	}

	public double distanceSq(double px, double py, double pz) {
		double[] c = centre();
		double dx = c[0] - px;
		double dy = c[1] - py;
		double dz = c[2] - pz;
		return dx * dx + dy * dy + dz * dz;
	}

	public boolean contains(Pos p) {
		if (kind == Kind.BOX && boxMin != null && boxMax != null) {
			return p.x() >= boxMin.x() && p.x() <= boxMax.x() && p.y() >= boxMin.y() && p.y() <= boxMax.y()
					&& p.z() >= boxMin.z() && p.z() <= boxMax.z();
		}
		return positions.contains(p);
	}

	/** True when this counter can take {@code p} into its group: a container counter it touches. */
	public boolean canJoin(String dim, Pos p) {
		if (!dimension.equals(dim) || (kind != Kind.CONTAINER && kind != Kind.GROUP) || positions.contains(p)) {
			return false;
		}
		for (Pos q : positions) {
			if (q.touches(p)) {
				return true;
			}
		}
		return false;
	}

	/** Builds a box counter from two corners in any order. */
	public static Counter box(String dim, Pos a, Pos b) {
		Counter c = new Counter();
		c.dimension = dim;
		c.kind = Kind.BOX;
		c.boxMin = new Pos(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()));
		c.boxMax = new Pos(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
		return c;
	}

	public static Counter container(String dim, List<Pos> blocks) {
		Counter c = new Counter();
		c.dimension = dim;
		c.positions.addAll(blocks);
		c.kind = blocks.size() > 1 ? Kind.GROUP : Kind.CONTAINER;
		return c;
	}

	/** Block count of a box; 0 for other kinds. */
	public long boxVolume() {
		if (kind != Kind.BOX || boxMin == null || boxMax == null) {
			return 0;
		}
		return (long) (boxMax.x() - boxMin.x() + 1) * (boxMax.y() - boxMin.y() + 1) * (boxMax.z() - boxMin.z() + 1);
	}
}
