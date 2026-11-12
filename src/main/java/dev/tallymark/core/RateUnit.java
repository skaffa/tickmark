package dev.tallymark.core;

/** Display unit for rates. Ticks are at 20 TPS: one hour is 72,000 ticks. */
public enum RateUnit {
	PER_MINUTE("/min", 1_200),
	PER_HOUR("/h", 72_000),
	PER_DAY("/day", 1_728_000);

	private final String suffix;
	private final long ticks;

	RateUnit(String suffix, long ticks) {
		this.suffix = suffix;
		this.ticks = ticks;
	}

	public String suffix() {
		return suffix;
	}

	/** Game ticks in one unit at 20 TPS. */
	public long ticks() {
		return ticks;
	}

	/** Real milliseconds in one unit. */
	public long millis() {
		return ticks * 50;
	}
}
