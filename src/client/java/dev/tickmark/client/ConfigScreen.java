package dev.tickmark.client;

import dev.tickmark.core.RateUnit;
import dev.tickmark.core.config.TickmarkConfig;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Tickmark settings (Mod Menu, or {@code /tickmark config}). Plain vanilla widgets, so Tickmark needs no
 * config library (copied from Vire's). Changes apply at once and are saved to
 * {@code config/tickmark/config.json} on close.
 */
public final class ConfigScreen extends Screen {
	private static final int W = 150;
	private static final int H = 20;
	private static final int GAP = 4;

	private final Screen parent;

	public ConfigScreen(Screen parent) {
		super(Component.literal("Tickmark settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		TickmarkConfig c = Tickmark.config;
		List<AbstractWidget> w = new ArrayList<>();

		// HUD
		w.add(toggle("HUD", () -> c.hudEnabled, v -> c.hudEnabled = v, "The rate panel on screen (key I)."));
		w.add(cycle("HUD corner", List.of(TickmarkConfig.Corner.values()), () -> c.hudCorner, v -> c.hudCorner = v, ConfigScreen::corner,
				"Screen corner the panel sits in."));
		w.add(intSlider("Offset across", 0, 300, 2, () -> c.hudOffsetX, v -> c.hudOffsetX = v, v -> v + " px",
				"Distance from the left or right edge of the screen."));
		w.add(intSlider("Offset down / up", 0, 300, 2, () -> c.hudOffsetY, v -> c.hudOffsetY = v, v -> v + " px",
				"Distance from the top or bottom edge of the screen."));
		w.add(cycle("Counters shown", List.of(TickmarkConfig.HudCounters.values()), () -> c.hudCounters, v -> c.hudCounters = v,
				ConfigScreen::counters,
				"Nearest: the counter closest to you in this dimension. Pinned: the counters you pinned with /tickmark pin <id> "
						+ "(the nearest if none). All: up to 4 counters, nearest first."));
		w.add(intSlider("Items per counter", 1, 10, 1, () -> c.maxItems, v -> c.maxItems = v, Integer::toString,
				"At most this many items per counter, most counted first. Use /tickmark filter to choose items."));
		w.add(cycle("Window", List.of(5, 15, 60), () -> c.windowMinutes, v -> c.windowMinutes = v, v -> v + " min",
				"Rates, trend and sparkline cover this many game minutes. Longer windows smooth out bursty farms "
						+ "(raid farms, iron farms)."));
		w.add(cycle("Unit", List.of(RateUnit.values()), () -> c.unit, v -> c.unit = v, ConfigScreen::unit,
				"Rates per game minute, hour or day, always at 20 TPS (72,000 ticks an hour)."));
		w.add(toggle("Sparkline", () -> c.sparkline, v -> c.sparkline = v,
				"A small per-minute graph next to each item. Gaps (farm not loaded) show as dots."));
		w.add(toggle("World labels", () -> c.worldLabels, v -> c.worldLabels = v,
				"Outlines of marked counters and a label with the top item's rate, in the world."));
		w.add(intSlider("Read every", 5, 100, 5, () -> c.sampleTicks, v -> c.sampleTicks = v, v -> v + " ticks",
				"Singleplayer and LAN host: how often the containers are read on the server. "
						+ "Shorter is more responsive; the rate is exact either way."));
		w.add(slider("Lag note above", 0.0, 0.5, 0.01, () -> c.lagNoteThreshold, v -> c.lagNoteThreshold = v,
				v -> Math.round(v * 100) + "% slow", "Show a note when the server ran this much slower than 20 TPS: "
						+ "the real output per hour is then lower than the rate at 20 TPS."));

		int rows = (w.size() + 1) / 2;
		int top = Math.max(28, (height - rows * (H + GAP) - 30) / 2);
		int left = width / 2 - W - GAP / 2;
		for (int i = 0; i < w.size(); i++) {
			AbstractWidget widget = w.get(i);
			widget.setPosition(left + (i % 2) * (W + GAP), top + (i / 2) * (H + GAP));
			addRenderableWidget(widget);
		}
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
				.bounds(width / 2 - 100, top + rows * (H + GAP) + 8, 200, H).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		Tickmark.config.clamp();
		Tickmark.saveConfig();
		if (minecraft != null) {
			minecraft.gui.setScreen(parent);
		}
	}

	// ---- labels ----

	private static String corner(TickmarkConfig.Corner c) {
		return switch (c) {
			case TOP_LEFT -> "top left";
			case TOP_RIGHT -> "top right";
			case BOTTOM_LEFT -> "bottom left";
			case BOTTOM_RIGHT -> "bottom right";
		};
	}

	private static String counters(TickmarkConfig.HudCounters c) {
		return switch (c) {
			case NEAREST -> "nearest";
			case PINNED -> "pinned";
			case ALL -> "all";
		};
	}

	private static String unit(RateUnit u) {
		return switch (u) {
			case PER_MINUTE -> "per minute";
			case PER_HOUR -> "per hour";
			case PER_DAY -> "per day";
		};
	}

	// ---- widgets ----

	private static CycleButton<Boolean> toggle(String label, Supplier<Boolean> get, Consumer<Boolean> set, String tip) {
		CycleButton<Boolean> b = CycleButton.onOffBuilder(get.get()).create(0, 0, W, H, Component.literal(label), (btn, v) -> set.accept(v));
		b.setTooltip(Tooltip.create(Component.literal(tip)));
		return b;
	}

	private static <T> CycleButton<T> cycle(String label, List<T> values, Supplier<T> get, Consumer<T> set, Function<T, String> name,
			String tip) {
		T current = values.contains(get.get()) ? get.get() : values.getFirst();
		CycleButton<T> b = CycleButton.builder((T v) -> Component.literal(name.apply(v)), current).withValues(values)
				.create(0, 0, W, H, Component.literal(label), (btn, v) -> set.accept(v));
		b.setTooltip(Tooltip.create(Component.literal(tip)));
		return b;
	}

	private static AbstractWidget intSlider(String label, int min, int max, int step, Supplier<Integer> get, Consumer<Integer> set,
			IntFunction<String> fmt, String tip) {
		return slider(label, min, max, step, () -> (double) get.get(), v -> set.accept((int) Math.round(v)), v -> fmt.apply((int) Math.round(v)), tip);
	}

	/** A slider over {@code [min, max]} in steps of {@code step}; the label reads "Name: value". */
	private static AbstractWidget slider(String label, double min, double max, double step, Supplier<Double> get, Consumer<Double> set,
			DoubleFunction<String> fmt, String tip) {
		double start = (Math.max(min, Math.min(max, get.get())) - min) / (max - min);
		AbstractSliderButton s = new AbstractSliderButton(0, 0, W, H, Component.empty(), start) {
			{
				updateMessage();
			}

			private double current() {
				double v = min + value * (max - min);
				return Math.max(min, Math.min(max, Math.round(v / step) * step));
			}

			@Override
			protected void updateMessage() {
				setMessage(Component.literal(label + ": " + fmt.apply(current())));
			}

			@Override
			protected void applyValue() {
				set.accept(current());
			}
		};
		s.setTooltip(Tooltip.create(Component.literal(tip)));
		return s;
	}
}
