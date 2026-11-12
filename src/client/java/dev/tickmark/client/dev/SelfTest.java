package dev.tickmark.client.dev;

import dev.tickmark.client.Marking;
import dev.tickmark.client.Tickmark;
import dev.tickmark.core.Counter;
import dev.tickmark.core.CounterSet;
import dev.tickmark.core.CounterStore;
import dev.tickmark.core.RateUnit;
import dev.tickmark.core.Series;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dev-only automated test (COMMON.md): {@code ./gradlew runClient -Pselftest=exact
 * --args="--quickPlaySingleplayer tmtest"}. Builds a rig with commands, marks it, waits for enough
 * items, checks the measured rate against the known rate, logs {@code SELFTEST PASS/FAIL} and quits.
 *
 * <p>Rig (exact mode): a repeating command block clock summons one iron ingot every {@value #PERIOD}
 * game ticks right above a hopper that feeds a chest. Known rate: 72,000 / {@value #PERIOD} per hour.
 */
public final class SelfTest {
	private static final String MODE = System.getProperty("tickmark.selftest", "");
	static final int PERIOD = 20;
	private static final double KNOWN_PER_HOUR = 72_000.0 / PERIOD;
	private static final double TOLERANCE = 0.02;
	/** Items to wait for before judging: enough that one item of phase error is under 1%. */
	private static final long TARGET_ITEMS = 120;
	private static final int TIMEOUT_TICKS = 20 * 60 * 12;
	private static final String ITEM = "minecraft:iron_ingot";

	private static final List<String> FAILURES = new ArrayList<>();
	private static int ticks;
	private static int stage;
	private static int stageStart;
	private static BlockPos chest;
	private static Counter counter;
	private static boolean done;

	private SelfTest() {
	}

	public static void init() {
		if (!MODE.isEmpty()) {
			Tickmark.LOG.info("[selftest] enabled ({})", MODE);
		}
	}

	public static void tick(Minecraft mc) {
		if (MODE.isEmpty() || done || mc.level == null || mc.player == null) {
			return;
		}
		ticks++;
		mc.options.pauseOnLostFocus = false;
		if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) {
			mc.gui.setScreen(null);
		}
		if (!MODE.equals("exact")) {
			finish(mc, "unknown self-test mode '" + MODE + "' (known: exact)");
			return;
		}
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) {
			finish(mc, "exact mode needs a singleplayer world (--quickPlaySingleplayer tmtest)");
			return;
		}
		int inStage = ticks - stageStart;
		switch (stage) {
			case 0 -> {
				if (ticks > 100) {
					build(mc, server);
					next();
				}
			}
			case 1 -> {
				// wait for the chest to reach the client (a slow laptop can take seconds), then mark it like a player would
				boolean arrived = mc.level.getBlockEntity(chest) instanceof net.minecraft.world.Container;
				if (!arrived && inStage > 600) {
					finish(mc, "rig chest not found at " + chest + " on the client");
					return;
				}
				if (arrived) {
					// reset any counters of earlier runs in this world
					Tickmark.counters = new CounterSet();
					Tickmark.changed();
					var marked = markChest(mc);
					if (marked == null) {
						finish(mc, "could not mark the chest");
						return;
					}
					counter = marked;
					Tickmark.LOG.info("[selftest] marked {} at {}", counter.label(), chest);
					next();
				}
			}
			case 2 -> {
				lookAt(mc, Vec3.atCenterOf(chest).add(0, 0.6, 0));
				long n = counter.series.total().count(ITEM);
				if (inStage % 400 == 0) {
					Tickmark.LOG.info("[selftest] {} items in {} ticks", n, counter.series.total().measuredTicks());
				}
				if (n >= TARGET_ITEMS) {
					next();
				} else if (inStage > TIMEOUT_TICKS) {
					finish(mc, "timeout: only " + n + " items counted");
				}
			}
			case 3 -> {
				lookAt(mc, Vec3.atCenterOf(chest).add(0, 0.6, 0));
				if (inStage == 5) {
					shot(mc, "exact-hud");
				}
				if (inStage == 30) {
					check(mc);
				}
			}
			default -> {
			}
		}
	}

	private static void next() {
		stage++;
		stageStart = ticks;
	}

	private static Counter markChest(Minecraft mc) {
		// the public path of the mark key: aim at the chest and mark it
		lookAt(mc, Vec3.atCenterOf(chest));
		mc.hitResult = new BlockHitResult(Vec3.atCenterOf(chest), net.minecraft.core.Direction.UP, chest, false);
		return Marking.markContainer(mc, chest, true).orElse(null);
	}

	private static void build(Minecraft mc, IntegratedServer server) {
		BlockPos base = mc.player.blockPosition().offset(4, 0, 0);
		int x = base.getX();
		int y = base.getY();
		int z = base.getZ();
		chest = new BlockPos(x, y, z);
		String item = "{Item:{id:\"" + ITEM + "\",count:1},Motion:[0.0d,0.0d,0.0d]}";
		List<String> cmds = List.of(
				"fill " + (x - 1) + " " + y + " " + (z - 2) + " " + (x + 6) + " " + (y + 4) + " " + (z + 2) + " air",
				"fill " + (x - 1) + " " + (y - 1) + " " + (z - 2) + " " + (x + 6) + " " + (y - 1) + " " + (z + 2) + " stone",
				"setblock " + x + " " + y + " " + z + " chest",
				"setblock " + x + " " + (y + 1) + " " + z + " hopper[facing=down]",
				"scoreboard objectives remove tm_clock",
				"scoreboard objectives add tm_clock dummy",
				"scoreboard players set #t tm_clock 0",
				"setblock " + (x + 3) + " " + y + " " + z + " repeating_command_block[facing=east]{auto:1b,Command:\"scoreboard players add #t tm_clock 1\"}",
				"setblock " + (x + 4) + " " + y + " " + z + " chain_command_block[facing=east]{auto:1b,Command:\"execute if score #t tm_clock matches "
						+ PERIOD + ".. run summon item " + (x + 0.5) + " " + (y + 2.2) + " " + (z + 0.5) + " " + item.replace("\"", "\\\"") + "\"}",
				"setblock " + (x + 5) + " " + y + " " + z + " chain_command_block[facing=east]{auto:1b,Command:\"execute if score #t tm_clock matches "
						+ PERIOD + ".. run scoreboard players set #t tm_clock 0\"}",
				"difficulty peaceful",
				"time set noon",
				"tp @p " + (x - 3.5) + " " + (y + 1) + " " + (z + 0.5));
		server.execute(() -> {
			// not suppressed: feedback and errors of each command go to the log
			var src = server.createCommandSourceStack();
			for (String c : cmds) {
				Tickmark.LOG.info("[selftest] /{}", c);
				server.getCommands().performPrefixedCommand(src, c);
			}
			Tickmark.LOG.info("[selftest] server sees {} at the chest position", server.overworld().getBlockState(chest));
		});
		Tickmark.LOG.info("[selftest] rig at {} (period {} ticks, known rate {}/h)", chest, PERIOD, KNOWN_PER_HOUR);
	}

	private static void check(Minecraft mc) {
		Series.Window total = counter.series.total();
		double rate = total.rate(ITEM, RateUnit.PER_HOUR);
		double err = Math.abs(rate - KNOWN_PER_HOUR) / KNOWN_PER_HOUR;
		Tickmark.LOG.info(String.format(Locale.ROOT, "[selftest] exact: %d items in %d ticks = %.1f/h, known %.1f/h, error %.2f%% (tolerance %.0f%%)",
				total.count(ITEM), total.measuredTicks(), rate, KNOWN_PER_HOUR, err * 100, TOLERANCE * 100));
		if (!(err <= TOLERANCE)) {
			FAILURES.add(String.format(Locale.ROOT, "rate %.1f/h is not within %.0f%% of %.1f/h", rate, TOLERANCE * 100, KNOWN_PER_HOUR));
		}
		if (total.gapTicks() != 0) {
			FAILURES.add("unexpected gap of " + total.gapTicks() + " ticks");
		}
		if (total.items().size() != 1) {
			FAILURES.add("expected only iron, got " + total.items().keySet());
		}
		// persistence: what is saved reads back the same
		Tickmark.save();
		try {
			CounterSet back = CounterStore.load(CounterStore.worldDir(Tickmark.dir(), Tickmark.worldKey()));
			Counter again = back.get(counter.id).orElse(null);
			if (again == null || again.series.total().count(ITEM) != total.count(ITEM)
					|| again.series.total().measuredTicks() != total.measuredTicks() || !again.positions.equals(counter.positions)) {
				FAILURES.add("saved counter does not read back the same");
			}
		} catch (java.io.IOException e) {
			FAILURES.add("could not read saved counters: " + e);
		}
		finish(mc, null);
	}

	private static void finish(Minecraft mc, String failure) {
		if (failure != null) {
			FAILURES.add(failure);
		}
		done = true;
		if (FAILURES.isEmpty()) {
			Tickmark.LOG.info("SELFTEST PASS ({})", MODE);
		} else {
			for (String f : FAILURES) {
				Tickmark.LOG.error("[selftest] {}", f);
			}
			Tickmark.LOG.error("SELFTEST FAIL ({}): {} problem(s)", MODE, FAILURES.size());
		}
		mc.stop();
	}

	private static void lookAt(Minecraft mc, Vec3 target) {
		Vec3 d = target.subtract(mc.player.getEyePosition());
		double flat = Math.sqrt(d.x * d.x + d.z * d.z);
		mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
		mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, flat)));
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "tickmark-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
				msg -> Tickmark.LOG.info("[selftest] screenshot {}: {}", name, msg.getString()));
	}
}
