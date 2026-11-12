package dev.tickmark.core;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a counter's per-minute history as CSV (RFC 4180) for spreadsheets. One row per minute and
 * item; a minute with no items still gets one row with an empty item so gaps stay visible.
 *
 * <p>Columns: {@value #HEADER}
 */
public final class CsvExport {
	public static final String HEADER = "time,game_tick,item,count,measured_ticks,gap_ticks,method,counter";
	private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

	private CsvExport() {
	}

	public static void write(Writer w, String counter, Method method, List<Bucket> buckets, ZoneId zone)
			throws IOException {
		w.write(HEADER);
		w.write("\r\n");
		for (Bucket b : buckets) {
			String time = b.endEpochMillis > 0 ? ISO.format(Instant.ofEpochMilli(b.endEpochMillis).atZone(zone)) : "";
			List<String> items = new ArrayList<>(b.items.keySet());
			if (items.isEmpty()) {
				items.add("");
			}
			for (String item : items) {
				row(w, time, Long.toString(b.startTick()), item, item.isEmpty() ? "0" : Long.toString(b.count(item)),
						Long.toString(b.measuredTicks), Long.toString(b.gapTicks), method.id(), counter);
			}
		}
	}

	private static void row(Writer w, String... cells) throws IOException {
		for (int i = 0; i < cells.length; i++) {
			if (i > 0) {
				w.write(',');
			}
			w.write(quote(cells[i]));
		}
		w.write("\r\n");
	}

	/** RFC 4180: quote a cell that holds a comma, quote, CR or LF, doubling inner quotes. */
	public static String quote(String s) {
		if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
			return s;
		}
		return '"' + s.replace("\"", "\"\"") + '"';
	}

	/** File name safe on Windows: letters, digits, '-', '_' and '.' kept, the rest becomes '_'. */
	public static String safeName(String s) {
		String out = s.replaceAll("[^A-Za-z0-9._-]", "_");
		return out.isEmpty() ? "_" : out;
	}
}
