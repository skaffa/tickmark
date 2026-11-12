package dev.tickmark.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** All counters of one world (every dimension). Plain fields for Gson. */
public final class CounterSet {
	public static final int MAX_BOX_VOLUME = 64 * 64 * 64;

	public int nextId = 1;
	public List<Counter> counters = new ArrayList<>();
	/** Counter the player marked last; new adjacent containers join it. */
	public int lastMarked;

	public Counter add(Counter c) {
		c.id = nextId++;
		counters.add(c);
		lastMarked = c.id;
		return c;
	}

	public Optional<Counter> get(int id) {
		return counters.stream().filter(c -> c.id == id).findFirst();
	}

	public boolean remove(int id) {
		return counters.removeIf(c -> c.id == id);
	}

	/** The counter that already holds this block, if any. */
	public Optional<Counter> holding(String dim, Pos p) {
		return counters.stream().filter(c -> c.dimension.equals(dim) && c.kind != Counter.Kind.BOX && c.positions.contains(p))
				.findFirst();
	}

	/**
	 * Marks a container (with the other half of a double chest, if any). It joins the last marked
	 * counter when it touches it, unless {@code forceNew}. Returns the counter and whether it is new;
	 * empty when the block is already marked.
	 */
	public Optional<Marked> mark(String dim, List<Pos> blocks, boolean forceNew) {
		for (Pos p : blocks) {
			if (holding(dim, p).isPresent()) {
				return Optional.empty();
			}
		}
		if (!forceNew) {
			Optional<Counter> last = get(lastMarked);
			if (last.isPresent() && blocks.stream().anyMatch(p -> last.get().canJoin(dim, p))) {
				Counter c = last.get();
				c.positions.addAll(blocks);
				c.kind = Counter.Kind.GROUP;
				// the group's contents changed: start a new baseline, keep the history
				c.restart(true);
				return Optional.of(new Marked(c, false));
			}
		}
		return Optional.of(new Marked(add(Counter.container(dim, blocks)), true));
	}

	public record Marked(Counter counter, boolean created) {
	}

	public List<Counter> inDimension(String dim) {
		return counters.stream().filter(c -> c.dimension.equals(dim)).toList();
	}

	/** Counters in this dimension, nearest first. */
	public List<Counter> nearest(String dim, double x, double y, double z) {
		List<Counter> out = new ArrayList<>(inDimension(dim));
		out.sort(Comparator.comparingDouble(c -> c.distanceSq(x, y, z)));
		return out;
	}
}
