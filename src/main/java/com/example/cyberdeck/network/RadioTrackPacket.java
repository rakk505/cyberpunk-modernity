package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * What the listener should be playing. A negative ordinal means stop; the server sends one of
 * these whenever a track changes so a party stays in step.
 */
public record RadioTrackPacket(int trackOrdinal) implements CustomPacketPayload {
    public static final Type<RadioTrackPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "radio_track"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RadioTrackPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, RadioTrackPacket::trackOrdinal,
                    RadioTrackPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RadioTrackPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
                com.example.cyberdeck.client.RadioClient.accept(packet.trackOrdinal()));
    }
}
