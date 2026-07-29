package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.CyberdeckState;
import com.example.cyberdeck.ram.RamAttachments;
import com.example.cyberdeck.skill.Skill;
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
 * @param slot     the hotbar slot / skill index (0-7)
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
            // Only allow while the interface is active and the helmet is worn.
            if (!CyberdeckState.isActive(player)) {
                return;
            }
            if (!com.example.cyberdeck.effect.CyberwareEffects.canQuickhack(player)) {
                player.sendSystemMessage(Component.translatable("message.cyberdeck.cyberdeck_required"), true);
                return;
            }
            Skill skill = Skill.fromSlot(packet.slot());
            if (skill == null) {
                return;
            }
            if (!(player.level() instanceof ServerLevel level)) {
                return;
            }
            Entity target = level.getEntity(packet.targetId());
            if (!(target instanceof LivingEntity living) || !living.isAlive()) {
                return;
            }
            // Range guard: prevent applying skills to far away entities.
            if (player.distanceToSqr(living) > 64 * 64) {
                return;
            }
            // One upload at a time per caster.
            if (com.example.cyberdeck.skill.QuickhackUploads.isUploading(player)) {
                return;
            }
            // RAM gate: quickhacks consume RAM. Fail with feedback if the player cannot afford it.
            int cost = com.example.cyberdeck.effect.CyberwareEffects
                    .quickhackRamCost(player, skill);
            if (cost > 0 && !RamAttachments.spend(player, cost)) {
                player.sendSystemMessage(Component.translatable("message.cyberdeck.no_ram",
                        cost, RamAttachments.get(player)), true);
                player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.7f, 0.6f);
                return;
            }
            // Quickhacks upload onto the target over time before taking effect.
            com.example.cyberdeck.skill.QuickhackUploads.start(player, skill, living, level);
        });
    }
}
