package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.faction.CrouchCombat;
import com.example.cyberdeck.faction.FactionEnemy;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent from the client to the server when the player presses the stealth-takedown key while a valid
 * crouch-behind takedown prompt is showing. Carries the intended target's entity id, but the server
 * fully re-validates every takedown condition via {@link CrouchCombat} before killing anything, so a
 * spoofed or stale id can never force a kill.
 */
public record StealthTakedownPacket(int targetId) implements CustomPacketPayload {
    public static final Type<StealthTakedownPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "stealth_takedown"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StealthTakedownPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, StealthTakedownPacket::targetId,
                    StealthTakedownPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StealthTakedownPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.level() instanceof ServerLevel level)) {
                return;
            }
            Entity entity = level.getEntity(packet.targetId());
            if (!(entity instanceof FactionEnemy target)) {
                return;
            }
            // Never trust the client: re-check crouch, range, rear-cone and undetected state.
            if (!CrouchCombat.isValidStealthTakedown(player, target)) {
                return;
            }
            level.playSound(null, target.getX(), target.getY(), target.getZ(),
                    SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 0.7f, 1.2f);
            com.example.cyberdeck.effect.ReactiveCyberware.onTakedown(player);
            target.hurtServer(level, level.damageSources().playerAttack(player), Float.MAX_VALUE);
        });
    }
}
