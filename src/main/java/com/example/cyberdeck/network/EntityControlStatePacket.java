package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Starts, refreshes, or ends a client-side remote entity camera session. */
public record EntityControlStatePacket(
        boolean active, long token, int targetId, UUID targetUuid, int kind)
        implements CustomPacketPayload {
    public static final Type<EntityControlStatePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "entity_control_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EntityControlStatePacket> STREAM_CODEC =
            StreamCodec.ofMember(EntityControlStatePacket::encode, EntityControlStatePacket::decode);

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(active);
        buffer.writeLong(token);
        buffer.writeVarInt(targetId);
        buffer.writeUUID(targetUuid);
        buffer.writeVarInt(kind);
    }

    private static EntityControlStatePacket decode(RegistryFriendlyByteBuf buffer) {
        return new EntityControlStatePacket(buffer.readBoolean(), buffer.readLong(),
                buffer.readVarInt(), buffer.readUUID(), buffer.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EntityControlStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
                com.example.cyberdeck.client.EntityControlClient.accept(packet));
    }
}
