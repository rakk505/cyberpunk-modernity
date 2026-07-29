package com.example.cyberdeck.weapon;

import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

/** Server-authoritative Smart Link target acquisition for the Yukimura smart pistol. */
public final class SmartTargeting {
    /** One uninterrupted second on the same visible mob is required for a hard lock. */
    public static final int LOCK_TICKS = 20;
    /** A forgiving but deliberate smart-weapon targeting cone (12 degrees from the crosshair). */
    private static final double MIN_AIM_DOT = 0.9781476007338057;
    /** A completed lock only breaks after the player looks more than 25 degrees away. */
    private static final double LOCK_RETAIN_DOT = 0.9063077870366499;

    private SmartTargeting() {
    }

    public static void tick(ServerPlayer player, ServerLevel level) {
        if (!canAcquire(player)) {
            SmartLockState.clear(player);
            return;
        }

        long now = level.getGameTime();
        SmartLockState current = SmartLockState.get(player);
        Mob stored = storedTarget(level, current);

        // A hard lock persists while the target stays alive, visible, in range, and reasonably
        // near the player's aim. This keeps small hand movements and crossing mobs from stealing it.
        if (current.locked(now) && isValidTarget(player, stored, LOCK_RETAIN_DOT)) {
            if (now == current.endTick()) {
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.55F, 1.8F);
            }
            return;
        }

        // During acquisition, prefer the same candidate while it remains in the tighter cone.
        Mob candidate = isValidTarget(player, stored, MIN_AIM_DOT)
                ? stored
                : findTarget(player, level);
        if (candidate == null) {
            SmartLockState.clear(player);
            return;
        }

        if (current.targetId() != candidate.getId() || !current.acquiring()) {
            SmartLockState.set(player,
                    new SmartLockState(candidate.getId(), now, now + requiredLockTicks(player)));
            return;
        }

    }

    /** Returns the retained hard-lock target when it is still alive, visible, and in range. */
    public static @Nullable LivingEntity lockedTarget(ServerPlayer player, ServerLevel level) {
        SmartLockState state = SmartLockState.get(player);
        if (!canAcquire(player) || !state.locked(level.getGameTime())) {
            return null;
        }
        Mob target = storedTarget(level, state);
        return isValidTarget(player, target, LOCK_RETAIN_DOT) ? target : null;
    }

    private static boolean canAcquire(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        return held.getItem() instanceof GunItem gun
                && gun.gun() == GunType.YUKIMURA
                && CyberwareAttachments.get(player).findFlag("smart_targeting") != null;
    }

    private static int requiredLockTicks(ServerPlayer player) {
        Cyberware smart = CyberwareAttachments.get(player).findFlag("smart_targeting");
        double speed = smart == null ? 0.0 : smart.value("smart_lock_speed_percent") / 100.0;
        return Math.max(4, (int) Math.round(LOCK_TICKS / (1.0 + speed)));
    }

    private static @Nullable Mob findTarget(ServerPlayer player, ServerLevel level) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        double range = GunType.YUKIMURA.range();
        double rangeSqr = range * range;
        AABB search = player.getBoundingBox().inflate(range);

        Mob best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, search,
                entity -> entity.isAlive() && !entity.isSpectator() && entity.isPickable())) {
            Vec3 targetPoint = mob.getBoundingBox().getCenter();
            Vec3 offset = targetPoint.subtract(eye);
            double distanceSqr = offset.lengthSqr();
            if (distanceSqr < 1.0E-4 || distanceSqr > rangeSqr || !player.hasLineOfSight(mob)) {
                continue;
            }
            double dot = look.dot(offset.normalize());
            if (dot < MIN_AIM_DOT) {
                continue;
            }
            // Prefer the mob nearest the reticle center; distance only breaks close ties.
            double score = dot - Math.sqrt(distanceSqr) * 1.0E-5;
            if (score > bestScore) {
                bestScore = score;
                best = mob;
            }
        }
        return best;
    }

    private static @Nullable Mob storedTarget(ServerLevel level, SmartLockState state) {
        Entity entity = level.getEntity(state.targetId());
        return entity instanceof Mob mob ? mob : null;
    }

    private static boolean isValidTarget(ServerPlayer player, @Nullable Mob mob,
                                         double minimumAimDot) {
        if (mob == null || !mob.isAlive() || mob.isSpectator() || !mob.isPickable()) {
            return false;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 offset = mob.getBoundingBox().getCenter().subtract(eye);
        double range = GunType.YUKIMURA.range();
        return offset.lengthSqr() >= 1.0E-4
                && offset.lengthSqr() <= range * range
                && player.hasLineOfSight(mob)
                && player.getLookAngle().normalize().dot(offset.normalize()) >= minimumAimDot;
    }
}
