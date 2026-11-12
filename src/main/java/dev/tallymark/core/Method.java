package dev.tallymark.core;

/** How a counter measures (PLAN.md section 3); the tag is shown in the HUD and the CSV. */
public enum Method {
	EXACT("EXACT", true),
	ENTITY("ENTITY ~", false),
	INVENTORY("INV", true),
	SNAPSHOT("SNAPSHOT", true);

	private final String tag;
	private final boolean exact;

	Method(String tag, boolean exact) {
		this.tag = tag;
		this.exact = exact;
	}

	public String tag() {
		return tag;
	}

	public boolean exact() {
		return exact;
	}

	/** Lower-case name for files. */
	public String id() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}
