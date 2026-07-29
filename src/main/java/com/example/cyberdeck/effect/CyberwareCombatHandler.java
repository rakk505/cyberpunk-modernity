package com.example.cyberdeck.effect;

import com.example.cyberdeck.cyberware.BodySlot;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

/**
 * Handles melee-related cyberware behavior:
 * <ul>
 *   <li><b>Gorilla Arms</b> - bonus flat damage and a strong horizontal fling on hit.</li>
 *   <li><b>Optical Camo</b> - attacking breaks stealth immediately (balance rule).</li>
 *   <li>Attack visuals/sounds for Gorilla Arms and Mantis Blades.</li>
 * </ul>
 * Gorilla's extra damage is applied in {@link LivingIncomingDamageEvent} so it stacks on top of the
 * normal attack; the knockback + visuals fire from {@link AttackEntityEvent}.
 */
public final class CyberwareCombatHandler {
    private static final float GORILLA_BONUS_DAMAGE = 10.0f;
    private static final double GORILLA_FLING_HORIZONTAL = 2.4;
    private static final double GORILLA_FLING_VERTICAL = 0.55;

    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (armsCyberware(player) == Cyberware.GORILLA_ARMS) {
            event.setAmount(event.getAmount() + GORILLA_BONUS_DAMAGE);
        }
    }

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        Player p = event.getEntity();
        if (!(p instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        // Attacking always breaks Optical Camo stealth (balance).
        if (ActiveAbilities.isOpticalCamoActive(player)) {
            OpticalCamo.deactivate(player);
        }

        if (!(event.getTarget() instanceof LivingEntity target)) {
            return;
        }

        Cyberware arms = armsCyberware(player);
        if (arms == Cyberware.GORILLA_ARMS) {
            flingBack(player, target);
            level.sendParticles(ParticleTypes.SWEEP_ATTACK,
                    target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                    8, 0.3, 0.3, 0.3, 0.0);
            level.sendParticles(ParticleTypes.CRIT,
                    target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                    20, 0.4, 0.4, 0.4, 0.4);
            level.playSound(null, target.getX(), target.getY(), target.getZ(),
                    SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0f, 0.7f);
        } else if (arms == Cyberware.MANTIS_BLADES) {
            level.sendParticles(ParticleTypes.SWEEP_ATTACK,
                    target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ(),
                    3, 0.2, 0.1, 0.2, 0.0);
            level.playSound(null, target.getX(), target.getY(), target.getZ(),
                    SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0f, 1.4f);
        }
    }

    private void flingBack(ServerPlayer player, LivingEntity target) {
        Vec3 dir = target.position().subtract(player.position());
        dir = new Vec3(dir.x, 0, dir.z);
        if (dir.lengthSqr() < 1.0e-4) {
            dir = player.getLookAngle();
            dir = new Vec3(dir.x, 0, dir.z);
        }
        dir = dir.normalize();
        target.push(dir.x * GORILLA_FLING_HORIZONTAL, GORILLA_FLING_VERTICAL, dir.z * GORILLA_FLING_HORIZONTAL);
        target.hurtMarked = true; // ensure the velocity change is synced to clients
    }

    private static Cyberware armsCyberware(ServerPlayer player) {
        return CyberwareAttachments.get(player).get(BodySlot.ARMS);
    }
}
