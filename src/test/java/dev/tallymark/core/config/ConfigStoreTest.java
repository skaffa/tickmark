package dev.tallymark.core.config;

import static org.junit.jupiter.api.Assertions.*;

import dev.tallymark.core.RateUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

class ConfigStoreTest {
	@TempDir
	Path tmp;

	private ConfigStore store() {
		Clock fixed = Clock.fixed(Instant.parse("2026-11-12T10:20:30Z"), ZoneOffset.UTC);
		return new ConfigStore(tmp.resolve("tallymark"), fixed);
	}

	@Test
	void missingFileGivesDefaultsAndCreatesFolder() throws Exception {
		ConfigStore s = store();
		TallymarkConfig c = s.load();
		assertEquals(15, c.windowMinutes);
		assertEquals(RateUnit.PER_HOUR, c.unit);
		assertTrue(Files.isRegularFile(tmp.resolve("tallymark").resolve("config.json")));
	}

	@Test
	void roundTrip() throws Exception {
		ConfigStore s = store();
		TallymarkConfig c = new TallymarkConfig();
		c.windowMinutes = 60;
		c.unit = RateUnit.PER_DAY;
		c.hudCorner = TallymarkConfig.Corner.BOTTOM_LEFT;
		s.save(c);
		TallymarkConfig back = s.load();
		assertEquals(60, back.windowMinutes);
		assertEquals(RateUnit.PER_DAY, back.unit);
		assertEquals(TallymarkConfig.Corner.BOTTOM_LEFT, back.hudCorner);
	}

	@Test
	void badValuesAreClamped() throws Exception {
		ConfigStore s = store();
		Files.createDirectories(s.dir());
		Files.writeString(s.file(), "{\"maxItems\": 0, \"sampleTicks\": 9999, \"unit\": \"PER_WEEK\", \"lagNoteThreshold\": -1}",
				StandardCharsets.UTF_8);
		TallymarkConfig c = s.load();
		assertEquals(1, c.maxItems);
		assertEquals(200, c.sampleTicks);
		assertEquals(RateUnit.PER_HOUR, c.unit);
		assertEquals(0.05, c.lagNoteThreshold, 1e-9);
	}

	@Test
	void brokenFileIsBackedUp() throws Exception {
		ConfigStore s = store();
		Files.createDirectories(s.dir());
		Files.writeString(s.file(), "{ not json", StandardCharsets.UTF_8);
		TallymarkConfig c = s.load();
		assertEquals(15, c.windowMinutes);
		assertNotNull(s.lastBackup());
		assertTrue(Files.isRegularFile(s.lastBackup()));
	}
}
