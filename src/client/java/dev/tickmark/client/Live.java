package dev.tickmark.client;

import dev.tickmark.core.Counter;
import dev.tickmark.core.EntityLedger;
import dev.tickmark.core.Method;
import dev.tickmark.core.Pos;
import dev.tickmark.core.Sampling;
import dev.tickmark.core.TpsEstimator;

import it.unimi.dsi.fastutil.ints.IntList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The multiplayer methods (PLAN.md 3.2, 3.3) and the server clock (section 4). Entities are watched
 * every client tick; samples are closed when a {@code ClientboundSetTimePacket} arrives (every 20
 * server ticks), so the sample clock is the server's game time even when the server lags.
 *
 * <p>The inventory method (a counter marked with {@code /tickmark mark self}) runs in singleplayer too:
 * the integrated server sends the same take packets.
 */
public final class Live {
	/** Beyond this distance from the player the client may not see item entities (tracking range 6 chunks). */
	private static final double MAX_SIGHT = 80;
	/** Search margin around a zone, so items falling in are seen before they enter. */
	private static final double MARGIN = 1.5;

	public static final TpsEstimator TPS = new TpsEstimator();
	private static long serverTime = Long.MIN_VALUE;
	private static long serverNanos;
	private static boolean timeArrived;
	private static int clientTick;
	/** Counters' zones, refreshed each tick, so removal packets can be matched quickly. */
	private static final Map<Integer, AABB> ZONES = new HashMap<>();
	/** Per counter: whether the zone has a hopper or container (decides the automatic rule). */
	private static final Map<Integer, Boolean> HAS_COLLECTOR = new HashMap<>();

	/** Dev counters for the self-test log: entities seen, removal ids hooked, of which tracked, take packets. */
	public static long debugSeen;
	public static long debugRemoved;
	public static long debugRemovedTracked;
	public static long debugTakes;
	/** Logs removals that are not at a collector (self-test runs only). */
	private static final boolean DEBUG = !System.getProperty("tickmark.selftest", "").isEmpty();

	private Live() {
	}

	static void clear() {
		serverTime = Long.MIN_VALUE;
		timeArrived = false;
		TPS.clear();
		ZONES.clear();
		HAS_COLLECTOR.clear();
	}

	/** Latest server game time from a time packet, or MIN_VALUE before the first one. */
	public static long serverTime() {
		return serverTime;
	}

	/** Entity mode applies: multiplayer, a container or box counter. */
	static boolean entityMode(Minecraft mc, Counter c) {
		return !Tickmark.exact(mc) && c.kind != Counter.Kind.SELF;
	}

	// ---- packet hooks (client thread, before vanilla applies the packet) ----

	public static void onTime(long gameTime) {
		long nanos = System.nanoTime();
		TPS.add(gameTime, nanos);
		if (gameTime > serverTime || serverTime == Long.MIN_VALUE) {
			serverTime = gameTime;
			serverNanos = nanos;
			timeArrived = true;
		}
	}

