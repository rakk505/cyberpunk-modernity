package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.CyberdeckState;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent from the client to the server when the player presses the toggle key while wearing a cyberdeck.
 */
public record ToggleInterfacePacket() implements CustomPacketPayload {
    public static final Type<ToggleInterfacePacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "toggle_interface"));

    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, ToggleInterfacePacket> STREAM_CODEC =
            StreamCodec.unit(new ToggleInterfacePacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ToggleInterfacePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                CyberdeckState.toggle(player);
            }
        });
    }
}
