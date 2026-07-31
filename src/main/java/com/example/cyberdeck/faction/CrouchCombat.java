package com.example.cyberdeck.faction;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Server-authoritative stealth and low-cover rules for crouched players. */
public final class CrouchCombat {
    /** Detection strength while crouched and motionless. */
    private static final float CROUCHED_STILL_VISIBILITY = 0.35F;
    /** Moving exposes a crouched player somewhat more, while remaining quieter than standing. */
    private static final float CROUCHED_MOVING_VISIBILITY = 0.55F;
    private static final double MOVING_SPEED_SQR = 0.0004;
    private static final double TORSO_HEIGHT = 0.82;
    private static final double SHOULDER_OFFSET = 0.20;

    /** Maximum distance (blocks) between player and enemy for a stealth takedown. */
    public static final double TAKEDOWN_RANGE = 2.5;
    private static final double TAKEDOWN_RANGE_SQR = TAKEDOWN_RANGE * TAKEDOWN_RANGE;
    /**
     * The player must sit inside the enemy's rear cone: the enemy's flat look direction must point
     * away from the player. cos(150deg) = -0.866 means the enemy->player vector is within 30deg of
     * directly behind the enemy.
     */
    private static final double REAR_CONE_COS = -0.5;
    private static final double MIN_DIRECTION_LENGTH_SQR = 1.0E-5;

    private CrouchCombat() {
    }

    /**
     * Finds the best stealth-takedown target near {@code player}: an alive {@link FactionEnemy}
     * that is within {@link #TAKEDOWN_RANGE} blocks, that the player is positioned directly behind
     * (inside the enemy's rear cone), and that has NOT detected the player (not triggered and not
     * currently targeting the player). Returns the nearest qualifying enemy, or {@code null}.
     *
     * <p>Used both by the client HUD heuristic (to decide when to show the prompt) and re-checked
     * server-side before an actual takedown so the client can never force a kill.
     */
    public static FactionEnemy findStealthTakedownTarget(Player player) {
        if (!isCrouched(player)) {
            return null;
        }
        AABB box = player.getBoundingBox().inflate(TAKEDOWN_RANGE);
        List<FactionEnemy> enemies = player.level().getEntitiesOfClass(
                FactionEnemy.class, box, FactionEnemy::isAlive);
        FactionEnemy best = null;
        double bestDistSqr = Double.MAX_VALUE;
        for (FactionEnemy enemy : enemies) {
            if (!isValidStealthTakedown(player, enemy)) {
                continue;
            }
            double distSqr = player.distanceToSqr(enemy);
            if (distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
                best = enemy;
            }
        }
        return best;
    }

    /**
     * True if {@code enemy} can currently be stealth-killed by {@code player}: player crouched, in
     * range, positioned behind the enemy, and the enemy is unaware of the player.
     */
    public static boolean isValidStealthTakedown(Player player, FactionEnemy enemy) {
        if (enemy == null || !enemy.isAlive() || !isCrouched(player)) {
            return false;
        }
        // The enemy must not have noticed the player yet.
        if (enemy.isTriggered() || enemy.getTarget() == player) {
            return false;
        }
        if (player.distanceToSqr(enemy) > TAKEDOWN_RANGE_SQR) {
            return false;
        }
        return isBehind(player, enemy);
    }

    /** True if {@code player} stands within the rear cone of {@code enemy}. */
    private static boolean isBehind(Player player, LivingEntity enemy) {
        Vec3 look = enemy.getViewVector(1.0F);
        Vec3 flatLook = new Vec3(look.x, 0.0, look.z);
        if (flatLook.lengthSqr() < MIN_DIRECTION_LENGTH_SQR) {
            return false;
        }
        Vec3 toPlayer = new Vec3(
                player.getX() - enemy.getX(), 0.0, player.getZ() - enemy.getZ());
        if (toPlayer.lengthSqr() < MIN_DIRECTION_LENGTH_SQR) {
            return false;
        }
        // Player is behind the enemy when the enemy's look direction points away from the player.
        return flatLook.normalize().dot(toPlayer.normalize()) <= REAR_CONE_COS;
    }

    /** Uses the authoritative pose so animation, eye height, and collision dimensions agree. */
    public static boolean isCrouched(Player player) {
        return player.getPose() == Pose.CROUCHING;
    }

    /** Multiplier applied to both detection range and detection buildup. */
    public static float visibility(Player player) {
        if (!isCrouched(player)) {
            return 1.0F;
        }
        return player.getDeltaMovement().horizontalDistanceSqr() > MOVING_SPEED_SQR
                ? CROUCHED_MOVING_VISIBILITY
                : CROUCHED_STILL_VISIBILITY;
    }

    /** Crouching also shortens the distance at which an unaware soldier can acquire the player. */
    public static float detectionRangeMultiplier(Player player) {
        if (!isCrouched(player)) {
            return 1.0F;
        }
        return player.getDeltaMovement().horizontalDistanceSqr() > MOVING_SPEED_SQR
                ? 0.75F
                : 0.60F;
    }

    /**
     * Treats a crouched player's torso as the combat target rather than their still-partly-visible
     * eye line. If at least two of three waist/shoulder rays hit collision geometry, a one-block
     * structure counts as hard cover for detection and ranged combat.
     */
    public static boolean hasLowCover(LivingEntity observer, Player player) {
        if (!isCrouched(player)) {
            return false;
        }

        Vec3 eye = observer.getEyePosition();
        Vec3 torso = new Vec3(player.getX(), player.getY() + TORSO_HEIGHT, player.getZ());
        Vec3 horizontal = player.position().subtract(observer.position()).multiply(1.0, 0.0, 1.0);
        Vec3 lateral = horizontal.lengthSqr() < 1.0E-6
                ? new Vec3(SHOULDER_OFFSET, 0.0, 0.0)
                : new Vec3(-horizontal.z, 0.0, horizontal.x)
                        .normalize().scale(SHOULDER_OFFSET);

        int blocked = 0;
        if (isBlocked(observer, eye, torso)) {
            blocked++;
        }
        if (isBlocked(observer, eye, torso.add(lateral))) {
            blocked++;
        }
        if (isBlocked(observer, eye, torso.subtract(lateral))) {
            blocked++;
        }
        return blocked >= 2;
    }

    private static boolean isBlocked(LivingEntity observer, Vec3 start, Vec3 end) {
        HitResult hit = observer.level().clip(new ClipContext(
                start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, observer));
        return hit.getType() == HitResult.Type.BLOCK;
    }
}
