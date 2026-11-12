package dev.tickmark.client;

import com.mojang.blaze3d.platform.InputConstants;

import dev.tickmark.client.dev.SelfTest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Client entrypoint: keys, events, commands, HUD and the per-tick work. */
public final class TickmarkClient implements ClientModInitializer {
	private static KeyMapping markKey;
	private static KeyMapping hudKey;

	@Override
	public void onInitializeClient() {
		Tickmark.loadConfig();
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Tickmark.MOD_ID, "main"));
		markKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.tickmark.mark", InputConstants.KEY_U, category));
		hudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.tickmark.hud", InputConstants.KEY_I, category));

		ClientTickEvents.END_CLIENT_TICK.register(TickmarkClient::tick);
		UseBlockCallback.EVENT.register(SnapshotTracker::onUse);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			ExactReader.clear();
			Live.clear();
			Tickmark.joinWorld(mc);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			Tickmark.leaveWorld();
			ExactReader.clear();
			Live.clear();
			Marking.boxMode = false;
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> Tickmark.save());
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath(Tickmark.MOD_ID, "panel"),
				TickmarkHud::extract);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> Commands.register(dispatcher, context));
		SelfTest.init();
		Tickmark.LOG.info("Tickmark loaded");
	}

	private static void tick(Minecraft mc) {
		SelfTest.tick(mc);
		if (Commands.openConfig) {
			Commands.openConfig = false;
			mc.gui.setScreen(new ConfigScreen(null));
		}
		if (mc.level == null || mc.player == null) {
			return;
		}
		while (markKey.consumeClick()) {
			Marking.key(mc, mc.player.isShiftKeyDown());
		}
		while (hudKey.consumeClick()) {
			Tickmark.config.hudEnabled = !Tickmark.config.hudEnabled;
			Tickmark.saveConfig();
			Tickmark.say(Tickmark.config.hudEnabled ? "HUD on" : "HUD off");
		}
		ExactReader.tick(mc);
		Live.tick(mc);
		SnapshotTracker.tick(mc);
		WorldOverlay.tick(mc);
		Tickmark.tickSave();
	}
}
