package dev.tallymark.client;

import dev.tallymark.core.config.ConfigStore;
import dev.tallymark.core.config.TallymarkConfig;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/** Client entrypoint. M1: loads the config; counters, HUD and commands come in M2. */
public final class TallymarkClient implements ClientModInitializer {
	public static final String MOD_ID = "tallymark";
	public static final Logger LOG = LoggerFactory.getLogger("Tallymark");

	private static ConfigStore store;
	private static TallymarkConfig config = new TallymarkConfig();

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
		LOG.info("Tallymark loaded");
	}

	/** {@code config/tallymark/}: every file the mod writes goes here. */
	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
	}

	public static TallymarkConfig config() {
		return config;
	}
}
