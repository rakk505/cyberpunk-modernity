package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.cyberware.BodySlot;
import com.example.cyberdeck.cyberware.CyberwareInstaller;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server request from the cyberware screen to uninstall the cyberware in a body slot.
 * The removed cyberware is returned to the player as an item (dropped if the inventory is full).
 *
 * @param slot ordinal of the {@link BodySlot} to clear
 * @param socket physical socket index inside that body system
 */
public record RemoveCyberwarePacket(int slot, int socket) implements CustomPacketPayload {
    public static final Type<RemoveCyberwarePacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "remove_cyberware"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RemoveCyberwarePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, RemoveCyberwarePacket::slot,
                    ByteBufCodecs.VAR_INT, RemoveCyberwarePacket::socket,
                    RemoveCyberwarePacket::new);

    public RemoveCyberwarePacket(int slot) {
        this(slot, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RemoveCyberwarePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (packet.slot() < 0 || packet.slot() >= BodySlot.VALUES.length) {
                return;
            }
            BodySlot slot = BodySlot.VALUES[packet.slot()];
            if (packet.socket() < 0 || packet.socket() >= slot.maximumSockets()) {
                return;
            }
            CyberwareInstaller.remove(player, slot, packet.socket());
        });
    }
}
