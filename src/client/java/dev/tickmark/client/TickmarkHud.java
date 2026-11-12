package dev.tickmark.client;

import dev.tickmark.core.Counter;
import dev.tickmark.core.Format;
import dev.tickmark.core.Method;
import dev.tickmark.core.RateUnit;
import dev.tickmark.core.Series;
import dev.tickmark.core.Trend;
import dev.tickmark.core.config.TickmarkConfig;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The HUD panel (PLAN.md section 7): per counter a title with the method tag and trust colour, then
 * per item the rate at 20 TPS, the total since reset, a sparkline of the window and the trend.
 */
final class TickmarkHud {
	static final int GREEN = 0xFF55DD66;
	static final int YELLOW = 0xFFE8D24A;
	static final int ORANGE = 0xFFFF9A3C;
	static final int GREY = 0xFF9A9A9A;
	private static final int TEXT = 0xFFE6E6E6;
	private static final int DIM = 0xFFA8A8A8;
	private static final int BG = 0xB0101418;
	private static final int ROW = 11;
	private static final int PAD = 4;
	private static final int SPARK_W = 2;

	private TickmarkHud() {
	}

	/** Trust colour of a counter right now. */
	static int trust(Counter c, Series.Window w) {
		if (c.paused || c.method == null) {
			return GREY;
		}
		boolean gaps = w.gapTicks() > (w.measuredTicks() + w.gapTicks()) / 10;
		if (c.inGap || gaps) {
			return ORANGE;
		}
		return c.method.exact() ? GREEN : (w.unknown() > 0 ? ORANGE : YELLOW);
	}

	/** Counters the panel shows, by the HUD setting. */
	static List<Counter> shown(Minecraft mc) {
		String dim = Tickmark.dimension(mc.level);
		List<Counter> near = Tickmark.counters.nearest(dim, mc.player.getX(), mc.player.getY(), mc.player.getZ());
		int max = Tickmark.config.hudCounters == TickmarkConfig.HudCounters.ALL ? 4 : 1;
		return near.subList(0, Math.min(max, near.size()));
	}

