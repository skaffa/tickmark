package dev.tickmark.core;

/** A block position without Minecraft classes, for the core and the saved counters. */
public record Pos(int x, int y, int z) {
	/** True when the two blocks share a face. */
	public boolean touches(Pos o) {
		return Math.abs(x - o.x) + Math.abs(y - o.y) + Math.abs(z - o.z) == 1;
	}

	public double distanceSq(double px, double py, double pz) {
		double dx = x + 0.5 - px;
		double dy = y + 0.5 - py;
		double dz = z + 0.5 - pz;
		return dx * dx + dy * dy + dz * dz;
	}

	@Override
	public String toString() {
		return x + " " + y + " " + z;
	}
}
