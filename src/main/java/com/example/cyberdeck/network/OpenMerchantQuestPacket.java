package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.client.screen.MerchantQuestScreen;
import dev.modernity.neoncity.MerchantQuestService;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server-owned fixer job list shown when a black truck merchant is used. */
public record OpenMerchantQuestPacket(
        int merchantEntityId,
        int sourceDistrictOrdinal,
        List<MerchantQuestService.QuestOffer> offers) implements CustomPacketPayload {
    private static final int MAX_OFFERS = 5;
    private static final int MAX_CARGO_LENGTH = 80;
    public static final Type<OpenMerchantQuestPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "open_merchant_quests"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenMerchantQuestPacket> STREAM_CODEC =
            StreamCodec.ofMember(OpenMerchantQuestPacket::encode, OpenMerchantQuestPacket::decode);

    public OpenMerchantQuestPacket {
        offers = offers == null ? List.of()
                : List.copyOf(offers.subList(0, Math.min(MAX_OFFERS, offers.size())));
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(merchantEntityId);
        buffer.writeVarInt(sourceDistrictOrdinal);
        buffer.writeVarInt(offers.size());
        for (MerchantQuestService.QuestOffer offer : offers) {
            buffer.writeVarInt(offer.targetDistrictOrdinal());
            buffer.writeInt(offer.targetX());
            buffer.writeInt(offer.targetZ());
            buffer.writeVarInt(offer.reward());
            buffer.writeUtf(offer.cargo(), MAX_CARGO_LENGTH);
            buffer.writeBoolean(offer.local());
        }
    }

    private static OpenMerchantQuestPacket decode(RegistryFriendlyByteBuf buffer) {
        int merchantEntityId = buffer.readVarInt();
        int sourceDistrictOrdinal = buffer.readVarInt();
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_OFFERS) {
            throw new DecoderException("Invalid merchant quest count: " + size);
        }
        List<MerchantQuestService.QuestOffer> offers = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            offers.add(new MerchantQuestService.QuestOffer(
                    buffer.readVarInt(),
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readVarInt(),
                    buffer.readUtf(MAX_CARGO_LENGTH),
                    buffer.readBoolean()));
        }
        return new OpenMerchantQuestPacket(merchantEntityId, sourceDistrictOrdinal, offers);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenMerchantQuestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> MerchantQuestScreen.open(packet));
    }
}
