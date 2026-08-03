package com.example.cyberdeck.effect;

import com.example.cyberdeck.cyberware.BodySlot;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Server-side implementations of the key-activated cyberware abilities. All entry points validate
 * that the player actually has the relevant cyberware installed (defense in depth against spoofed
 * packets) and enforce cooldowns where appropriate.
 */
public final class CyberwareActions {
    private static final float ARM_CANNON_POWER = 3.0f;
    private static final int ARM_CANNON_COOLDOWN = 6 * 20;
    private static final double ARM_CANNON_RANGE = 64.0;
    private static final double THRETEVAC_RADIUS = 15.0;
    private static final int THRETEVAC_TICKS = 14 * 20; // 14 seconds
    private static final int THRETEVAC_COOLDOWN = 20 * 20;

    private CyberwareActions() {
    }

    private static Cyberware at(ServerPlayer player, BodySlot slot) {
        return CyberwareAttachments.get(player).get(slot);
    }

    /** Sandevistan (T): slows the world for everyone except the player for a few seconds. */
    public static void sandevistan(ServerPlayer player) {
        Cyberware operatingSystem = at(player, BodySlot.OPERATING_SYSTEM);
        if (operatingSystem != null && operatingSystem.hasFlag("berserk")) {
            CyberwareEffects.toggleBerserk(player, operatingSystem);
            return;
        }
        SandevistanMechanics.ToggleResult result = SandevistanMechanics.toggle(player);
        switch (result) {
            case ACTIVATED -> {
                player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, 0.8f, 0.5f);
                player.sendSystemMessage(Component.translatable("message.cyberdeck.sandevistan"), true);
            }
            case DEACTIVATED -> player.sendSystemMessage(
                    Component.translatable("message.cyberdeck.sandevistan_off"), true);
            case RECHARGING -> player.sendSystemMessage(
                    Component.translatable("message.cyberdeck.sandevistan_recharging"), true);
            case INVALID, DEBOUNCED -> {
            }
        }
    }

    /** Arm Cannon (V): explosion where the player is aiming, with scattered fire at the impact. */
    public static void armCannon(ServerPlayer player) {
        if (CyberwareWeaponEffects.rangedWeaponsBlocked(player)) {
            return;
        }
        Cyberware launcher = CyberwareEffects.findFlag(player, "projectile_launcher");
        if (launcher == null) {
            return;
        }
        if (ActiveAbilities.onCooldown(player, "arm_cannon")) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        ActiveAbilities.setCooldown(player, "arm_cannon", ARM_CANNON_COOLDOWN);

        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getViewVector(1.0f).normalize();
        Vec3 end = eye.add(look.scale(ARM_CANNON_RANGE));
        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 impact = hit.getType() == HitResult.Type.MISS ? end : hit.getLocation();

        // Explosion (does not destroy blocks by default here to avoid griefing; damages entities).
        float power = ARM_CANNON_POWER + launcher.tier().rank() * 0.08f;
        level.explode(player, impact.x, impact.y, impact.z, power,
                Level.ExplosionInteraction.NONE);

        if (launcher.hasFlag("damage_thermal")) {
            scatterFire(level, BlockPos.containing(impact));
        }
        applyLauncherElement(level, player, launcher, impact);

        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, impact.x, impact.y, impact.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.LAVA, impact.x, impact.y, impact.z, 30, 1.2, 0.6, 1.2, 0.1);
        level.playSound(null, impact.x, impact.y, impact.z,
                SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 2.0f, 0.9f);
    }

    private static void applyLauncherElement(
            ServerLevel level, ServerPlayer player, Cyberware launcher, Vec3 impact) {
        AABB area = new AABB(impact, impact).inflate(4.5);
        List<LivingEntity> victims = level.getEntitiesOfClass(LivingEntity.class, area,
                entity -> entity != player && entity.isAlive());
        for (LivingEntity victim : victims) {
            if (launcher.hasFlag("damage_electrical")) {
                victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 6 * 20, 1));
            } else if (launcher.hasFlag("damage_thermal")) {
                victim.igniteForSeconds(6.0f);
            } else if (launcher.hasFlag("damage_chemical")) {
                victim.addEffect(new MobEffectInstance(MobEffects.POISON, 7 * 20, 1));
            }
        }
        if (launcher.hasFlag("damage_electrical")) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    impact.x, impact.y, impact.z, 40, 2.0, 1.0, 2.0, 0.14);
        } else if (launcher.hasFlag("damage_chemical")) {
            level.sendParticles(ParticleTypes.SNEEZE,
                    impact.x, impact.y, impact.z, 35, 1.8, 0.8, 1.8, 0.08);
        }
    }

    private static void scatterFire(ServerLevel level, BlockPos center) {
        java.util.Random rng = new java.util.Random();
        for (int i = 0; i < 14; i++) {
            int dx = rng.nextInt(7) - 3;
            int dz = rng.nextInt(7) - 3;
            // Find a surface near the center column and place fire on top of a solid block.
            for (int dy = 2; dy >= -2; dy--) {
                BlockPos ground = center.offset(dx, dy, dz);
                BlockPos above = ground.above();
                BlockState groundState = level.getBlockState(ground);
                if (!groundState.isAir() && groundState.isSolidRender() && level.getBlockState(above).isAir()) {
                    level.setBlockAndUpdate(above, Blocks.FIRE.defaultBlockState());
                    break;
                }
            }
        }
    }

    /** Legacy P action: ThreatEvac itself is passive; Blood Pump is activated here when installed. */
    public static void thretevac(ServerPlayer player) {
        Cyberware bloodPump = CyberwareEffects.findFlag(player, "blood_pump");
        if (bloodPump == null || ActiveAbilities.onCooldown(player, "blood_pump")) {
            return;
        }
        float instant = (float) Math.max(1.0, bloodPump.value("blood_pump_instant_health"));
        player.heal(instant);
        ActiveAbilities.activate(player, "blood_pump_regen", 6 * 20);
        ActiveAbilities.setCooldown(player, "blood_pump", 30 * 20);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BREWING_STAND_BREW, SoundSource.PLAYERS, 1.0f, 1.2f);
        player.sendSystemMessage(Component.translatable("message.cyberdeck.blood_pump"), true);
    }

    /** Optical Camo (U): toggle invisibility + aggro immunity. */
    public static void opticalCamo(ServerPlayer player) {
        if (CyberwareEffects.findFlag(player, "optical_camo") == null) {
            return;
        }
        OpticalCamo.toggle(player);
    }

    /** Frog Legs double jump: applies an extra upward impulse mid-air. Validated server-side. */
    public static boolean doubleJump(ServerPlayer player) {
        if (CyberwareEffects.findFlag(player, "double_jump") == null
                || !DoubleJumpGuard.tryConsume(player)) {
            return false;
        }
        Vec3 m = player.getDeltaMovement();
        player.setDeltaMovement(m.x, 0.52, m.z);
        player.hurtMarked = true;
        player.fallDistance = 0.0;
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.CLOUD,
                    player.getX(), player.getY(), player.getZ(), 12, 0.3, 0.05, 0.3, 0.02);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.SLIME_JUMP, SoundSource.PLAYERS, 0.6f, 1.4f);
        }
        return true;
    }

    /** Begins a server-timed Fortified Ankles charge after the client suppresses vanilla jumping. */
    public static boolean startChargedJump(ServerPlayer player) {
        return ChargedJump.start(player);
    }

    /** Releases the accumulated Fortified Ankles impulse. */
    public static boolean releaseChargedJump(ServerPlayer player) {
        return ChargedJump.release(player);
    }

    /** Cancels a charge when a screen opens or the input context becomes invalid. */
    public static void cancelChargedJump(ServerPlayer player) {
        ChargedJump.cancel(player);
    }
}
