package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.effect.CyberwareActions;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server request to trigger a key-activated cyberware ability. The server re-validates
 * that the player actually has the corresponding cyberware installed before acting.
 *
 * @param action ordinal of {@link Action}
 */
public record CyberwareActionPacket(int action) implements CustomPacketPayload {

    /** The set of client-triggerable cyberware actions. */
    public enum Action {
        SANDEVISTAN,
        ARM_CANNON,
        THRETEVAC,
        OPTICAL_CAMO,
        DOUBLE_JUMP,
        CHARGED_JUMP_START,
        CHARGED_JUMP_RELEASE,
        CHARGED_JUMP_CANCEL;

        public static final Action[] VALUES = values();
    }

    public static final Type<CyberwareActionPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "cyberware_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CyberwareActionPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, CyberwareActionPacket::action,
                    CyberwareActionPacket::new);

    public CyberwareActionPacket(Action action) {
        this(action.ordinal());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CyberwareActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (packet.action() < 0 || packet.action() >= Action.VALUES.length) {
                return;
            }
            switch (Action.VALUES[packet.action()]) {
                case SANDEVISTAN -> CyberwareActions.sandevistan(player);
                case ARM_CANNON -> CyberwareActions.armCannon(player);
                case THRETEVAC -> CyberwareActions.thretevac(player);
                case OPTICAL_CAMO -> CyberwareActions.opticalCamo(player);
                case DOUBLE_JUMP -> CyberwareActions.doubleJump(player);
                case CHARGED_JUMP_START -> CyberwareActions.startChargedJump(player);
                case CHARGED_JUMP_RELEASE -> CyberwareActions.releaseChargedJump(player);
                case CHARGED_JUMP_CANCEL -> CyberwareActions.cancelChargedJump(player);
            }
        });
    }
}
