package dev.tickmark.client;

import dev.tickmark.core.Counter;
import dev.tickmark.core.Format;
import dev.tickmark.core.Pos;
import dev.tickmark.core.Series;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Outlines of the marked counters and a label with the top item's rate, as vanilla gizmos submitted
 * every client tick (Gauge's approach: works with Sodium and Iris without render hooks).
 */
final class WorldOverlay {
	private static final double RANGE = 96;
	private static final float TEXT = 0.5f;
	private static final float SMALL = 0.4f;

	private WorldOverlay() {
	}

	static void tick(Minecraft mc) {
		if (!Tickmark.config.worldLabels || mc.level == null || mc.player == null) {
			return;
		}
		String dim = Tickmark.dimension(mc.level);
		Vec3 eye = mc.player.getEyePosition();
		for (Counter c : Tickmark.counters.inDimension(dim)) {
			if (c.kind == Counter.Kind.SELF || c.distanceSq(eye.x, eye.y, eye.z) > RANGE * RANGE) {
				continue;
			}
			long now = c.lastTick != Long.MIN_VALUE ? c.lastTick : mc.level.getGameTime();
			Series.Window w = c.series.window(now, Tickmark.config.windowMinutes * Series.BUCKET_TICKS);
			int color = TickmarkHud.trust(c, w);
			double top;
			if (c.kind == Counter.Kind.BOX) {
				AABB box = new AABB(c.boxMin.x(), c.boxMin.y(), c.boxMin.z(), c.boxMax.x() + 1, c.boxMax.y() + 1, c.boxMax.z() + 1);
				Gizmos.cuboid(box.inflate(0.02), GizmoStyle.stroke(color, 2.0f));
				top = box.maxY;
			} else {
				top = Double.NEGATIVE_INFINITY;
				for (Pos p : c.positions) {
					Gizmos.cuboid(new AABB(new BlockPos(p.x(), p.y(), p.z())).inflate(0.02), GizmoStyle.stroke(color, 2.0f));
					top = Math.max(top, p.y() + 1);
				}
			}
			double[] centre = c.centre();
			// above whatever sits on the output (a hopper, a collector): up to 4 blocks of non-air
			BlockPos above = BlockPos.containing(centre[0], top, centre[2]);
			for (int i = 0; i < 4 && !mc.level.getBlockState(above).isAir(); i++) {
				above = above.above();
				top = above.getY();
			}
			Vec3 at = new Vec3(centre[0], top + 0.45, centre[2]);
			String line = c.label() + "  " + (c.paused ? "paused" : c.method == null ? "not measured" : c.method.tag());
			Gizmos.billboardText(line, at.add(0, 0.45, 0), TextGizmo.Style.forColorAndCentered(color).withScale(SMALL))
					.setAlwaysOnTop();
			var ids = c.series.total().itemsByCount();
			if (!ids.isEmpty()) {
				String id = ids.getFirst();
				String name = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id))).getHoverName().getString();
				String rate = Format.rate(w.rate(id, Tickmark.config.unit)) + Tickmark.config.unit.suffix() + " " + name;
				Gizmos.billboardText(rate, at, TextGizmo.Style.forColorAndCentered(0xFFFFFFFF).withScale(TEXT)).setAlwaysOnTop();
			}
		}
		if (Marking.boxMode && Marking.boxCorner != null) {
			Pos p = Marking.boxCorner;
			Gizmos.cuboid(new AABB(new BlockPos(p.x(), p.y(), p.z())).inflate(0.03), GizmoStyle.stroke(0xFF7FC8FF, 3.0f));
		}
	}
}
