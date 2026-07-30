package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import dev.modernity.neoncity.MerchantQuestService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client selection for a fixer offer; the server regenerates and validates the offer. */
public record AcceptMerchantQuestPacket(
        int merchantEntityId,
        int offerIndex) implements CustomPacketPayload {
    public static final Type<AcceptMerchantQuestPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "accept_merchant_quest"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AcceptMerchantQuestPacket> STREAM_CODEC =
            StreamCodec.ofMember(AcceptMerchantQuestPacket::encode, AcceptMerchantQuestPacket::decode);

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(merchantEntityId);
        buffer.writeVarInt(offerIndex);
    }

    private static AcceptMerchantQuestPacket decode(RegistryFriendlyByteBuf buffer) {
        return new AcceptMerchantQuestPacket(buffer.readVarInt(), buffer.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AcceptMerchantQuestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                MerchantQuestService.accept(
                        player, packet.merchantEntityId(), packet.offerIndex());
            }
        });
    }
}