	static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		TickmarkConfig cfg = Tickmark.config;
		if (!cfg.hudEnabled || mc.level == null || mc.player == null || mc.gui.hud.isHidden()) {
			return;
		}
		List<Counter> counters = shown(mc);
		if (counters.isEmpty()) {
			return;
		}
		List<Block> blocks = new ArrayList<>();
		for (Counter c : counters) {
			blocks.add(block(mc, c, cfg));
		}
		Font font = mc.font;
		int w = 0;
		int h = 0;
		for (Block b : blocks) {
			w = Math.max(w, b.width(font));
			h += b.height() + PAD;
		}
		h -= PAD;
		int x = switch (cfg.hudCorner) {
			case TOP_LEFT, BOTTOM_LEFT -> cfg.hudOffsetX + PAD;
			default -> g.guiWidth() - cfg.hudOffsetX - PAD - w;
		};
		int y = switch (cfg.hudCorner) {
			case TOP_LEFT, TOP_RIGHT -> cfg.hudOffsetY + PAD;
			default -> g.guiHeight() - cfg.hudOffsetY - PAD - h;
		};
		for (Block b : blocks) {
			b.draw(g, font, x, y, w);
			y += b.height() + PAD;
		}
	}

	// ---- layout ----

	private record ItemRow(ItemStack icon, String name, String rate, String total, int[] spark, String trend, int trendColor) {
	}

	private record Block(String title, String tag, int tagColor, List<ItemRow> rows, List<String> notes) {
		int height() {
			return ROW + rows.size() * ROW + notes.size() * ROW;
		}

		int width(Font font) {
			int w = font.width(title) + 12 + font.width(tag) + 8;
			int[] cols = cols(font);
			if (!rows.isEmpty()) {
				w = Math.max(w, cols[5]);
			}
			for (String n : notes) {
				w = Math.max(w, font.width(n));
			}
			return w;
		}

		/** Column ends: icon+name, rate, total, spark, trend; [5] = full width. */
		int[] cols(Font font) {
			int name = 0;
			int rate = 0;
			int total = 0;
			int spark = 0;
			int trend = 0;
			for (ItemRow r : rows) {
				name = Math.max(name, font.width(r.name));
				rate = Math.max(rate, font.width(r.rate));
				total = Math.max(total, font.width(r.total));
				spark = Math.max(spark, r.spark.length * SPARK_W);
				trend = Math.max(trend, font.width(r.trend));
			}
			int c0 = 10 + name;
			int c1 = c0 + 8 + rate;
			int c2 = c1 + 8 + total;
			int c3 = c2 + (spark > 0 ? 8 + spark : 0);
			int c4 = c3 + 8 + trend;
			return new int[] {c0, c1, c2, c3, c4, c4};
		}

		void draw(GuiGraphicsExtractor g, Font font, int x, int y, int w) {
			g.fill(x - PAD, y - PAD + 1, x + w + PAD, y + height() + PAD - 2, BG);
			g.fill(x - PAD, y - PAD + 1, x - PAD + 2, y + height() + PAD - 2, tagColor);
			g.text(font, title, x, y, 0xFFFFFFFF, true);
			g.text(font, tag, x + w - font.width(tag), y, tagColor, true);
			int[] cols = cols(font);
			int ry = y + ROW;
			for (ItemRow r : rows) {
				var pose = g.pose();
				pose.pushMatrix();
				pose.translate(x, ry - 1);
				pose.scale(0.5f, 0.5f);
				g.fakeItem(r.icon, 0, 0);
				pose.popMatrix();
				g.text(font, r.name, x + 10, ry, TEXT, true);
				g.text(font, r.rate, x + cols[1] - font.width(r.rate), ry, 0xFFFFFFFF, true);
				g.text(font, r.total, x + cols[2] - font.width(r.total), ry, DIM, true);
				int sx = x + cols[2] + 8;
				for (int i = 0; i < r.spark.length; i++) {
					int level = r.spark[i];
					if (level < 0) {
						g.fill(sx + i * SPARK_W, ry + 7, sx + i * SPARK_W + 1, ry + 8, 0x60FFFFFF);
					} else {
						g.fill(sx + i * SPARK_W, ry + 7 - level, sx + i * SPARK_W + 1, ry + 8, 0xFF7FC8FF);
					}
				}
				g.text(font, r.trend, x + cols[3] + 8, ry, r.trendColor, true);
				ry += ROW;
			}
			for (String n : notes) {
				g.text(font, n, x, ry, DIM, true);
				ry += ROW;
			}
		}
	}

	private static Block block(Minecraft mc, Counter c, TickmarkConfig cfg) {
		long now = c.lastTick != Long.MIN_VALUE ? c.lastTick : mc.level.getGameTime();
		long windowTicks = cfg.windowMinutes * Series.BUCKET_TICKS;
		Series.Window w = c.series.window(now, windowTicks);
		Series.Window total = c.series.total();
		RateUnit unit = cfg.unit;
		String tag = c.paused ? "PAUSED" : c.method == null ? "NOT MEASURED" : c.method.tag();
		int color = trust(c, w);

		List<ItemRow> rows = new ArrayList<>();
		long last = Math.floorDiv(now - 1, Series.BUCKET_TICKS);
		long first = last - cfg.windowMinutes + 1;
		// minutes before the counter's first measurement are not gaps: start the sparkline there
		List<dev.tickmark.core.Bucket> all = c.series.buckets();
		if (!all.isEmpty()) {
			first = Math.max(first, all.getFirst().index);
		}
		List<String> ids = total.itemsByCount();
		for (String id : ids.subList(0, Math.min(cfg.maxItems, ids.size()))) {
			Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
			ItemStack stack = new ItemStack(item);
			Trend.Result t = Trend.of(w.buckets(), id);
			String trend = switch (t.direction()) {
				case WARMING_UP -> "...";
				case STABLE -> "stable";
				case RISING -> "rising";
				case FALLING -> "falling";
			};
			int tc = switch (t.direction()) {
				case RISING -> GREEN;
				case FALLING -> ORANGE;
				default -> DIM;
			};
			int[] spark = cfg.sparkline ? Trend.sparkline(w.buckets(), id, first, last) : new int[0];
			rows.add(new ItemRow(stack, stack.getHoverName().getString(), Format.rate(w.rate(id, unit)) + unit.suffix(),
					"+" + Format.count(total.count(id)), spark, trend, tc));
		}

		List<String> notes = new ArrayList<>();
		if (rows.isEmpty() && !c.paused) {
			notes.add(c.lastTick == Long.MIN_VALUE ? "Starting..." : c.kind == Counter.Kind.SELF ? "Nothing picked up yet" : "No items yet");
		}
		String measured = "measured " + Format.duration(total.measuredTicks()) + " · " + cfg.windowMinutes + " m window";
		if (total.gapTicks() > 0) {
			measured += " · " + Format.duration(total.gapTicks()) + (c.method == Method.EXACT ? " not loaded" : " not seen");
		}
		notes.add(measured);
		if (c.inGap) {
			notes.add(c.method == Method.EXACT ? "Chunks not ticking now: not counted"
					: c.method == Method.INVENTORY ? "Other dimension: not counted" : "Out of sight or not ticking now: not counted");
		}
		if (c.method == Method.ENTITY) {
			if (c.ledger != null && c.ledger.rule() == dev.tickmark.core.EntityLedger.Rule.ARRIVED) {
				notes.add("No hopper in the area: counting items that arrive");
			}
			if (w.lost() > 0 || w.unknown() > 0) {
				notes.add(Format.count(w.lost()) + " lost (picked up, despawned) · " + Format.count(w.unknown()) + " unknown");
			}
			String top = ids.isEmpty() ? null : ids.getFirst();
			for (var e : c.snapshots.entrySet()) {
				if (e.getValue().measured() && top == null) {
					top = e.getValue().gain.keySet().stream().findFirst().orElse(null);
				}
			}
			double snap = top == null ? Double.NaN : c.snapshotRate(top, unit);
			if (!Double.isNaN(snap)) {
				long span = c.snapshots.values().stream().filter(dev.tickmark.core.SnapshotLog::measured)
						.mapToLong(l -> l.gainTicks).max().orElse(0);
				notes.add("Opened: " + Format.rate(snap) + unit.suffix() + " " + Format.shortId(top) + " over " + Format.duration(span)
						+ " (exact)");
			} else if (c.kind != Counter.Kind.BOX) {
				notes.add("Open it, close it, open it later: exact check");
			}
		}
		if (c.full) {
			notes.add("Full: the rate is capped");
		}
		double tps = w.tps();
		if (!Double.isNaN(tps) && tps < 20 * (1 - cfg.lagNoteThreshold) && w.measuredTicks() > 600) {
			notes.add(String.format(java.util.Locale.ROOT, "Server ran at %.1f TPS: real output %d%% lower", tps,
					Math.round((1 - tps / 20) * 100)));
		}
		if (Trend.of(w.buckets(), ids.isEmpty() ? "" : ids.getFirst()).variation() > 1.0) {
			notes.add("Bursty farm: the 60 m window is more reliable");
		}
		return new Block(c.label(), tag, color, rows, notes);
	}
}
