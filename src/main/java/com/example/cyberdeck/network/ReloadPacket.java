package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.weapon.GunItem;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent from the client to the server when the player presses the reload key while holding a gun.
 * The server starts a reload for the currently held gun (subject to the usual reserve-ammo checks).
 */
public record ReloadPacket() implements CustomPacketPayload {
    public static final Type<ReloadPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "reload"));

    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, ReloadPacket> STREAM_CODEC =
            StreamCodec.unit(new ReloadPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ReloadPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.level() instanceof ServerLevel level)) {
                return;
            }
            ItemStack held = player.getMainHandItem();
            if (held.getItem() instanceof GunItem gun) {
                gun.tryStartReload(level, player, held);
            }
        });
    }
}
