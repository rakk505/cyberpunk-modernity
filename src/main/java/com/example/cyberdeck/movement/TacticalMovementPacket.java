package com.example.cyberdeck.movement;

import com.example.cyberdeck.Cyberdeck;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client request for a dash or slide. Every field is validated again on the server. */
public record TacticalMovementPacket(int actionId, float forward, float strafe)
        implements CustomPacketPayload {

    public static final Type<TacticalMovementPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "tactical_movement"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TacticalMovementPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    TacticalMovementPacket::actionId,
                    ByteBufCodecs.FLOAT,
                    TacticalMovementPacket::forward,
                    ByteBufCodecs.FLOAT,
                    TacticalMovementPacket::strafe,
                    TacticalMovementPacket::new);

    public TacticalMovementPacket(TacticalAction action, float forward, float strafe) {
        this(action.networkId(), forward, strafe);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TacticalMovementPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            TacticalAction.fromNetworkId(packet.actionId())
                    .filter(TacticalAction::isMovementAction)
                    .ifPresent(action -> TacticalMovement.request(
                            player, action, packet.forward(), packet.strafe()));
        });
    }
}
