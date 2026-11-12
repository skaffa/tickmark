package dev.tickmark.core;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns successive content readings of a counter (item id to count) into gains (PLAN.md section 3.1).
 *
 * <p>Default: only rises count. A drop (a player or hopper taking items out) moves the baseline down so
 * the items do not come back as output when the counter refills. In net mode a drop is a negative gain.
 */
public final class GainTracker {
	private final boolean net;
	private Map<String, Long> last;

	public GainTracker(boolean net) {
		this.net = net;
	}

	/** True until the first reading; the first reading is the baseline and gives no gain. */
	public boolean primed() {
		return last != null;
	}

	/** Forgets the baseline, e.g. after a gap: the next reading starts fresh. */
	public void rebase() {
		last = null;
	}

	/** Returns the change since the last reading; empty for the first reading. */
	public Map<String, Long> update(Map<String, Long> now) {
		Map<String, Long> out = new LinkedHashMap<>();
		if (last == null) {
			last = new HashMap<>(now);
			return out;
		}
		for (Map.Entry<String, Long> e : now.entrySet()) {
			long d = e.getValue() - last.getOrDefault(e.getKey(), 0L);
			if (d > 0 || (net && d < 0)) {
				out.put(e.getKey(), d);
			}
		}
		if (net) {
			for (Map.Entry<String, Long> e : last.entrySet()) {
				if (!now.containsKey(e.getKey()) && e.getValue() > 0) {
					out.put(e.getKey(), -e.getValue());
				}
			}
		}
		last = new HashMap<>(now);
		return out;
	}
}
