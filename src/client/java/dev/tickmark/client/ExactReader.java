package dev.tickmark.client;

import dev.tickmark.core.Counter;
import dev.tickmark.core.Method;
import dev.tickmark.core.Pos;
import dev.tickmark.core.Sampling;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exact mode: in singleplayer and on a LAN host the server runs in this process, so the
 * counters' containers are read on the server thread, as Gauge's ServerPeek does. Every
 * {@code sampleTicks} client ticks one task reads all counters (every dimension) and publishes the
 * readings; the client thread applies them one tick later.
 */
final class ExactReader {
	/** What the server thread needs to read one counter: a copy, so it never touches client objects. */
	private record Job(int id, String dimension, Counter.Kind kind, List<Pos> positions, Pos min, Pos max) {
	}

	private static volatile Map<Integer, Sampling.Reading> results;
	private static volatile boolean pending;
	private static int clientTicks;

	private ExactReader() {
	}

	static void clear() {
		results = null;
		clientTicks = 0;
	}

	static void tick(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) {
			return;
		}
		Map<Integer, Sampling.Reading> ready = results;
		if (ready != null) {
			results = null;
			apply(ready);
		}
		if (++clientTicks < Tickmark.config.sampleTicks || pending) {
			return;
		}
		clientTicks = 0;
		List<Job> jobs = new ArrayList<>();
		for (Counter c : Tickmark.counters.counters) {
			c.method = c.kind == Counter.Kind.SELF ? null : Method.EXACT;
			if (c.kind != Counter.Kind.SELF && !c.paused) {
				jobs.add(new Job(c.id, c.dimension, c.kind, List.copyOf(c.positions), c.boxMin, c.boxMax));
			}
		}
		if (jobs.isEmpty()) {
			return;
		}
		pending = true;
		server.execute(() -> {
			try {
				Map<Integer, Sampling.Reading> out = new HashMap<>();
				long epoch = System.currentTimeMillis();
				for (Job j : jobs) {
					ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(j.dimension)));
					if (level != null) {
						out.put(j.id, read(level, j, epoch));
					}
				}
				results = out;
			} catch (RuntimeException e) {
				Tickmark.LOG.warn("exact read failed", e);
			} finally {
				pending = false;
			}
		});
	}

	private static void apply(Map<Integer, Sampling.Reading> readings) {
		boolean any = false;
		for (Counter c : Tickmark.counters.counters) {
			Sampling.Reading r = readings.get(c.id);
			if (r != null && Sampling.apply(c, r) != null) {
				any = true;
			}
		}
		if (any) {
			Tickmark.changed();
		}
	}

	// ---- server thread ----

	private static Sampling.Reading read(ServerLevel level, Job j, long epoch) {
		long tick = level.getGameTime();
		long nanos = System.nanoTime();
		Map<String, Long> items = new LinkedHashMap<>();
		boolean ticking = true;
		boolean full = true;
		boolean anyContainer = false;
		if (j.kind == Counter.Kind.BOX) {
			int cx0 = j.min.x() >> 4;
			int cz0 = j.min.z() >> 4;
			int cx1 = j.max.x() >> 4;
			int cz1 = j.max.z() >> 4;
			for (int cx = cx0; cx <= cx1; cx++) {
				for (int cz = cz0; cz <= cz1; cz++) {
					LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
					if (chunk == null) {
						return new Sampling.Reading(tick, nanos, epoch, false, null, false);
					}
					if (!ticking(level, new BlockPos(cx * 16 + 8, j.min.y(), cz * 16 + 8))) {
						ticking = false;
					}
					for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
						BlockPos p = e.getKey();
						if (inBox(p, j) && e.getValue() instanceof Container c) {
							add(items, c);
						}
					}
				}
			}
			AABB box = new AABB(j.min.x(), j.min.y(), j.min.z(), j.max.x() + 1, j.max.y() + 1, j.max.z() + 1);
			for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box, Entity::isAlive)) {
				addStack(items, e.getItem());
			}
			// container carts and chest boats parked in the area (a hopper-minecart collector)
			for (Entity e : level.getEntities((Entity) null, box, e -> e instanceof Container && e.isAlive())) {
				add(items, (Container) e);
			}
			return new Sampling.Reading(tick, nanos, epoch, ticking, items, false);
		}
		for (Pos p : j.positions) {
			BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
			if (level.getChunkSource().getChunkNow(p.x() >> 4, p.z() >> 4) == null) {
				return new Sampling.Reading(tick, nanos, epoch, false, null, false);
			}
			if (!ticking(level, pos)) {
				ticking = false;
			}
			BlockEntity be = level.getBlockEntity(pos);
			if (be instanceof Container c) {
				anyContainer = true;
				add(items, c);
				full &= isFull(c);
			}
		}
		return new Sampling.Reading(tick, nanos, epoch, ticking, anyContainer ? items : null, anyContainer && full);
	}

	private static boolean ticking(ServerLevel level, BlockPos pos) {
		return level.shouldTickBlocksAt(ChunkPos.pack(pos)) && level.isPositionEntityTicking(pos);
	}

	private static boolean inBox(BlockPos p, Job j) {
		return p.getX() >= j.min.x() && p.getX() <= j.max.x() && p.getY() >= j.min.y() && p.getY() <= j.max.y()
				&& p.getZ() >= j.min.z() && p.getZ() <= j.max.z();
	}

	private static void add(Map<String, Long> items, Container c) {
		int n = c.getContainerSize();
		for (int i = 0; i < n; i++) {
			addStack(items, c.getItem(i));
		}
	}

	private static void addStack(Map<String, Long> items, ItemStack stack) {
		if (!stack.isEmpty()) {
			items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), (long) stack.getCount(), Long::sum);
		}
	}

	private static boolean isFull(Container c) {
		int n = c.getContainerSize();
		for (int i = 0; i < n; i++) {
			ItemStack s = c.getItem(i);
			if (s.isEmpty() || s.getCount() < Math.min(s.getMaxStackSize(), c.getMaxStackSize(s))) {
				return false;
			}
		}
		return n > 0;
	}
}
