package dev.tickmark.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Saves the counters of one world in {@code config/tickmark/worlds/<world key>/counters.json}. A file
 * that cannot be read is kept as {@code counters.json.broken} and an empty set is used.
 */
public final class CounterStore {
	public static final String FILE_NAME = "counters.json";
	private static final Gson GSON = new GsonBuilder().create();

	private CounterStore() {
	}

	/** Folder for one world, e.g. {@code sp_New_World} or {@code mp_play.example.com_25565}. */
	public static Path worldDir(Path modDir, String worldKey) {
		return modDir.resolve("worlds").resolve(CsvExport.safeName(worldKey));
	}

	public static CounterSet load(Path dir) throws IOException {
		Path file = dir.resolve(FILE_NAME);
		if (!Files.isRegularFile(file)) {
			return new CounterSet();
		}
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			CounterSet set = GSON.fromJson(r, CounterSet.class);
			if (set == null) {
				throw new JsonParseException("empty file");
			}
			set.counters.removeIf(c -> c == null || c.dimension == null || c.kind == null);
			for (Counter c : set.counters) {
				if (c.series == null) {
					c.series = new Series();
				}
				if (c.positions == null) {
					c.positions = new java.util.ArrayList<>();
				}
				if (c.snapshots == null) {
					c.snapshots = new java.util.LinkedHashMap<>();
				}
				if (c.filter == null) {
					c.filter = new java.util.ArrayList<>();
				}
				c.restart();
				set.nextId = Math.max(set.nextId, c.id + 1);
			}
			return set;
		} catch (JsonParseException | IllegalStateException e) {
			Files.move(file, dir.resolve(FILE_NAME + ".broken"), StandardCopyOption.REPLACE_EXISTING);
			return new CounterSet();
		}
	}

	public static void save(Path dir, CounterSet set) throws IOException {
		Files.createDirectories(dir);
		Path tmp = dir.resolve(FILE_NAME + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(set, w);
		}
		try {
			Files.move(tmp, dir.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, dir.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
