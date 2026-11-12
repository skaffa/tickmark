package dev.tickmark.core.config;

import dev.tickmark.core.RateUnit;

/** Settings saved in {@code config/tickmark/config.json}. Plain fields for Gson; {@link #clamp()} fixes bad values. */
public final class TickmarkConfig {
	public enum Corner {
		TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
	}

	public enum HudCounters {
		NEAREST, PINNED, ALL
	}

	/** Sample windows offered in the settings screen, in minutes. */
	public static final int[] WINDOWS = {5, 15, 60};

	public boolean hudEnabled = true;
	public Corner hudCorner = Corner.TOP_RIGHT;
	public int hudOffsetX = 4;
	public int hudOffsetY = 4;
	public HudCounters hudCounters = HudCounters.NEAREST;
	/** Most items listed per counter (top by count). */
	public int maxItems = 5;
	public int windowMinutes = 15;
	public RateUnit unit = RateUnit.PER_HOUR;
	public boolean sparkline = true;
	public boolean worldLabels = true;
	/** Game ticks between exact-mode reads. */
	public int sampleTicks = 20;
	/** Show the real-time rate when it differs from the 20 TPS rate by more than this share. */
	public double lagNoteThreshold = 0.05;

	public TickmarkConfig clamp() {
		if (hudCorner == null) {
			hudCorner = Corner.TOP_RIGHT;
		}
		if (hudCounters == null) {
			hudCounters = HudCounters.NEAREST;
		}
		if (unit == null) {
			unit = RateUnit.PER_HOUR;
		}
		hudOffsetX = clamp(hudOffsetX, 0, 4000);
		hudOffsetY = clamp(hudOffsetY, 0, 4000);
		maxItems = clamp(maxItems, 1, 20);
		windowMinutes = clamp(windowMinutes, 1, 24 * 60);
		sampleTicks = clamp(sampleTicks, 5, 200);
		if (!(lagNoteThreshold >= 0 && lagNoteThreshold <= 1)) {
			lagNoteThreshold = 0.05;
		}
		return this;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
