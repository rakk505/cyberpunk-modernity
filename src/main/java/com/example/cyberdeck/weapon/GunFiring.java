package com.example.cyberdeck.weapon;

import com.example.cyberdeck.effect.SandevistanMechanics;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side hitscan resolution shared by player guns and faction enemies. Firing traces one ray
 * per pellet from the shooter's eyes along their look direction (jittered by the gun's spread),
 * stops at the first solid block, and damages the first living entity along the way with
 * distance-based falloff. This makes precise aim the deciding factor in every fight.
 */
public final class GunFiring {
    private GunFiring() {
    }

    /**
     * Fires {@code gun} from {@code shooter}. Applies damage/particles/sound on the server. The
     * caller is responsible for ammo and cooldown; this method only resolves the shot.
     */
    public static void fire(ServerLevel level, LivingEntity shooter, GunType gun) {
        RandomSource rng = shooter.getRandom();

        // Yukimura remains a conventional hitscan pistol until a player with Smart Link finishes
        // a server-authoritative lock. Enemies and pre-lock shots retain the normal gun behavior.
        if (gun == GunType.YUKIMURA && shooter instanceof ServerPlayer player) {
            LivingEntity lockedTarget = SmartTargeting.lockedTarget(player, level);
            if (lockedTarget != null) {
                SmartBullet.spawn(level, player, lockedTarget);
                playFireSound(level, shooter, gun, rng);
                return;
            }
        }

        Vec3 eye = shooter.getEyePosition();
        Vec3 baseDir = shooter.getViewVector(1.0f).normalize();

        for (int i = 0; i < gun.pellets(); i++) {
            float spread = gun.spreadDegrees();
            if (shooter instanceof ServerPlayer player) {
                double reduction = com.example.cyberdeck.effect.CyberwareEffects
                        .sumValue(player, "spread_reduction_percent") / 100.0;
                spread *= (float) (1.0 - Math.min(0.9, reduction));
            }
            Vec3 dir = applySpread(baseDir, spread, rng);
            Vec3 end = eye.add(dir.scale(gun.range()));

            // Stop the ray at the first solid block so bullets can't shoot through walls.
            HitResult block = level.clip(new ClipContext(
                    eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
            Vec3 rayEnd = block.getType() == HitResult.Type.MISS ? end : block.getLocation();

            EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                    shooter, eye, rayEnd,
                    shooter.getBoundingBox().expandTowards(dir.scale(gun.range())).inflate(1.0),
                    e -> e instanceof LivingEntity && e != shooter && e.isAlive() && !e.isSpectator(),
                    gun.range() * gun.range());

            Vec3 impact = rayEnd;
            if (hit != null && hit.getEntity() instanceof LivingEntity target) {
                impact = hit.getLocation();
                double dist = eye.distanceTo(impact);
                float dmg = gun.damageAtDistance(dist);
                DamageSource source = damageSource(shooter);
                if (shooter instanceof ServerPlayer player) {
                    SandevistanMechanics.hurtWithGunModifiers(
                            level, player, target, source, dmg, impact);
                } else {
                    target.hurtServer(level, source, dmg);
                }
                // A single crisp spark at the hit, no spread so it reads as a clean impact
                // instead of a lingering cloud.
                level.sendParticles(ParticleTypes.CRIT,
                        impact.x, impact.y, impact.z, 1, 0.0, 0.0, 0.0, 0.0);
            }

            // Bullet trail: a thin, fast-fading tracer from the muzzle to the impact point.
            Vec3 muzzle = eye.add(dir.scale(1.2));
            spawnBulletTrail(level, muzzle, impact);
        }

        playFireSound(level, shooter, gun, rng);
    }

    private static void playFireSound(ServerLevel level, LivingEntity shooter,
                                      GunType gun, RandomSource rng) {
        SoundEvent sound = fireSound(gun);
        level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(),
                sound, SoundSource.PLAYERS, 1.0f, pitchFor(gun, rng));
    }

