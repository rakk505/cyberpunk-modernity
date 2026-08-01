package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.client.screen.MerchantQuestScreen;
import dev.modernity.neoncity.MissionCatalog;
import dev.modernity.neoncity.MissionService;
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
        List<MissionService.MissionOffer> offers) implements CustomPacketPayload {
    private static final int MAX_OFFERS = 5;
    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_TEXT_LENGTH = 256;
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
        for (MissionService.MissionOffer offer : offers) {
            buffer.writeUtf(offer.definitionId(), MAX_ID_LENGTH);
            buffer.writeVarInt(offer.type().ordinal());
            buffer.writeUtf(offer.title(), MAX_TEXT_LENGTH);
            buffer.writeUtf(offer.briefing(), MAX_TEXT_LENGTH);
            buffer.writeUtf(offer.objective(), MAX_TEXT_LENGTH);
            buffer.writeVarInt(offer.targetDistrictOrdinal());
            buffer.writeInt(offer.targetX());
            buffer.writeInt(offer.targetZ());
            buffer.writeVarInt(offer.reward());
            buffer.writeVarInt(offer.streetCred());
        }
    }

    private static OpenMerchantQuestPacket decode(RegistryFriendlyByteBuf buffer) {
        int merchantEntityId = buffer.readVarInt();
        int sourceDistrictOrdinal = buffer.readVarInt();
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_OFFERS) {
            throw new DecoderException("Invalid merchant quest count: " + size);
        }
        List<MissionService.MissionOffer> offers = new ArrayList<>(size);
        MissionCatalog.MissionType[] types = MissionCatalog.MissionType.values();
        for (int index = 0; index < size; index++) {
            String definitionId = buffer.readUtf(MAX_ID_LENGTH);
            int typeOrdinal = buffer.readVarInt();
            if (typeOrdinal < 0 || typeOrdinal >= types.length) {
                throw new DecoderException("Invalid merchant mission type: " + typeOrdinal);
            }
            offers.add(new MissionService.MissionOffer(
                    definitionId,
                    types[typeOrdinal],
                    buffer.readUtf(MAX_TEXT_LENGTH),
                    buffer.readUtf(MAX_TEXT_LENGTH),
                    buffer.readUtf(MAX_TEXT_LENGTH),
                    buffer.readVarInt(),
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt()));
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
