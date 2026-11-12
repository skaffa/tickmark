package dev.tickmark.client;

import dev.tickmark.core.config.ConfigStore;
import dev.tickmark.core.config.TickmarkConfig;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/** Client entrypoint. M1: loads the config; counters, HUD and commands come in M2. */
public final class TickmarkClient implements ClientModInitializer {
	public static final String MOD_ID = "tickmark";
	public static final Logger LOG = LoggerFactory.getLogger("Tickmark");

	private static ConfigStore store;
	private static TickmarkConfig config = new TickmarkConfig();

	@Override
	public void onInitializeClient() {
		store = new ConfigStore(dir());
		try {
			config = store.load();
			if (store.lastBackup() != null) {
				LOG.warn("config.json could not be read; kept it as {} and used defaults", store.lastBackup());
			}
		} catch (IOException e) {
			LOG.error("could not load config, using defaults", e);
		}
		LOG.info("Tickmark loaded");
	}

	/** {@code config/tickmark/}: every file the mod writes goes here. */
	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
	}

	public static TickmarkConfig config() {
		return config;
	}
}