    /**
     * Draws a sleek, Cyberpunk-style bullet tracer as a sparse line of short-lived
     * {@link ParticleTypes#ELECTRIC_SPARK} particles from the muzzle to the impact point. Unlike the
     * old dense END_ROD beam, electric-spark particles are small, bright and fade within a fraction
     * of a second, so the shot reads as a quick streak rather than a persistent glowing line that
     * clutters the view. Points are widely spaced and skip the muzzle stub so the tracer doesn't
     * bloom right in front of the camera.
     */
    private static void spawnBulletTrail(ServerLevel level, Vec3 muzzle, Vec3 impact) {
        Vec3 delta = impact.subtract(muzzle);
        double length = delta.length();
        if (length < 1.0e-4) {
            return;
        }
        int steps = Math.min(TRAIL_MAX_POINTS, Math.max(1, (int) (length / TRAIL_STEP)));
        Vec3 stepVec = delta.scale(1.0 / steps);
        // Start a little past the muzzle so the tracer doesn't flash across the player's face.
        Vec3 point = muzzle.add(stepVec.scale(TRAIL_MUZZLE_SKIP));
        for (int s = TRAIL_MUZZLE_SKIP; s <= steps; s++) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, point.x, point.y, point.z,
                    1, 0.0, 0.0, 0.0, 0.0);
            point = point.add(stepVec);
        }
    }

    /** Spacing in blocks between successive tracer particles along a bullet trail. */
    private static final double TRAIL_STEP = 1.6;
    /** Hard cap on tracer particles per shot so long-range hits stay cheap. */
    private static final int TRAIL_MAX_POINTS = 16;
    /** Number of near-muzzle tracer points to skip so the streak doesn't bloom in the camera. */
    private static final int TRAIL_MUZZLE_SKIP = 1;

    private static Vec3 applySpread(Vec3 dir, float spreadDegrees, RandomSource rng) {
        if (spreadDegrees <= 0.0f) {
            return dir;
        }
        double spreadRad = Math.toRadians(spreadDegrees);
        double yaw = (rng.nextDouble() - 0.5) * 2.0 * spreadRad;
        double pitch = (rng.nextDouble() - 0.5) * 2.0 * spreadRad;
        // Build an orthonormal basis around dir and nudge it.
        Vec3 up = Math.abs(dir.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 right = dir.cross(up).normalize();
        Vec3 realUp = right.cross(dir).normalize();
        return dir.add(right.scale(Math.tan(yaw))).add(realUp.scale(Math.tan(pitch))).normalize();
    }

    private static DamageSource damageSource(LivingEntity shooter) {
        if (shooter instanceof Player player) {
            return shooter.damageSources().playerAttack(player);
        }
        return shooter.damageSources().mobAttack(shooter);
    }

    private static SoundEvent fireSound(GunType gun) {
        return switch (gun) {
            case SHOTGUN, M2038, CARNAGE -> SoundEvents.GENERIC_EXPLODE.value();
            case SNIPER, GRAD -> SoundEvents.FIREWORK_ROCKET_BLAST;
            default -> SoundEvents.CROSSBOW_SHOOT;
        };
    }

    private static float pitchFor(GunType gun, RandomSource rng) {
        float base = switch (gun) {
            case SNIPER, GRAD -> 0.7f;
            case SHOTGUN, M2038, CARNAGE -> 0.6f;
            case ASSAULT_RIFLE, AJAX, COPPERHEAD -> 1.3f;
            case SMG, SARATOGA, G58_DIAN, YUKIMURA -> 1.6f;
            case PISTOL, OVERTURE, UNITY, THREE_FIVE_ONE_SIX -> 1.1f;
            case MANTIS_BLADE -> 0.9f;
        };
        return base + (rng.nextFloat() - 0.5f) * 0.1f;
    }
}
