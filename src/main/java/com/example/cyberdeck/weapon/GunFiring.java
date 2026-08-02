package com.example.cyberdeck.weapon;

import com.example.cyberdeck.effect.SandevistanMechanics;
import com.example.cyberdeck.defense.ExplosiveCanisterBlock;
import com.example.cyberdeck.defense.KangTaoTurret;
import com.example.cyberdeck.faction.Faction;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.movement.TacticalMovement;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.npc.GunshotAlerts;
import net.minecraft.core.particles.DustParticleOptions;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
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
    private static final String MISSION_INSTANCE_TAG = "cyberdeck_mission_instance";

    private GunFiring() {
    }

    /**
     * Fires {@code gun} from {@code shooter}. Applies damage/particles/sound on the server. The
     * caller is responsible for ammo and cooldown; this method only resolves the shot.
     */
    public static void fire(ServerLevel level, LivingEntity shooter, GunType gun) {
        Vec3 direction = shooter.getViewVector(1.0F).normalize();
        Vec3 origin = shooter.getEyePosition();
        fire(level, shooter, gun, origin, direction, false);
    }

    /**
     * Fires from an explicit world-space origin and direction while retaining the gun's normal
     * damage, falloff, spread, penetration, sound, and canister interactions.
     */
    public static void fire(
            ServerLevel level,
            LivingEntity shooter,
            GunType gun,
            Vec3 origin,
            Vec3 direction) {
        if (direction.lengthSqr() < 1.0E-8) {
            return;
        }
        fire(level, shooter, gun, origin, direction.normalize(), true);
    }

    private static void fire(
            ServerLevel level,
            LivingEntity shooter,
            GunType gun,
            Vec3 origin,
            Vec3 direction,
            boolean originIsMuzzle) {
        RandomSource rng = shooter.getRandom();
        GunshotAlerts.emit(level, shooter, gun);
        if (shooter instanceof ServerPlayer player) {
            TacticalMovement.markShot(player);
        }

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

        Vec3 eye = origin;
        Vec3 baseDir = direction;

        for (int i = 0; i < gun.pellets(); i++) {
            float spread = gun.spreadDegrees();
            if (shooter instanceof ServerPlayer player) {
                double reduction = com.example.cyberdeck.effect.CyberwareEffects
                        .sumValue(player, "spread_reduction_percent") / 100.0;
                spread *= (float) (1.0 - Math.min(0.9, reduction));
            }
            Vec3 dir = applySpread(baseDir, spread, rng);
            Vec3 end = eye.add(dir.scale(gun.range()));

            ShotPath path = traceBlocks(level, shooter, gun, eye, end, dir);
            Vec3 rayEnd = path.rayEnd();

            // Treat the penetrated cell as solid space for entity targeting: check the segment in
            // front of it first, then resume beyond its exit face. This prevents hitting an entity
            // whose bounding box happens to overlap the wall itself.
            EntityHitResult hit;
            if (path.penetrationEntry() == null) {
                hit = findEntityHit(shooter, eye, rayEnd);
            } else {
                hit = findEntityHit(shooter, eye, path.penetrationEntry());
                if (hit == null) {
                    Vec3 resumed = path.penetrationExit().add(dir.scale(PENETRATION_EPSILON));
                    hit = findEntityHit(shooter, resumed, rayEnd);
                }
            }

            Vec3 impact = rayEnd;
            if (hit != null && hit.getEntity() instanceof LivingEntity target) {
                impact = hit.getLocation();
                double dist = eye.distanceTo(impact);
                float dmg = gun.damageAtDistance(dist);
                DamageSource source = damageSource(shooter, gun);
                if (shooter instanceof ServerPlayer player) {
                    SandevistanMechanics.hurtWithGunModifiers(
                            level, player, target, source, dmg, impact);
                } else {
                    target.hurtServer(level, source, dmg);
                }
                // A single crisp spark at the hit, no spread so it reads as a clean impact
                // instead of a lingering cloud.
                if (gun.isTech()) {
                    level.sendParticles(TECH_PARTICLE, true, false,
                            impact.x, impact.y, impact.z, 4, 0.04, 0.04, 0.04, 0.0);
                } else {
                    level.sendParticles(ParticleTypes.CRIT,
                            impact.x, impact.y, impact.z, 1, 0.0, 0.0, 0.0, 0.0);
                }
            }

            detonateReachedCanisters(level, shooter, eye, impact, path);

            if (path.penetrationEntry() != null
                    && eye.distanceToSqr(impact) >= eye.distanceToSqr(path.penetrationEntry())) {
                spawnPenetrationEffect(level, path.penetrationEntry());
                spawnPenetrationEffect(level, path.penetrationExit());
            }

            // Explicit-origin callers supply the real muzzle, while ordinary weapons retain their
            // legacy eye offset and near-camera particle skip.
            Vec3 tracerOrigin = originIsMuzzle ? eye : eye.add(dir.scale(1.2));
            spawnBulletTrail(level, tracerOrigin, impact, gun.isTech(),
                    originIsMuzzle ? 0 : TRAIL_MUZZLE_SKIP);
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
    private static void spawnBulletTrail(ServerLevel level, Vec3 muzzle, Vec3 impact,
                                         boolean tech, int muzzleSkip) {
        Vec3 delta = impact.subtract(muzzle);
        double length = delta.length();
        if (length < 1.0e-4) {
            return;
        }
        int steps = Math.min(TRAIL_MAX_POINTS, Math.max(1, (int) (length / TRAIL_STEP)));
        Vec3 stepVec = delta.scale(1.0 / steps);
        // Ordinary weapons skip the near-camera segment; explicit muzzle shots start at the tip.
        Vec3 point = muzzle.add(stepVec.scale(muzzleSkip));
        for (int s = muzzleSkip; s <= steps; s++) {
            if (tech) {
                // Override the 32-block packet radius so long-range Tech sniper tracers remain
                // visible to the shooter.
                level.sendParticles(TECH_PARTICLE, true, false,
                        point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0);
            } else {
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                        point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0);
            }
            point = point.add(stepVec);
        }
    }

    /**
     * Clips a hitscan ray against terrain. Conventional rounds stop at the first collision. Tech
     * rounds resume immediately beyond that collision's block cell and then stop at the next one,
     * which permits a one-block wall but never a contiguous two-block wall.
     */
    private static ShotPath traceBlocks(ServerLevel level, LivingEntity shooter, GunType gun,
                                        Vec3 start, Vec3 end, Vec3 direction) {
        BlockHitResult first = level.clipIncludingBorder(new ClipContext(
                start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
        if (first.getType() == HitResult.Type.MISS) {
            return new ShotPath(end, null, null, null, null);
        }
        if (!gun.isTech() || first.isWorldBorderHit()
                || level.getBlockState(first.getBlockPos())
                        .getDestroySpeed(level, first.getBlockPos()) < 0.0F) {
            return new ShotPath(first.getLocation(), null, null, first, null);
        }

        Vec3 entry = first.getLocation();
        Vec3 exit = new AABB(first.getBlockPos()).clip(end, entry).orElse(null);
        if (exit == null) {
            return new ShotPath(entry, null, null, first, null);
        }
        Vec3 resumed = exit.add(direction.scale(PENETRATION_EPSILON));
        if (resumed.distanceToSqr(end) >= exit.distanceToSqr(end)) {
            return new ShotPath(exit, entry, exit, first, null);
        }

        BlockHitResult second = level.clipIncludingBorder(new ClipContext(
                resumed, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
        Vec3 rayEnd = second.getType() == HitResult.Type.MISS ? end : second.getLocation();
        return new ShotPath(rayEnd, entry, exit, first,
                second.getType() == HitResult.Type.MISS ? null : second);
    }

    private static void detonateReachedCanisters(
            ServerLevel level, LivingEntity shooter, Vec3 eye, Vec3 impact, ShotPath path) {
        detonateReachedCanister(level, shooter, eye, impact, path.firstBlockHit());
        BlockHitResult second = path.secondBlockHit();
        if (second != null && (path.firstBlockHit() == null
                || !second.getBlockPos().equals(path.firstBlockHit().getBlockPos()))) {
            detonateReachedCanister(level, shooter, eye, impact, second);
        }
    }

    private static void detonateReachedCanister(
            ServerLevel level, LivingEntity shooter, Vec3 eye, Vec3 impact,
            BlockHitResult blockHit) {
        if (blockHit != null
                && eye.distanceToSqr(blockHit.getLocation())
                        <= eye.distanceToSqr(impact) + IMPACT_DISTANCE_EPSILON) {
            ExplosiveCanisterBlock.detonate(level, blockHit.getBlockPos(), shooter);
        }
    }

    private static EntityHitResult findEntityHit(LivingEntity shooter, Vec3 start, Vec3 end) {
        if (start.distanceToSqr(end) < 1.0E-8) {
            return null;
        }
        return ProjectileUtil.getEntityHitResult(
                shooter, start, end, new AABB(start, end).inflate(1.0),
                entity -> canHitTarget(shooter, entity),
                start.distanceToSqr(end));
    }

    /** Shared hitscan filtering for city combatants. */
    public static boolean canHitTarget(LivingEntity shooter, Entity entity) {
        if (!(entity instanceof LivingEntity living)
                || entity == shooter
                || !entity.isAlive()
                || entity.isSpectator()) {
            return false;
        }
        if (shooter instanceof KangTaoTurret) {
            String missionInstance = shooter.getPersistentData()
                    .getString(MISSION_INSTANCE_TAG).orElse("");
            if (!missionInstance.isBlank()
                    && missionInstance.equals(living.getPersistentData()
                            .getString(MISSION_INSTANCE_TAG).orElse(""))) {
                return false;
            }
            if (living instanceof CityNpc || living instanceof KangTaoTurret) {
                return false;
            }
            if (living instanceof FactionEnemy guard
                    && guard.getFaction() == Faction.KANG_TAO) {
                return false;
            }
        }
        return (!(shooter instanceof FactionEnemy || shooter instanceof CityNpc)
                || !(living instanceof CityNpc));
    }

    private static void spawnPenetrationEffect(ServerLevel level, Vec3 point) {
        level.sendParticles(TECH_PARTICLE, true, false,
                point.x, point.y, point.z, 7, 0.08, 0.08, 0.08, 0.015);
    }

    /** Spacing in blocks between successive tracer particles along a bullet trail. */
    private static final double TRAIL_STEP = 1.6;
    /** Hard cap on tracer particles per shot so long-range hits stay cheap. */
    private static final int TRAIL_MAX_POINTS = 16;
    /** Number of near-muzzle tracer points to skip so the streak doesn't bloom in the camera. */
    private static final int TRAIL_MUZZLE_SKIP = 1;
    /** Cyan shared by Tech tracers, impacts, and wall-penetration sparks. */
    private static final DustParticleOptions TECH_PARTICLE =
            new DustParticleOptions(0x26E6FF, 0.9F);
    private static final double PENETRATION_EPSILON = 1.0E-4;
    private static final double IMPACT_DISTANCE_EPSILON = 1.0E-7;

    private record ShotPath(
            Vec3 rayEnd,
            Vec3 penetrationEntry,
            Vec3 penetrationExit,
            BlockHitResult firstBlockHit,
            BlockHitResult secondBlockHit) {
    }

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

    private static DamageSource damageSource(LivingEntity shooter, GunType gun) {
        if (gun.baseGun() != GunType.MANTIS_BLADE) {
            return shooter.damageSources().source(CyberdeckDamageTypes.BULLET, shooter, shooter);
        }
        if (shooter instanceof Player player) {
            return shooter.damageSources().playerAttack(player);
        }
        return shooter.damageSources().mobAttack(shooter);
    }

    private static SoundEvent fireSound(GunType gun) {
        return switch (gun.baseGun()) {
            case SHOTGUN, M2038, CARNAGE -> SoundEvents.GENERIC_EXPLODE.value();
            case SNIPER, GRAD -> SoundEvents.FIREWORK_ROCKET_BLAST;
            default -> SoundEvents.CROSSBOW_SHOOT;
        };
    }

    private static float pitchFor(GunType gun, RandomSource rng) {
        float base = switch (gun.baseGun()) {
            case SNIPER, GRAD -> 0.7f;
            case SHOTGUN, M2038, CARNAGE -> 0.6f;
            case ASSAULT_RIFLE, AJAX, COPPERHEAD -> 1.3f;
            case SMG, SARATOGA, G58_DIAN, YUKIMURA -> 1.6f;
            case PISTOL, OVERTURE, UNITY, THREE_FIVE_ONE_SIX -> 1.1f;
            case MANTIS_BLADE -> 0.9f;
            default -> 1.1f; // baseGun() never returns a Tech variant.
        };
        return base + (rng.nextFloat() - 0.5f) * 0.1f;
    }
}
