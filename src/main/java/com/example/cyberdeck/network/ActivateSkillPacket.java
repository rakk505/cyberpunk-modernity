package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.CyberdeckState;
import com.example.cyberdeck.skill.QuickhackUploads;
import com.example.cyberdeck.skill.Skill;
import com.example.cyberdeck.skill.DeviceQuickhack;
import com.example.cyberdeck.skill.QuickhackTargets;
import com.example.cyberdeck.defense.KangTaoTurret;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent from the client to the server when the player clicks a skill slot while targeting an entity.
 *
 * @param slot     target-specific scanner action index
 * @param targetId the network id of the targeted entity
 */
public record ActivateSkillPacket(int slot, int targetId) implements CustomPacketPayload {
    public static final Type<ActivateSkillPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "activate_skill"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ActivateSkillPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ActivateSkillPacket::slot,
                    ByteBufCodecs.VAR_INT, ActivateSkillPacket::targetId,
                    ActivateSkillPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ActivateSkillPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // Only allow while the interface is active and a cyberdeck OS is installed.
            if (!CyberdeckState.isActive(player)) {
                return;
            }
            if (!com.example.cyberdeck.effect.CyberwareEffects.canQuickhack(player)) {
                player.sendSystemMessage(Component.translatable(
                        "message.cyberdeck.cyberdeck_required"), true);
                return;
            }
            // Selection belongs to the scanner sidebar, not to vanilla's hotbar. The server still
            // constrains the packet to an action supported by the authoritative target entity.
            if (!(player.level() instanceof ServerLevel level)) {
                return;
            }
            Entity target = level.getEntity(packet.targetId());
            if (target == null || !target.isAlive() || !QuickhackTargets.isActionable(target)) {
                sendFailure(player, "message.cyberdeck.quickhack_invalid_target");
                return;
            }
            if (!QuickhackTargets.isUnderScannerReticle(player, target, level)) {
                sendFailure(player, "message.cyberdeck.quickhack_invalid_target");
                return;
            }

            DeviceQuickhack deviceQuickhack = QuickhackTargets.isDevice(target)
                    ? DeviceQuickhack.fromSlot(target, packet.slot()) : null;
            Skill skill = deviceQuickhack == null ? Skill.fromSlot(packet.slot()) : null;
            if (deviceQuickhack == null
                    && (skill == null || skill == Skill.STANDBY
                            || !(target instanceof LivingEntity living)
                            || !(living instanceof net.minecraft.world.entity.monster.Enemy))) {
                return;
            }

            QuickhackUploads.EnqueueResult result = deviceQuickhack != null
                    ? QuickhackUploads.enqueueDevice(player, deviceQuickhack, target, level)
                    : QuickhackUploads.enqueue(player, skill, (LivingEntity) target, level);
            String actionName = deviceQuickhack != null
                    ? deviceQuickhack.displayName() : skill.displayName();
            int ramCost = deviceQuickhack != null
                    ? com.example.cyberdeck.effect.CyberwareEffects
                            .quickhackRamCost(player, deviceQuickhack)
                    : com.example.cyberdeck.effect.CyberwareEffects
                            .quickhackRamCost(player, skill);
            switch (result.status()) {
                case ACCEPTED -> {
                    // The scanner list, RAM rail and upload marker acknowledge this without a
                    // vanilla action-bar line obscuring the cinematic HUD.
                }
                case INSUFFICIENT_RAM -> {
                    player.sendSystemMessage(Component.translatable("message.cyberdeck.no_ram",
                            ramCost, result.availableRam()), true);
                    playFailure(player);
                }
                case QUEUE_FULL -> {
                    player.sendSystemMessage(Component.translatable(
                            "message.cyberdeck.quickhack_queue_full",
                            QuickhackUploads.MAX_QUEUE_SIZE), true);
                    playFailure(player);
                }
                case DUPLICATE_SKILL -> {
                    player.sendSystemMessage(Component.translatable(
                            "message.cyberdeck.quickhack_duplicate", actionName), true);
                    playFailure(player);
                }
                case INSUFFICIENT_TIER -> {
                    if (target instanceof KangTaoTurret turret) {
                        player.sendSystemMessage(Component.translatable(
                                "message.cyberdeck.turret_tier_required",
                                turret.getSecurityLevel(),
                                KangTaoTurret.cyberdeckSecurityLevel(
                                        com.example.cyberdeck.cyberware.CyberwareAttachments
                                                .get(player))), true);
                    }
                    playFailure(player);
                }
                case INVALID_TARGET -> sendFailure(
                        player, "message.cyberdeck.quickhack_invalid_target");
                case INACTIVE, INVALID_SKILL -> {
                    // Mode and skill checks above normally make these unreachable. Keep the
                    // queue API defensive without giving packet spoofers additional information.
                }
            }
        });
    }

    private static void sendFailure(ServerPlayer player, String translationKey) {
        player.sendSystemMessage(Component.translatable(translationKey), true);
        playFailure(player);
    }

    private static void playFailure(ServerPlayer player) {
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.7f, 0.6f);
    }
}
