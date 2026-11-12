package dev.tickmark.client;

import dev.tickmark.core.Counter;
import dev.tickmark.core.CounterSet;
import dev.tickmark.core.Pos;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Marking counters from the crosshair: containers, groups and two-click boxes (PLAN.md section 7). */
public final class Marking {
	/** First corner of a box being marked, or null; box mode is on while {@link #boxMode} is true. */
	static Pos boxCorner;
	static boolean boxMode;

	private Marking() {
	}

	static BlockPos target(Minecraft mc) {
		HitResult hit = mc.hitResult;
		if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
			return bh.getBlockPos();
		}
		return null;
	}

	static Pos pos(BlockPos p) {
		return new Pos(p.getX(), p.getY(), p.getZ());
	}

	/** The mark key: next box corner in box mode, else the container under the crosshair. */
	static void key(Minecraft mc, boolean forceNew) {
		if (boxMode) {
			BlockPos t = target(mc);
			boxCorner(mc, t != null ? pos(t) : pos(mc.player.blockPosition()));
			return;
		}
		BlockPos t = target(mc);
		if (t == null) {
			Tickmark.say("look at a chest, barrel, hopper or other container");
			return;
		}
		markContainer(mc, t, forceNew);
	}

	static void startBox() {
		boxMode = true;
		boxCorner = null;
		Tickmark.say("box: press the mark key on the first corner");
	}

	static void boxCorner(Minecraft mc, Pos p) {
		if (boxCorner == null) {
			boxCorner = p;
			Tickmark.say("box: first corner " + p + ", now the opposite corner");
			return;
		}
		Pos a = boxCorner;
		boxMode = false;
		boxCorner = null;
		markBox(mc, a, p);
	}

	static Optional<Counter> markBox(Minecraft mc, Pos a, Pos b) {
		Counter c = Counter.box(Tickmark.dimension(mc.level), a, b);
		if (c.boxVolume() > CounterSet.MAX_BOX_VOLUME) {
			Tickmark.say("box too big (" + c.boxVolume() + " blocks, max " + CounterSet.MAX_BOX_VOLUME + ")");
			return Optional.empty();
		}
		Tickmark.counters.add(c);
		Tickmark.changed();
		Tickmark.say("counter " + c.label() + ": box of " + c.boxVolume() + " blocks" + modeNote(mc));
		return Optional.of(c);
	}

	public static Optional<Counter> markContainer(Minecraft mc, BlockPos t, boolean forceNew) {
		BlockEntity be = mc.level.getBlockEntity(t);
		if (!(be instanceof Container)) {
			Tickmark.say("that is not a container; use /tickmark mark box for an area");
			return Optional.empty();
		}
		String dim = Tickmark.dimension(mc.level);
		List<Pos> blocks = new ArrayList<>();
		blocks.add(pos(t));
		BlockState s = mc.level.getBlockState(t);
		// a double chest is one container: mark both halves together
		if (s.getBlock() instanceof ChestBlock && s.hasProperty(ChestBlock.TYPE) && s.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			blocks.add(pos(t.relative(ChestBlock.getConnectedDirection(s))));
		}
		Optional<CounterSet.Marked> m = Tickmark.counters.mark(dim, blocks, forceNew);
		if (m.isEmpty()) {
			Tickmark.counters.holding(dim, pos(t)).ifPresent(c -> Tickmark.say("already in counter " + c.label()));
			return Optional.empty();
		}
		Counter c = m.get().counter();
		Tickmark.changed();
		if (m.get().created()) {
			Tickmark.say("counter " + c.label() + ": " + Tickmark.describe(c) + modeNote(mc));
		} else {
			Tickmark.say("added to counter " + c.label() + " (" + c.positions.size() + " containers)");
		}
		return Optional.of(c);
	}

	private static String modeNote(Minecraft mc) {
		return Tickmark.exact(mc) ? " (exact)" : " (estimated from item entities; open it now and then for an exact check)";
	}

	/** Counts what the player picks up, in this dimension: for AFK farms where the player collects. */
	static Optional<Counter> markSelf(Minecraft mc) {
		String dim = Tickmark.dimension(mc.level);
		for (Counter c : Tickmark.counters.inDimension(dim)) {
			if (c.kind == Counter.Kind.SELF) {
				Tickmark.say("counter " + c.label() + " already counts your pickups here");
				return Optional.empty();
			}
		}
		Counter c = new Counter();
		c.dimension = dim;
		c.kind = Counter.Kind.SELF;
		Tickmark.counters.add(c);
		Tickmark.changed();
		Tickmark.say("counter " + c.label() + ": items you pick up in this dimension");
		return Optional.of(c);
	}
}
