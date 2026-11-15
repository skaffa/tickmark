package dev.tickmark.client;

import dev.tickmark.core.Counter;
import dev.tickmark.core.SnapshotLog;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multiplayer snapshot method, from Gauge's container tracker: the block the player
 * right-clicked is bound to the container screen that opens, if its slot count matches. A few ticks
 * after opening (contents arrive after the screen) the contents are measured against the last close;
 * on close the contents become the new baseline. Time is the server's game time from the time packets.
 */
final class SnapshotTracker {
	private static final int OPEN_WINDOW = 60;
	/** Ticks to wait after the screen opens before reading: the contents packet follows the open packet. */
	private static final int SETTLE = 3;

	private static BlockPos clicked;
	private static long clickedTick;
	private static Screen boundScreen;
	private static Counter boundCounter;
	private static String boundKey;
	private static int openTicks;
	private static Map<String, Long> latest;

	private SnapshotTracker() {
	}

	static InteractionResult onUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (level.isClientSide()) {
			clicked = hit.getBlockPos().immutable();
			clickedTick = level.getGameTime();
		}
		return InteractionResult.PASS;
	}

	static void tick(Minecraft mc) {
		Screen screen = mc.gui.screen();
		if (screen != boundScreen) {
			close();
			boundScreen = screen;
			if (screen instanceof AbstractContainerScreen<?> acs && !Tickmark.exact(mc)) {
				bind(mc, acs.getMenu());
			}
		}
		if (boundCounter == null || !(screen instanceof AbstractContainerScreen<?> acs)) {
			return;
		}
		openTicks++;
		latest = read(acs.getMenu());
		if (openTicks == SETTLE) {
			long t = Live.serverTime();
			if (t != Long.MIN_VALUE && log().opened(latest, t)) {
				Tickmark.changed();
				Tickmark.LOG.debug("snapshot {} {}: {} over {} ticks", boundCounter.label(), boundKey, log().gain, log().gainTicks);
			}
		}
	}

	private static SnapshotLog log() {
		return boundCounter.snapshots.computeIfAbsent(boundKey, k -> new SnapshotLog());
	}

	private static void close() {
		if (boundCounter != null && latest != null && openTicks >= SETTLE && Live.serverTime() != Long.MIN_VALUE) {
			log().closed(latest, Live.serverTime());
			Tickmark.changed();
		}
		boundCounter = null;
		boundKey = null;
		latest = null;
		openTicks = 0;
	}

	private static void bind(Minecraft mc, AbstractContainerMenu menu) {
		if (clicked == null || mc.level == null || mc.level.getGameTime() - clickedTick > OPEN_WINDOW) {
			return;
		}
		BlockPos pos = clicked;
		clicked = null;
		if (!(mc.level.getBlockEntity(pos) instanceof Container be)) {
			return;
		}
		List<BlockPos> blocks = new ArrayList<>();
		blocks.add(pos);
		int expected = be.getContainerSize();
		BlockState s = mc.level.getBlockState(pos);
		if (s.getBlock() instanceof ChestBlock && s.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			BlockPos other = ChestBlock.getConnectedBlockPos(pos, s);
			if (mc.level.getBlockEntity(other) instanceof Container o) {
				expected += o.getContainerSize();
				blocks.add(other);
			}
		}
		if (containerSlots(menu).size() != expected) {
			return;
		}
		String dim = Tickmark.dimension(mc.level);
		for (BlockPos b : blocks) {
			var c = Tickmark.counters.holding(dim, Marking.pos(b));
			if (c.isPresent()) {
				boundCounter = c.get();
				// one key per container: the lowest block of a double chest
				blocks.sort(BlockPos::compareTo);
				boundKey = Marking.pos(blocks.getFirst()).toString();
				openTicks = 0;
				return;
			}
		}
	}

	private static List<Slot> containerSlots(AbstractContainerMenu menu) {
		List<Slot> out = new ArrayList<>();
		for (Slot slot : menu.slots) {
			if (!(slot.container instanceof Inventory)) {
				out.add(slot);
			}
		}
		return out;
	}

	private static Map<String, Long> read(AbstractContainerMenu menu) {
		Map<String, Long> out = new LinkedHashMap<>();
		for (Slot slot : containerSlots(menu)) {
			ItemStack st = slot.getItem();
			if (!st.isEmpty()) {
				out.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), (long) st.getCount(), Long::sum);
			}
		}
		return out;
	}
}
