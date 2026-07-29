package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * Server -> client snapshot of the caster's ordered quickhack queue. A packet with
 * {@code targetId < 0} clears the queue display and its RAM reservation.
 *
 * @param activeSkillOrdinal ordinal of the active {@link com.example.cyberdeck.skill.Skill}
 * @param targetId     network id of the target entity, or -1 for "no upload"
 * @param startTick    game time the upload began
 * @param endTick      game time the upload completes
 * @param reservedRam  RAM promised to all active and pending queue entries
 * @param skillOrdinals ordered skill ordinals, active first, with at most four entries
 */
public record QuickhackUploadPacket(int activeSkillOrdinal, int targetId, long startTick,
                                    long endTick, int reservedRam, List<Integer> skillOrdinals)
        implements CustomPacketPayload {

    public QuickhackUploadPacket {
        skillOrdinals = List.copyOf(skillOrdinals);
    }

    public static final QuickhackUploadPacket NONE =
            new QuickhackUploadPacket(-1, -1, 0L, 0L, 0, List.of());

    public static final Type<QuickhackUploadPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "quickhack_upload"));

    public static final StreamCodec<RegistryFriendlyByteBuf, QuickhackUploadPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, QuickhackUploadPacket::activeSkillOrdinal,
                    ByteBufCodecs.VAR_INT, QuickhackUploadPacket::targetId,
                    ByteBufCodecs.VAR_LONG, QuickhackUploadPacket::startTick,
                    ByteBufCodecs.VAR_LONG, QuickhackUploadPacket::endTick,
                    ByteBufCodecs.VAR_INT, QuickhackUploadPacket::reservedRam,
                    ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(4)),
                    QuickhackUploadPacket::skillOrdinals,
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
