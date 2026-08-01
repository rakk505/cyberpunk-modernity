package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import dev.modernity.neoncity.AmbientGigService;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Explicit player acceptance of one stable district-board offer. */
public record AcceptDiscoveredGigPacket(UUID offerId) implements CustomPacketPayload {
    public static final Type<AcceptDiscoveredGigPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "accept_discovered_gig"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AcceptDiscoveredGigPacket> STREAM_CODEC =
            StreamCodec.ofMember(
                    AcceptDiscoveredGigPacket::encode, AcceptDiscoveredGigPacket::decode);

    public AcceptDiscoveredGigPacket {
        if (offerId == null) throw new IllegalArgumentException("Offer id is required");
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(offerId);
    }

    private static AcceptDiscoveredGigPacket decode(RegistryFriendlyByteBuf buffer) {
        return new AcceptDiscoveredGigPacket(buffer.readUUID());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AcceptDiscoveredGigPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                AmbientGigService.accept(player, packet.offerId());
            }
        });
    }
}
