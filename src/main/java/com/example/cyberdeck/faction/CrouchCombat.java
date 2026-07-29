package com.example.cyberdeck.faction;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Server-authoritative stealth and low-cover rules for crouched players. */
public final class CrouchCombat {
    /** Detection strength while crouched and motionless. */
    private static final float CROUCHED_STILL_VISIBILITY = 0.35F;
    /** Moving exposes a crouched player somewhat more, while remaining quieter than standing. */
    private static final float CROUCHED_MOVING_VISIBILITY = 0.55F;
    private static final double MOVING_SPEED_SQR = 0.0004;
    private static final double TORSO_HEIGHT = 0.82;
    private static final double SHOULDER_OFFSET = 0.20;

    private CrouchCombat() {
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
