package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.client.mission.MissionTrackerClient;
import dev.modernity.neoncity.MissionCatalog;
import dev.modernity.neoncity.MissionService;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Owner-only active mission snapshot shared by normal HUD and city navigation. */
public record MissionSyncPacket(
        boolean active,
        int kindOrdinal,
        int typeOrdinal,
        String title,
        String objective,
        int districtOrdinal,
        int targetX,
        int targetZ,
        int navigationX,
        int navigationZ,
        int reward,
        int streetCred,
        boolean deployed) implements CustomPacketPayload {
    private static final int MAX_TEXT = 256;
    public static final Type<MissionSyncPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "mission_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MissionSyncPacket> STREAM_CODEC =
            StreamCodec.ofMember(MissionSyncPacket::encode, MissionSyncPacket::decode);

    public MissionSyncPacket {
        title = title == null ? "" : title;
        objective = objective == null ? "" : objective;
    }

    public static MissionSyncPacket inactive() {
        return new MissionSyncPacket(
                false, -1, -1, "", "", -1, 0, 0, 0, 0, 0, 0, false);
    }

    public static MissionSyncPacket active(
            MissionService.ContractKind kind,
            MissionCatalog.MissionType type,
            String title,
            String objective,
            int district,
            int targetX,
            int targetZ,
            int navigationX,
            int navigationZ,
            int reward,
            int streetCred,
            boolean deployed) {
        return new MissionSyncPacket(
                true, kind.ordinal(), type.ordinal(), title, objective,
                district, targetX, targetZ, navigationX, navigationZ,
                reward, streetCred, deployed);
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(active);
        if (!active) return;
        buffer.writeVarInt(kindOrdinal);
        buffer.writeVarInt(typeOrdinal);
        buffer.writeUtf(title, MAX_TEXT);
        buffer.writeUtf(objective, MAX_TEXT);
        buffer.writeVarInt(districtOrdinal);
        buffer.writeInt(targetX);
        buffer.writeInt(targetZ);
        buffer.writeInt(navigationX);
        buffer.writeInt(navigationZ);
        buffer.writeVarInt(reward);
        buffer.writeVarInt(streetCred);
        buffer.writeBoolean(deployed);
    }

    private static MissionSyncPacket decode(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) return inactive();
        int kind = buffer.readVarInt();
        if (kind < 0 || kind >= MissionService.ContractKind.values().length) {
            throw new DecoderException("Invalid contract kind " + kind);
        }
        int type = buffer.readVarInt();
        if (type < 0 || type >= MissionCatalog.MissionType.values().length) {
            throw new DecoderException("Invalid mission type " + type);
        }
        return new MissionSyncPacket(
                true,
                kind,
                type,
                buffer.readUtf(MAX_TEXT),
                buffer.readUtf(MAX_TEXT),
                buffer.readVarInt(),
                buffer.readInt(),
                buffer.readInt(),
                buffer.readInt(),
                buffer.readInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MissionSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> MissionTrackerClient.receive(packet));
    }
}
