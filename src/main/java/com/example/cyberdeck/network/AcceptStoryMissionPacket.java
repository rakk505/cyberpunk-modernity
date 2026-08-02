package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import dev.modernity.neoncity.AmbientGigService;
import dev.modernity.neoncity.MissionService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Validated Journal acceptance request for one unlocked mainline mission. */
public record AcceptStoryMissionPacket(String missionId) implements CustomPacketPayload {
    private static final int MAX_ID = 64;
    public static final Type<AcceptStoryMissionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "accept_story_mission"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AcceptStoryMissionPacket> STREAM_CODEC =
            StreamCodec.ofMember(AcceptStoryMissionPacket::encode, AcceptStoryMissionPacket::decode);

    public AcceptStoryMissionPacket {
        if (missionId == null || !missionId.matches("[a-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid story mission id");
        }
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(missionId, MAX_ID);
    }

    private static AcceptStoryMissionPacket decode(RegistryFriendlyByteBuf buffer) {
        return new AcceptStoryMissionPacket(buffer.readUtf(MAX_ID));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AcceptStoryMissionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                MissionService.startStory(player, packet.missionId());
                AmbientGigService.syncJournal(player);
            }
        });
    }
}
