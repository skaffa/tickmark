package dev.tickmark.client.mixin;

import dev.tickmark.client.Live;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The three packets the multiplayer methods need. Each hook runs after
 * {@code ensureRunningOnSameThread}: the handler is first called on the network thread, which throws
 * there and re-runs it on the client thread. Removal and take hooks run before vanilla applies them,
 * so the entity is still in the level.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
	private static final String ENSURE = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

	@Inject(method = "handleRemoveEntities", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
	private void tickmark$removeEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
		Live.onRemove(packet.entityIds());
	}

	@Inject(method = "handleTakeItemEntity", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
	private void tickmark$takeItem(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
		Live.onTake(packet.getItemId(), packet.getPlayerId(), packet.getAmount());
	}

	@Inject(method = "handleSetTime", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
	private void tickmark$setTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
		Live.onTime(packet.gameTime());
	}
}
