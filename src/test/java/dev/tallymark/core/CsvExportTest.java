package dev.tallymark.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.ZoneOffset;
import java.util.Map;

class CsvExportTest {
	@Test
	void quotesOnlyWhenNeeded() {
		assertEquals("plain", CsvExport.quote("plain"));
		assertEquals("\"a,b\"", CsvExport.quote("a,b"));
		assertEquals("\"say \"\"hi\"\"\"", CsvExport.quote("say \"hi\""));
		assertEquals("\"two\nlines\"", CsvExport.quote("two\nlines"));
	}

	@Test
	void writesRowsPerMinuteAndItemWithGaps() throws Exception {
		Series s = new Series();
		long t0 = 1_700_000_000_000L;
		s.add(new Sample(0, 1200, 60_000, t0, false, Map.of("minecraft:iron_ingot", 60L), 0, 0));
		s.add(new Sample(1200, 2400, 60_000, t0 + 60_000, true, Map.of(), 0, 0));
		StringWriter w = new StringWriter();
		CsvExport.write(w, "Iron farm, east", Method.EXACT, s.buckets(), ZoneOffset.UTC);
		String[] lines = w.toString().split("\r\n");
		assertEquals(3, lines.length);
		assertEquals(CsvExport.HEADER, lines[0]);
		assertEquals("2023-11-14T22:13:20Z,0,minecraft:iron_ingot,60,1200,0,exact,\"Iron farm, east\"", lines[1]);
		assertEquals("2023-11-14T22:14:20Z,1200,,0,0,1200,exact,\"Iron farm, east\"", lines[2]);
	}

	@Test
	void safeFileNames() {
		assertEquals("Iron_farm__east_", CsvExport.safeName("Iron farm, east!"));
		assertEquals("play.example.com_25565", CsvExport.safeName("play.example.com:25565"));
		assertEquals("_", CsvExport.safeName(""));
	}
}
