package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.control.RemoteEntityControl;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Authenticated input sample for the sender's current remote entity session. */
public record EntityControlInputPacket(
        long token, int sequence, float forward, float turn, float yaw, float pitch, int buttons)
        implements CustomPacketPayload {
    public static final int BUTTON_FIRE = 1;
    public static final int BUTTON_BRAKE = 1 << 1;
    public static final int BUTTON_EXIT = 1 << 2;

    public static final Type<EntityControlInputPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "entity_control_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EntityControlInputPacket> STREAM_CODEC =
            StreamCodec.ofMember(EntityControlInputPacket::encode, EntityControlInputPacket::decode);

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(token);
        buffer.writeVarInt(sequence);
        buffer.writeFloat(forward);
        buffer.writeFloat(turn);
        buffer.writeFloat(yaw);
        buffer.writeFloat(pitch);
        buffer.writeByte(buttons);
    }

    private static EntityControlInputPacket decode(RegistryFriendlyByteBuf buffer) {
        return new EntityControlInputPacket(buffer.readLong(), buffer.readVarInt(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                buffer.readUnsignedByte());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EntityControlInputPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                RemoteEntityControl.handleInput(player, packet);
            }
        });
    }
}
