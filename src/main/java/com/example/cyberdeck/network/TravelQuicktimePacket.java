package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;

import dev.modernity.neoncity.QuicktimeTravelService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client request to travel from one station to the nearest station in a chosen district. */
public record TravelQuicktimePacket(long sourcePos, int districtOrdinal) implements CustomPacketPayload {
    public static final Type<TravelQuicktimePacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "travel_quicktime"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TravelQuicktimePacket> STREAM_CODEC =
            StreamCodec.ofMember(TravelQuicktimePacket::encode, TravelQuicktimePacket::decode);

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(sourcePos);
        buffer.writeVarInt(districtOrdinal);
    }

    private static TravelQuicktimePacket decode(RegistryFriendlyByteBuf buffer) {
        return new TravelQuicktimePacket(buffer.readLong(), buffer.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TravelQuicktimePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                QuicktimeTravelService.travel(
                        player, BlockPos.of(packet.sourcePos()), packet.districtOrdinal());
            }
        });
    }
}