	public static void onTake(int itemId, int collectorId, int amount) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			return;
		}
		debugTakes++;
		Entity taken = level.getEntity(itemId);
		for (Counter c : Tickmark.counters.counters) {
			if (c.ledger != null && (c.ledger.tracks(itemId) || lateSee(level, c, taken))) {
				c.ledger.taken(itemId, amount);
			}
		}
		if (collectorId == mc.player.getId() && level.getEntity(itemId) instanceof ItemEntity ie) {
			String item = id(ie);
			String dim = Tickmark.dimension(level);
			for (Counter c : Tickmark.counters.counters) {
				if (c.kind == Counter.Kind.SELF && c.dimension.equals(dim) && !c.paused) {
					c.ledger().add(item, amount);
				}
			}
		}
	}

	public static void onRemove(IntList ids) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			return;
		}
		double sight = sight(mc);
		ids.forEach(id -> {
			debugRemoved++;
			Entity e = level.getEntity(id);
			for (Counter c : Tickmark.counters.counters) {
				if (c.ledger == null || !c.ledger.tracks(id) && !lateSee(level, c, e)) {
					continue;
				}
				debugRemovedTracked++;
				boolean outOfSight = e == null || e.distanceTo(mc.player) > sight - 8
						|| !level.hasChunk(e.blockPosition().getX() >> 4, e.blockPosition().getZ() >> 4);
				boolean near = e instanceof ItemEntity ie && nearCollector(level, ie);
				if (DEBUG && !near && e != null) {
					BlockPos p = e.blockPosition();
					Tickmark.LOG.info("[live] removal not at a collector: id {} at {} (age {}, motion {}); blocks {} / {} / {} / {}", id,
							e.position(), e instanceof ItemEntity ie2 ? ie2.getAge() : -1, e.getDeltaMovement(),
							level.getBlockState(p).getBlock(), level.getBlockState(p.below()).getBlock(),
							level.getBlockState(p.below(2)).getBlock(), level.getBlockState(p.below(3)).getBlock());
				}
				c.ledger.removed(new EntityLedger.Removal(id, outOfSight, near));
			}
		});
	}

	/**
	 * An item the per-tick scan never saw: an idle hopper takes an item the server tick after it
	 * spawns, so its spawn and removal reach the client in the same frame. If it is in the counter's
	 * zone, record it now (before the removal or take is applied). Returns true when recorded.
	 */
	private static boolean lateSee(ClientLevel level, Counter c, Entity e) {
		if (!(e instanceof ItemEntity ie) || !c.dimension.equals(Tickmark.dimension(level))) {
			return false;
		}
		AABB zone = ZONES.get(c.id);
		if (zone == null || !zone.inflate(MARGIN).contains(ie.position())) {
			return false;
		}
		c.ledger.see(new EntityLedger.Seen(ie.getId(), id(ie), ie.getItem().getCount(), zone.contains(ie.position()),
				nearCollector(level, ie), ie.getAge()));
		return true;
	}

	// ---- per tick ----

	static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		clientTick++;
		String dim = Tickmark.dimension(level);
		for (Counter c : Tickmark.counters.counters) {
			if (c.kind == Counter.Kind.SELF) {
				c.method = Method.INVENTORY;
				continue;
			}
			if (!entityMode(mc, c)) {
				continue;
			}
			c.method = Method.ENTITY;
			if (!c.dimension.equals(dim) || c.paused) {
				continue;
			}
			AABB zone = zone(c);
			ZONES.put(c.id, zone);
			if (clientTick % 100 == 1 || !HAS_COLLECTOR.containsKey(c.id)) {
				HAS_COLLECTOR.put(c.id, hasCollector(level, zone));
			}
			EntityLedger ledger = c.ledger();
			ledger.rule(c.rule != null ? c.rule : HAS_COLLECTOR.get(c.id) ? EntityLedger.Rule.COLLECTED : EntityLedger.Rule.ARRIVED);
			ledger.tick(clientTick);
			Set<Integer> seen = new HashSet<>();
			for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, zone.inflate(MARGIN), Entity::isAlive)) {
				seen.add(ie.getId());
				debugSeen++;
				ledger.see(new EntityLedger.Seen(ie.getId(), id(ie), ie.getItem().getCount(), zone.contains(ie.position()),
						nearCollector(level, ie), ie.getAge()));
			}
			ledger.forgetExcept(seen);
			ledger.settle();
		}
		if (timeArrived) {
			timeArrived = false;
			sample(mc, dim);
		}
	}

	/** Closes a sample for every live counter at the server time of the packet that just arrived. */
	private static void sample(Minecraft mc, String dim) {
		long epoch = System.currentTimeMillis();
		boolean changed = false;
		for (Counter c : Tickmark.counters.counters) {
			boolean self = c.kind == Counter.Kind.SELF;
			if (!self && !entityMode(mc, c)) {
				continue;
			}
			if (c.paused) {
				if (c.lastTick != Long.MIN_VALUE) {
					c.restart(true);
				}
				continue;
			}
			if (c.lastTick == Long.MIN_VALUE) {
				Sampling.begin(c, serverTime, serverNanos);
				c.ledger().take();
				changed = true;
				continue;
			}
			if (serverTime <= c.lastTick) {
				continue;
			}
			EntityLedger.Events ev = c.ledger().take();
			boolean gap = !c.dimension.equals(dim) || (!self && (ev.blind || !visible(mc, c)));
			Sampling.events(c, serverTime, serverNanos, epoch, gap, ev.gains, ev.lost, ev.unknown);
			changed = true;
		}
		if (changed) {
			Tickmark.changed();
		}
	}

	// ---- helpers ----

	static String id(ItemEntity ie) {
		return BuiltInRegistries.ITEM.getKey(ie.getItem().getItem()).toString();
	}

	/** Blocks the counter watches for items: a box as marked; containers plus the 3 blocks above them (the hopper that feeds them). */
	static AABB zone(Counter c) {
		if (c.kind == Counter.Kind.BOX) {
			return new AABB(c.boxMin.x(), c.boxMin.y(), c.boxMin.z(), c.boxMax.x() + 1, c.boxMax.y() + 1, c.boxMax.z() + 1);
		}
		AABB box = null;
		for (Pos p : c.positions) {
			AABB b = new AABB(p.x(), p.y(), p.z(), p.x() + 1, p.y() + 4, p.z() + 1);
			box = box == null ? b : box.minmax(b);
		}
		return box == null ? new AABB(0, 0, 0, 0, 0, 0) : box;
	}

	/** How far the client sees item entities: the tracking range, capped by the render distance. */
	static double sight(Minecraft mc) {
		return Math.min(MAX_SIGHT, mc.options.getEffectiveRenderDistance() * 16.0);
	}

	/** The zone is loaded, ticking (inside simulation distance) and close enough to see its items. */
	static boolean visible(Minecraft mc, Counter c) {
		ClientLevel level = mc.level;
		AABB z = ZONES.getOrDefault(c.id, zone(c));
		Vec3 centre = z.getCenter();
		if (centre.distanceTo(mc.player.position()) > sight(mc) - 8) {
			return false;
		}
		int sim = level.getServerSimulationDistance();
		int pcx = mc.player.blockPosition().getX() >> 4;
		int pcz = mc.player.blockPosition().getZ() >> 4;
		for (int cx = (int) Math.floor(z.minX) >> 4; cx <= ((int) Math.floor(z.maxX - 1e-6) >> 4); cx++) {
			for (int cz = (int) Math.floor(z.minZ) >> 4; cz <= ((int) Math.floor(z.maxZ - 1e-6) >> 4); cz++) {
				if (!level.hasChunk(cx, cz)) {
					return false;
				}
				if (sim > 0 && Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz)) > sim - 1) {
					return false;
				}
			}
		}
		return true;
	}

	/** How far down an item's landing spot is looked for. */
	private static final int LANDING_DEPTH = 6;

	/**
	 * In or over a hopper or other container: the first block with a collision shape straight below the
	 * item (through air, up to {@value #LANDING_DEPTH} blocks) is a container, so a falling item lands
	 * there. Where the item is right now is not enough: the client's copy can trail the server's by
	 * several ticks (at a lowered {@code /tick rate} it may not have moved at all). Or next to a hopper
	 * minecart.
	 */
	private static boolean nearCollector(ClientLevel level, ItemEntity ie) {
		BlockPos p = ie.blockPosition();
		for (int k = 0; k <= LANDING_DEPTH; k++) {
			BlockPos b = p.below(k);
			if (level.getBlockEntity(b) instanceof Container) {
				return true;
			}
			if (!level.getBlockState(b).getCollisionShape(level, b).isEmpty()) {
				break; // lands on something that is not a container
			}
		}
		return !level.getEntitiesOfClass(MinecartHopper.class, ie.getBoundingBox().inflate(1.0)).isEmpty();
	}

	private static boolean hasCollector(ClientLevel level, AABB zone) {
		BlockPos min = BlockPos.containing(zone.minX, zone.minY - 1, zone.minZ);
		BlockPos max = BlockPos.containing(zone.maxX - 1e-6, zone.maxY - 1e-6, zone.maxZ - 1e-6);
		long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
		if (volume > 32_768) {
			return true; // big areas: assume a collector rather than scan every block each few seconds
		}
		for (BlockPos p : BlockPos.betweenClosed(min, max)) {
			if (level.getBlockEntity(p) instanceof Container) {
				return true;
			}
		}
		return !level.getEntitiesOfClass(MinecartHopper.class, zone).isEmpty();
	}
}
