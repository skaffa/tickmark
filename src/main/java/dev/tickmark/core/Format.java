package dev.tickmark.core;

import java.util.Locale;

/** Number and time formatting for the HUD, chat and labels. */
public final class Format {
	private Format() {
	}

	/** A rate: one decimal below 10, whole numbers with thousands separators above; "-" for NaN. */
	public static String rate(double v) {
		if (Double.isNaN(v) || Double.isInfinite(v)) {
			return "-";
		}
		if (Math.abs(v) < 10) {
			return String.format(Locale.ROOT, "%.1f", v);
		}
		return count(Math.round(v));
	}

	public static String count(long v) {
		return String.format(Locale.ROOT, "%,d", v);
	}

	/** Game ticks at 20 TPS as "45 s", "1 m 59 s", "12 m", "1 h 40 m", "2 d 3 h". */
	public static String duration(long ticks) {
		long s = Math.max(0, ticks) / 20;
		if (s < 60) {
			return s + " s";
		}
		long m = s / 60;
		if (m < 10) {
			return s % 60 == 0 ? m + " m" : m + " m " + (s % 60) + " s";
		}
		if (m < 60) {
			return m + " m";
		}
		long h = m / 60;
		if (h < 24) {
			return h + " h " + (m % 60) + " m";
		}
		return (h / 24) + " d " + (h % 24) + " h";
	}

	/** {@code minecraft:iron_ingot} becomes {@code iron_ingot}; other namespaces are kept. */
	public static String shortId(String id) {
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}
}
