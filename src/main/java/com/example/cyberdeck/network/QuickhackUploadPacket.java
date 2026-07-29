package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server -> client notification about the caster's current quickhack upload, so the client can draw
 * a progress marker over the target. A packet with {@code targetId < 0} clears any active marker.
 *
 * @param skillOrdinal ordinal of the {@link com.example.cyberdeck.skill.Skill} being uploaded
 * @param targetId     network id of the target entity, or -1 for "no upload"
 * @param startTick    game time the upload began
 * @param endTick      game time the upload completes
 */
public record QuickhackUploadPacket(int skillOrdinal, int targetId, long startTick, long endTick)
        implements CustomPacketPayload {

    public static final QuickhackUploadPacket NONE = new QuickhackUploadPacket(0, -1, 0L, 0L);

    public static final Type<QuickhackUploadPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "quickhack_upload"));

    public static final StreamCodec<RegistryFriendlyByteBuf, QuickhackUploadPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, QuickhackUploadPacket::skillOrdinal,
                    ByteBufCodecs.VAR_INT, QuickhackUploadPacket::targetId,
                    ByteBufCodecs.VAR_LONG, QuickhackUploadPacket::startTick,
                    ByteBufCodecs.VAR_LONG, QuickhackUploadPacket::endTick,
                    QuickhackUploadPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(QuickhackUploadPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
                com.example.cyberdeck.client.QuickhackUploadClient.set(packet));
    }
}
