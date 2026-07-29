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
    private static final int SANDEVISTAN_TICKS = 8 * 20; // 8 seconds
    private static final int SANDEVISTAN_COOLDOWN = 20 * 20; // 20 seconds
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
        if (at(player, BodySlot.OPERATING_SYSTEM) != Cyberware.SANDEVISTAN) {
            return;
        }
        if (ActiveAbilities.isSandevistanActive(player) || ActiveAbilities.onCooldown(player, "sandevistan")) {
            return;
        }
        ActiveAbilities.sandevistan.put(player.getUUID(), SANDEVISTAN_TICKS);
        ActiveAbilities.setCooldown(player, "sandevistan", SANDEVISTAN_TICKS + SANDEVISTAN_COOLDOWN);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, 0.8f, 0.5f);
        player.sendSystemMessage(Component.translatable("message.cyberdeck.sandevistan"), true);
    }

    /** Arm Cannon (V): explosion where the player is aiming, with scattered fire at the impact. */
    public static void armCannon(ServerPlayer player) {
        if (at(player, BodySlot.ARMS) != Cyberware.ARM_CANNON) {
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
        level.explode(player, impact.x, impact.y, impact.z, ARM_CANNON_POWER, Level.ExplosionInteraction.NONE);

        // Scatter fire around the impact on exposed top faces.
        scatterFire(level, BlockPos.containing(impact));

        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, impact.x, impact.y, impact.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.LAVA, impact.x, impact.y, impact.z, 30, 1.2, 0.6, 1.2, 0.1);
        level.playSound(null, impact.x, impact.y, impact.z,
                SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 2.0f, 0.9f);
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

    /** Thretevac (P): highlight all hostile mobs within 15 blocks for 14 seconds. */
    public static void thretevac(ServerPlayer player) {
        if (at(player, BodySlot.NERVOUS_SYSTEM) != Cyberware.THRETEVAC) {
            return;
        }
        if (ActiveAbilities.onCooldown(player, "thretevac")) {
            return;
        }
        ActiveAbilities.setCooldown(player, "thretevac", THRETEVAC_COOLDOWN);

        AABB area = player.getBoundingBox().inflate(THRETEVAC_RADIUS);
        List<Mob> mobs = player.level().getEntitiesOfClass(Mob.class, area,
                m -> m instanceof Enemy && m.isAlive());
        for (Mob mob : mobs) {
            mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, THRETEVAC_TICKS, 0, false, false, false));
        }
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SCULK_BLOCK_CHARGE, SoundSource.PLAYERS, 1.0f, 1.2f);
        player.sendSystemMessage(Component.translatable("message.cyberdeck.thretevac",
                mobs.size()), true);
    }

    /** Optical Camo (U): toggle invisibility + aggro immunity. */
    public static void opticalCamo(ServerPlayer player) {
        if (at(player, BodySlot.INTEGUMENTARY_SYSTEM) != Cyberware.OPTICAL_CAMO) {
            return;
        }
        OpticalCamo.toggle(player);
    }

    /** Frog Legs double jump: applies an extra upward impulse mid-air. Validated server-side. */
    public static void doubleJump(ServerPlayer player) {
        if (at(player, BodySlot.LEGS) != Cyberware.FROG_LEGS) {
            return;
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
    }
}
