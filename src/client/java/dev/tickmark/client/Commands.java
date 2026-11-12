package dev.tickmark.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;

import dev.tickmark.core.Counter;
import dev.tickmark.core.CsvExport;
import dev.tickmark.core.EntityLedger;
import dev.tickmark.core.Format;
import dev.tickmark.core.Pos;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** {@code /tickmark} and its short alias {@code /tm}. */
final class Commands {
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private Commands() {
	}

	static void register(CommandDispatcher<FabricClientCommandSource> d) {
		LiteralCommandNode<FabricClientCommandSource> root = d.register(build("tickmark"));
		d.register(literal("tm").executes(Commands::help).redirect(root));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> build(String name) {
		return literal(name)
				.executes(Commands::help)
				.then(literal("help").executes(Commands::help))
				.then(literal("mark")
						.executes(c -> mark(c, false))
						.then(literal("new").executes(c -> mark(c, true)))
						.then(literal("self").executes(c -> Marking.markSelf(Minecraft.getInstance()).isPresent() ? 1 : 0))
						.then(literal("box")
								.executes(c -> {
									Marking.startBox();
									return 1;
								})
								.then(argument("x1", IntegerArgumentType.integer()).then(argument("y1", IntegerArgumentType.integer())
										.then(argument("z1", IntegerArgumentType.integer()).then(argument("x2", IntegerArgumentType.integer())
												.then(argument("y2", IntegerArgumentType.integer()).then(argument("z2", IntegerArgumentType.integer())
														.executes(Commands::markBox)))))))))
				.then(literal("list").executes(Commands::list))
				.then(literal("name").then(argument("id", IntegerArgumentType.integer(1))
						.then(argument("name", StringArgumentType.greedyString()).executes(c -> each(c, false, k -> {
							k.name = StringArgumentType.getString(c, "name").trim();
						}, "renamed")))))
				.then(literal("reset")
						.then(literal("all").executes(c -> each(c, true, Commands::reset, "reset")))
						.then(argument("id", IntegerArgumentType.integer(1)).executes(c -> each(c, false, Commands::reset, "reset"))))
				.then(literal("pause")
						.then(literal("all").executes(c -> each(c, true, k -> k.paused = true, "paused")))
						.then(argument("id", IntegerArgumentType.integer(1)).executes(c -> each(c, false, k -> k.paused = true, "paused"))))
				.then(literal("resume")
						.then(literal("all").executes(c -> each(c, true, Commands::resume, "resumed")))
						.then(argument("id", IntegerArgumentType.integer(1)).executes(c -> each(c, false, Commands::resume, "resumed"))))
				.then(literal("rule").then(argument("id", IntegerArgumentType.integer(1))
						.then(literal("collected").executes(c -> each(c, false, k -> k.rule = EntityLedger.Rule.COLLECTED, "set to count items collected by hoppers:")))
						.then(literal("arrived").executes(c -> each(c, false, k -> k.rule = EntityLedger.Rule.ARRIVED, "set to count items arriving in the area:")))
						.then(literal("auto").executes(c -> each(c, false, k -> k.rule = null, "set to choose the rule automatically:")))))
				.then(literal("remove").then(argument("id", IntegerArgumentType.integer(1)).executes(Commands::remove)))
				.then(literal("export")
						.then(literal("all").executes(c -> export(c, true)))
						.then(argument("id", IntegerArgumentType.integer(1)).executes(c -> export(c, false))))
				.then(literal("hud").executes(c -> {
					Tickmark.config.hudEnabled = !Tickmark.config.hudEnabled;
					Tickmark.saveConfig();
					c.getSource().sendFeedback(Component.literal("Tickmark HUD " + (Tickmark.config.hudEnabled ? "on" : "off")));
					return 1;
				}));
	}

	private static int help(CommandContext<FabricClientCommandSource> c) {
		String[] lines = {
				"Tickmark: measures farm output in items per hour (at 20 TPS).",
				"/tickmark mark - mark the container you look at (or the mark key, U). Adjacent containers join the last counter;",
				"/tickmark mark new - always start a new counter (or Shift + mark key)",
				"/tickmark mark box [x1 y1 z1 x2 y2 z2] - mark an area (without numbers: mark key on two corners)",
				"/tickmark mark self - count what you pick up (AFK farms where you collect)",
				"/tickmark rule <id> collected|arrived|auto - multiplayer: count items hoppers collect, or items entering the area",
				"/tickmark list | name <id> <name> | reset <id|all> | pause <id|all> | resume <id|all> | remove <id>",
				"/tickmark export <id|all> - CSV in config/tickmark/exports/",
				"/tickmark hud - show or hide the panel (or the I key). /tm is short for /tickmark.",
				"Exact in singleplayer and on a LAN host. On servers: estimated from item entities (ENTITY ~);",
				"open a marked container now and then for an exact check between visits."};
		for (String l : lines) {
			c.getSource().sendFeedback(Component.literal(l));
		}
		return 1;
	}

	private static int mark(CommandContext<FabricClientCommandSource> c, boolean forceNew) {
		Minecraft mc = Minecraft.getInstance();
		var t = Marking.target(mc);
		if (t == null) {
			c.getSource().sendError(Component.literal("Look at a container first"));
			return 0;
		}
		return Marking.markContainer(mc, t, forceNew).isPresent() ? 1 : 0;
	}

	private static int markBox(CommandContext<FabricClientCommandSource> c) {
		Pos a = new Pos(IntegerArgumentType.getInteger(c, "x1"), IntegerArgumentType.getInteger(c, "y1"), IntegerArgumentType.getInteger(c, "z1"));
		Pos b = new Pos(IntegerArgumentType.getInteger(c, "x2"), IntegerArgumentType.getInteger(c, "y2"), IntegerArgumentType.getInteger(c, "z2"));
		return Marking.markBox(Minecraft.getInstance(), a, b).isPresent() ? 1 : 0;
	}

	private static int list(CommandContext<FabricClientCommandSource> c) {
		List<Counter> all = Tickmark.counters.counters;
		if (all.isEmpty()) {
			c.getSource().sendFeedback(Component.literal("No counters in this world. Look at a container and press U."));
			return 1;
		}
		for (Counter k : all) {
			var w = k.series.total();
			var ids = w.itemsByCount();
			String top = ids.isEmpty() ? "no items yet"
					: Format.rate(w.rate(ids.getFirst(), Tickmark.config.unit)) + Tickmark.config.unit.suffix() + " "
							+ Format.shortId(ids.getFirst());
			c.getSource().sendFeedback(Component.literal(k.label() + ": " + Tickmark.describe(k) + " in " + Format.shortId(k.dimension)
					+ (k.paused ? " (paused)" : "") + " - " + top + ", measured " + Format.duration(w.measuredTicks())));
		}
		return 1;
	}

	private static void reset(Counter k) {
		k.series.reset();
		k.restart(true);
	}

	private static void resume(Counter k) {
		if (k.paused) {
			k.paused = false;
			k.restart(true);
		}
	}

	/** Runs an action on one counter (argument "id") or on all; reports and saves. */
	private static int each(CommandContext<FabricClientCommandSource> c, boolean all, Consumer<Counter> action, String verb) {
		if (all) {
			Tickmark.counters.counters.forEach(action);
			Tickmark.changed();
			c.getSource().sendFeedback(Component.literal("Tickmark: " + verb + " " + Tickmark.counters.counters.size() + " counter(s)"));
			return 1;
		}
		int id = IntegerArgumentType.getInteger(c, "id");
		var k = Tickmark.counters.get(id);
		if (k.isEmpty()) {
			c.getSource().sendError(Component.literal("No counter #" + id + " (see /tickmark list)"));
			return 0;
		}
		action.accept(k.get());
		Tickmark.changed();
		c.getSource().sendFeedback(Component.literal("Tickmark: " + verb + " " + k.get().label()));
		return 1;
	}

	private static int remove(CommandContext<FabricClientCommandSource> c) {
		int id = IntegerArgumentType.getInteger(c, "id");
		if (!Tickmark.counters.remove(id)) {
			c.getSource().sendError(Component.literal("No counter #" + id));
			return 0;
		}
		Tickmark.changed();
		c.getSource().sendFeedback(Component.literal("Tickmark: removed #" + id));
		return 1;
	}

	private static int export(CommandContext<FabricClientCommandSource> c, boolean all) {
		List<Counter> list = all ? Tickmark.counters.counters
				: Tickmark.counters.get(IntegerArgumentType.getInteger(c, "id")).map(List::of).orElse(List.of());
		if (list.isEmpty()) {
			c.getSource().sendError(Component.literal("Nothing to export"));
			return 0;
		}
		for (Counter k : list) {
			try {
				Path f = export(k);
				c.getSource().sendFeedback(Component.literal("Tickmark: wrote " + Tickmark.dir().getParent().getParent().relativize(f)));
			} catch (IOException e) {
				Tickmark.LOG.warn("export failed", e);
				c.getSource().sendError(Component.literal("Export of " + k.label() + " failed: " + e.getMessage()));
			}
		}
		return 1;
	}

	static Path export(Counter k) throws IOException {
		Path dir = Tickmark.dir().resolve("exports");
		Files.createDirectories(dir);
		String world = Tickmark.worldKey() == null ? "world" : Tickmark.worldKey();
		String label = k.name == null || k.name.isEmpty() ? Integer.toString(k.id) : k.id + "-" + k.name;
		Path f = dir.resolve(CsvExport.safeName(world + "-" + label + "-" + LocalDateTime.now().format(STAMP)) + ".csv");
		try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
			CsvExport.write(w, k.label(), k.method == null ? dev.tickmark.core.Method.EXACT : k.method, k.series.buckets(),
					ZoneId.systemDefault());
		}
		return f;
	}
}
