package dev.tickmark.client;

import dev.tickmark.core.Counter;
import dev.tickmark.core.CounterSet;
import dev.tickmark.core.CounterStore;
import dev.tickmark.core.config.ConfigStore;
import dev.tickmark.core.config.TickmarkConfig;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/** Shared client state: settings, the current world's counters, saving. */
public final class Tickmark {
	public static final String MOD_ID = "tickmark";
	public static final Logger LOG = LoggerFactory.getLogger("Tickmark");
	/** Save the counters at least this often while in a world (game is not trusted to exit cleanly). */
	private static final long SAVE_EVERY_NANOS = 5L * 60 * 1_000_000_000L;

	private static ConfigStore configStore;
	public static TickmarkConfig config = new TickmarkConfig();

	/** Counters of the world the player is in; empty set outside a world. */
	public static CounterSet counters = new CounterSet();
	private static Path worldDir;
	private static String worldKey;
	private static long lastSave;
	private static boolean dirty;

	private Tickmark() {
	}

	/** {@code config/tickmark/}: every file the mod writes goes here. */
	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
	}

	static void loadConfig() {
		configStore = new ConfigStore(dir());
		try {
			config = configStore.load();
			if (configStore.lastBackup() != null) {
				LOG.warn("config.json could not be read; kept it as {} and used defaults", configStore.lastBackup());
			}
		} catch (IOException e) {
			LOG.error("could not load config, using defaults", e);
		}
	}

	public static void saveConfig() {
		try {
			configStore.save(config);
		} catch (IOException e) {
			LOG.warn("could not save config", e);
		}
	}

	/** Key of the current world: the save folder in singleplayer, the server address in multiplayer. */
	static String currentWorldKey(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			Path name = root.getFileName();
			return "sp_" + (name == null ? "world" : name.toString());
		}
		ServerData data = mc.getCurrentServer();
		return "mp_" + (data == null ? "unknown" : data.ip);
	}

	static void joinWorld(Minecraft mc) {
		worldKey = currentWorldKey(mc);
		worldDir = CounterStore.worldDir(dir(), worldKey);
		try {
			counters = CounterStore.load(worldDir);
		} catch (IOException e) {
			LOG.warn("could not load counters for {}", worldKey, e);
			counters = new CounterSet();
		}
		lastSave = System.nanoTime();
		LOG.info("world {}: {} counter(s)", worldKey, counters.counters.size());
	}

	static void leaveWorld() {
		save();
		counters = new CounterSet();
		worldDir = null;
		worldKey = null;
	}

	public static String worldKey() {
		return worldKey;
	}

	/** Marks the counters as changed; they are written within a few seconds. */
	public static void changed() {
		dirty = true;
	}

	static void tickSave() {
		long now = System.nanoTime();
		if (worldDir != null && (dirty && now - lastSave > 3_000_000_000L || now - lastSave > SAVE_EVERY_NANOS)) {
			save();
		}
	}

	public static void save() {
		if (worldDir == null) {
			return;
		}
		try {
			CounterStore.save(worldDir, counters);
			dirty = false;
			lastSave = System.nanoTime();
		} catch (IOException e) {
			LOG.warn("could not save counters to {}", worldDir, e);
		}
	}

	static Path worldDir() {
		return worldDir;
	}

	public static String dimension(Level level) {
		return level.dimension().identifier().toString();
	}

	/** Exact mode is possible: this client hosts the world (singleplayer or LAN host). */
	public static boolean exact(Minecraft mc) {
		return mc.hasSingleplayerServer();
	}

	public static void say(String msg) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.literal("Tickmark: " + msg).withStyle(ChatFormatting.GOLD));
		}
	}

	static String describe(Counter c) {
		return switch (c.kind) {
			case CONTAINER -> "container at " + c.positions.getFirst();
			case GROUP -> c.positions.size() + " containers";
			case BOX -> "box " + c.boxMin + " to " + c.boxMax;
			case SELF -> "your inventory";
		};
	}
}
