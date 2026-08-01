package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import dev.modernity.neoncity.AmbientGigService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Explicit refresh used when the player opens the Journal screen. */
public record RequestGigJournalPacket() implements CustomPacketPayload {
    public static final RequestGigJournalPacket INSTANCE = new RequestGigJournalPacket();
    public static final Type<RequestGigJournalPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "request_gig_journal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestGigJournalPacket> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestGigJournalPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                AmbientGigService.syncJournal(player);
            }
        });
    }
}
