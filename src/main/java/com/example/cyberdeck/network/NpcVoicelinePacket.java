package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.client.NpcVoicelineClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** One server-selected NPC bark delivered only to the player who initiated it. */
public record NpcVoicelinePacket(String speaker, String line, int durationTicks)
        implements CustomPacketPayload {
    private static final int MAX_SPEAKER_LENGTH = 96;
    private static final int MAX_LINE_LENGTH = 1_024;
    public static final Type<NpcVoicelinePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "npc_voiceline"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NpcVoicelinePacket> STREAM_CODEC =
            StreamCodec.ofMember(NpcVoicelinePacket::encode, NpcVoicelinePacket::decode);

    public NpcVoicelinePacket {
        speaker = speaker == null ? "" : speaker;
        line = line == null ? "" : line;
        durationTicks = Mth.clamp(durationTicks, 20, 200);
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(speaker, MAX_SPEAKER_LENGTH);
        buffer.writeUtf(line, MAX_LINE_LENGTH);
        buffer.writeVarInt(durationTicks);
    }

    private static NpcVoicelinePacket decode(RegistryFriendlyByteBuf buffer) {
        return new NpcVoicelinePacket(
                buffer.readUtf(MAX_SPEAKER_LENGTH),
                buffer.readUtf(MAX_LINE_LENGTH),
                buffer.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(NpcVoicelinePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> NpcVoicelineClient.receive(packet));
    }
}
