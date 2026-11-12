package dev.tickmark.client.dev;

import dev.tickmark.client.Marking;
import dev.tickmark.client.Tickmark;
import dev.tickmark.core.Counter;
import dev.tickmark.core.CounterSet;
import dev.tickmark.core.CounterStore;
import dev.tickmark.core.Method;
import dev.tickmark.core.RateUnit;
import dev.tickmark.core.Series;
import dev.tickmark.core.SnapshotLog;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dev-only automated tests (COMMON.md). The rig: a repeating command block clock summons one iron
 * ingot every {@value #PERIOD} game ticks right above a hopper that feeds a chest, so the known rate
 * is 72,000 / {@value #PERIOD} per hour. Each mode marks the chest, waits for enough items, checks the
 * measured rate within a tolerance, logs {@code SELFTEST PASS/FAIL} and quits.
 *
 * <ul>
 * <li>{@code exact}: {@code -Pselftest=exact --args="--quickPlaySingleplayer tmtest"}. Commands run on the
 * integrated server. Tolerance {@value #EXACT_TOLERANCE}.</li>
 * <li>{@code entity}: {@code -Pselftest=entity --args="--quickPlayMultiplayer 127.0.0.1:25565"} with
 * {@code ./gradlew runServer} running (RCON on, command blocks on). Commands go over RCON. Checks the
 * entity estimate (tolerance {@value #ENTITY_TOLERANCE}), the snapshot taken by opening the chest twice,
 * and a {@code /tick rate 10} phase where the 20 TPS rate must hold while the real-time rate halves.</li>
 * </ul>
 */
public final class SelfTest {
	private static final String MODE = System.getProperty("tickmark.selftest", "");
	static final int PERIOD = 20;
	private static final double KNOWN_PER_HOUR = 72_000.0 / PERIOD;
	private static final double EXACT_TOLERANCE = 0.02;
	private static final double ENTITY_TOLERANCE = 0.05;
	private static final double SNAPSHOT_TOLERANCE = 0.03;
	/** Items to wait for before judging: enough that one item of phase error is under 1%. */
	private static final long TARGET_ITEMS = 120;
	private static final long SLOW_ITEMS = 60;
	private static final int TIMEOUT_TICKS = 20 * 60 * 12;
	private static final String ITEM = "minecraft:iron_ingot";
	private static final String RCON_PASSWORD = "tickmarktest";

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
		boolean entity = MODE.equals("entity");
		if (!entity && !MODE.equals("exact")) {
			finish(mc, "unknown self-test mode '" + MODE + "' (known: exact, entity)");
			return;
		}
		if (entity == mc.hasSingleplayerServer()) {
			finish(mc, entity ? "entity mode needs the test server (--quickPlayMultiplayer 127.0.0.1:25565)"
					: "exact mode needs a singleplayer world (--quickPlaySingleplayer tmtest)");
			return;
		}
		int inStage = ticks - stageStart;
		if (stage >= 2 && chest != null && mc.gui.screen() == null) {
			lookAt(mc, Vec3.atCenterOf(chest).add(0, 0.6, 0));
		}
		switch (stage) {
			case 0 -> {
				if (ticks > 100) {
					build(mc);
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
					// counters of earlier runs in this world would change what the test expects
					Tickmark.counters = new CounterSet();
					Tickmark.changed();
					counter = markChest(mc);
					if (counter == null) {
						finish(mc, "could not mark the chest");
						return;
					}
					Tickmark.LOG.info("[selftest] marked {} at {}", counter.label(), chest);
					next();
				}
			}
			case 2 -> {
				// entity mode: open and close the chest once, as the snapshot baseline
				if (!entity) {
					next();
				} else if (inStage == 20) {
					open(mc);
				} else if (inStage == 40) {
					mc.player.closeContainer();
				} else if (inStage == 50) {
					next();
				}
			}
			case 3 -> {
				long n = counter.series.total().count(ITEM);
				if (inStage % 400 == 0) {
					Tickmark.LOG.info("[selftest] {} items in {} ticks (method {})", n, counter.series.total().measuredTicks(), counter.method);
				}
				if (n >= TARGET_ITEMS) {
					next();
				} else if (inStage > TIMEOUT_TICKS) {
					finish(mc, "timeout: only " + n + " items counted");
				}
			}
			case 4 -> {
				if (inStage == 5) {
					shot(mc, MODE + "-hud");
				}
				if (inStage == 30) {
					check(mc, entity);
					if (!entity) {
						finish(mc, null);
						return;
					}
					open(mc);
				}
				if (inStage == 60) {
					checkSnapshot();
				}
				if (inStage == 70) {
					mc.player.closeContainer();
				}
				if (inStage == 80) {
					shot(mc, "entity-snapshot-hud");
				}
				if (inStage == 100) {
					rcon("tick rate 10");
					counter.series.reset();
					counter.restart(true);
					next();
				}
			}
			case 5 -> {
				long n = counter.series.total().count(ITEM);
				if (n >= SLOW_ITEMS) {
					checkSlow();
					rcon("tick rate 20");
					finish(mc, null);
				} else if (inStage > TIMEOUT_TICKS) {
					rcon("tick rate 20");
					finish(mc, "timeout at tick rate 10: only " + n + " items counted");
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
		lookAt(mc, Vec3.atCenterOf(chest));
		mc.hitResult = new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false);
		return Marking.markContainer(mc, chest, true).orElse(null);
	}

	/** Right-clicks the chest through the normal game path, so the snapshot tracker binds it. */
	private static void open(Minecraft mc) {
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(chest).add(0, 0.5, 0), Direction.UP, chest, false);
		mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
	}

	private static void build(Minecraft mc) {
		BlockPos base = mc.player.blockPosition().offset(4, 0, 0);
		int x = base.getX();
		int y = base.getY();
		int z = base.getZ();
		chest = new BlockPos(x, y, z);
		String item = "{Item:{id:\"" + ITEM + "\",count:1},Motion:[0.0d,0.0d,0.0d]}";
		String name = mc.player.getGameProfile().name();
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
				"tick rate 20",
				"tp " + name + " " + (x - 3.5) + " " + (y + 1) + " " + (z + 0.5) + " -90 20");
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> {
				var src = server.createCommandSourceStack();
				for (String c : cmds) {
					Tickmark.LOG.info("[selftest] /{}", c);
					server.getCommands().performPrefixedCommand(src, c);
				}
			});
		} else {
			for (String c : cmds) {
				rcon(c);
			}
		}
		Tickmark.LOG.info("[selftest] rig at {} (period {} ticks, known rate {}/h)", chest, PERIOD, KNOWN_PER_HOUR);
	}

	private static void rcon(String cmd) {
		try (Rcon r = new Rcon("127.0.0.1", 25575, RCON_PASSWORD)) {
			String reply = r.command(cmd);
			Tickmark.LOG.info("[selftest] rcon /{} -> {}", cmd, reply.isBlank() ? "ok" : reply.strip());
		} catch (IOException e) {
			FAILURES.add("RCON failed for '" + cmd + "': " + e);
		}
	}

	private static void check(Minecraft mc, boolean entity) {
		Series.Window total = counter.series.total();
		double rate = total.rate(ITEM, RateUnit.PER_HOUR);
		double err = Math.abs(rate - KNOWN_PER_HOUR) / KNOWN_PER_HOUR;
		double tol = entity ? ENTITY_TOLERANCE : EXACT_TOLERANCE;
		Tickmark.LOG.info(String.format(Locale.ROOT,
				"[selftest] %s: %d items in %d ticks = %.1f/h, known %.1f/h, error %.2f%% (tolerance %.0f%%); lost %d, unknown %d, gap %d ticks, %.1f TPS",
				MODE, total.count(ITEM), total.measuredTicks(), rate, KNOWN_PER_HOUR, err * 100, tol * 100, total.lost(), total.unknown(),
				total.gapTicks(), total.tps()));
		if (!(err <= tol)) {
			FAILURES.add(String.format(Locale.ROOT, "rate %.1f/h is not within %.0f%% of %.1f/h", rate, tol * 100, KNOWN_PER_HOUR));
		}
		Method want = entity ? Method.ENTITY : Method.EXACT;
		if (counter.method != want) {
			FAILURES.add("method is " + counter.method + ", expected " + want);
		}
		if (total.gapTicks() != 0) {
			FAILURES.add("unexpected gap of " + total.gapTicks() + " ticks");
		}
		if (total.items().size() != 1) {
			FAILURES.add("expected only iron, got " + total.items().keySet());
		}
		if (entity && total.unknown() > 0) {
			FAILURES.add(total.unknown() + " items with an unknown cause");
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
		} catch (IOException e) {
			FAILURES.add("could not read saved counters: " + e);
		}
	}

	private static void checkSnapshot() {
		SnapshotLog log = counter.snapshots.values().stream().findFirst().orElse(null);
		if (log == null || !log.measured()) {
			FAILURES.add("no snapshot was measured by opening the chest twice");
			return;
		}
		double rate = log.rate(ITEM, RateUnit.PER_HOUR);
		double err = Math.abs(rate - KNOWN_PER_HOUR) / KNOWN_PER_HOUR;
		Tickmark.LOG.info(String.format(Locale.ROOT, "[selftest] snapshot: %d items over %d ticks = %.1f/h, error %.2f%% (tolerance %.0f%%)",
				log.gain.getOrDefault(ITEM, 0L), log.gainTicks, rate, err * 100, SNAPSHOT_TOLERANCE * 100));
		if (!(err <= SNAPSHOT_TOLERANCE)) {
			FAILURES.add(String.format(Locale.ROOT, "snapshot rate %.1f/h is not within %.0f%% of %.1f/h", rate, SNAPSHOT_TOLERANCE * 100,
					KNOWN_PER_HOUR));
		}
	}

	private static void checkSlow() {
		Series.Window total = counter.series.total();
		double rate = total.rate(ITEM, RateUnit.PER_HOUR);
		double real = total.realRate(ITEM, RateUnit.PER_HOUR);
		double err = Math.abs(rate - KNOWN_PER_HOUR) / KNOWN_PER_HOUR;
		Tickmark.LOG.info(String.format(Locale.ROOT,
				"[selftest] tick rate 10: %d items in %d ticks = %.1f/h at 20 TPS (error %.2f%%), %.1f/h real time, %.1f TPS",
				total.count(ITEM), total.measuredTicks(), rate, err * 100, real, total.tps()));
		if (!(err <= ENTITY_TOLERANCE)) {
			FAILURES.add(String.format(Locale.ROOT, "at tick rate 10 the 20 TPS rate %.1f/h is not within %.0f%% of %.1f/h", rate,
					ENTITY_TOLERANCE * 100, KNOWN_PER_HOUR));
		}
		// the server runs at most 10 TPS now: real output is at most about half
		if (!(real <= KNOWN_PER_HOUR * 0.55)) {
			FAILURES.add(String.format(Locale.ROOT, "at tick rate 10 the real-time rate %.1f/h is not about half", real));
		}
		if (!(total.tps() <= 10.5)) {
			FAILURES.add(String.format(Locale.ROOT, "measured %.1f TPS at tick rate 10", total.tps()));
		}
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
