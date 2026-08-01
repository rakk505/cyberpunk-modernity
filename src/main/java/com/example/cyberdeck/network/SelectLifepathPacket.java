package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.lifepath.LifepathService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client request containing only a stable archetype id; all rewards resolve on the server. */
public record SelectLifepathPacket(String lifepathId) implements CustomPacketPayload {
    private static final int MAX_ID_LENGTH = 24;
    public static final Type<SelectLifepathPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "select_lifepath"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SelectLifepathPacket> STREAM_CODEC =
            StreamCodec.ofMember(SelectLifepathPacket::encode, SelectLifepathPacket::decode);

    public SelectLifepathPacket {
        lifepathId = lifepathId == null ? "" : lifepathId;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(lifepathId, MAX_ID_LENGTH);
    }

    private static SelectLifepathPacket decode(RegistryFriendlyByteBuf buffer) {
        return new SelectLifepathPacket(buffer.readUtf(MAX_ID_LENGTH));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SelectLifepathPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                boolean accepted = LifepathService.select(player, packet.lifepathId());
                PacketDistributor.sendToPlayer(
                        player, new LifepathSelectionResultPacket(accepted));
            }
        });
    }
}
