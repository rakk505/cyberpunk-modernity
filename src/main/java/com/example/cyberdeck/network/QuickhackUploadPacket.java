package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/** Server-to-client snapshot of every quickhack target owned by one caster. */
public record QuickhackUploadPacket(int reservedRam, List<TargetUpload> uploads)
        implements CustomPacketPayload {
    private static final int MAX_UPLOAD_TARGETS = 4;

    public QuickhackUploadPacket {
        uploads = List.copyOf(uploads);
    }

    /** One independently-timed target queue, with the currently uploading hack first. */
    public record TargetUpload(int activeSkillOrdinal, int targetId, long startTick,
                               long endTick, List<Integer> skillOrdinals) {
        public TargetUpload {
            skillOrdinals = List.copyOf(skillOrdinals);
        }

        private static final StreamCodec<RegistryFriendlyByteBuf, TargetUpload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, TargetUpload::activeSkillOrdinal,
                        ByteBufCodecs.VAR_INT, TargetUpload::targetId,
                        ByteBufCodecs.VAR_LONG, TargetUpload::startTick,
                        ByteBufCodecs.VAR_LONG, TargetUpload::endTick,
                        ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(4)),
                        TargetUpload::skillOrdinals,
                        TargetUpload::new);
    }

    public static final QuickhackUploadPacket NONE =
            new QuickhackUploadPacket(0, List.of());

    public static final Type<QuickhackUploadPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "quickhack_upload"));

    public static final StreamCodec<RegistryFriendlyByteBuf, QuickhackUploadPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, QuickhackUploadPacket::reservedRam,
                    TargetUpload.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_UPLOAD_TARGETS)),
                    QuickhackUploadPacket::uploads,
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
