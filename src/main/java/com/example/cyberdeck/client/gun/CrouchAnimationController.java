package com.example.cyberdeck.client.gun;

import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

/** Smooth client-side blend and playback clock for the shared Blockbench crouch stance. */
final class CrouchAnimationController {
    private static final float BLEND_PER_SECOND = 7.0F;
    private static final double MOVING_SPEED_SQR = 0.0004;

    private static float blend;
    private static long lastUpdateNanos;

    private CrouchAnimationController() {
    }

    static float update(Player player) {
        long now = System.nanoTime();
        float target = player.getPose() == Pose.CROUCHING ? 1.0F : 0.0F;
        long elapsedNanos = now - lastUpdateNanos;
        if (lastUpdateNanos == 0L || elapsedNanos > 500_000_000L) {
            // No gun was rendered recently; snap to the current stance instead of resuming a stale
            // half-blend from before the weapon was holstered.
            lastUpdateNanos = now;
            blend = target;
            return blend;
        }

        float deltaSeconds = Math.min(0.05F, elapsedNanos / 1_000_000_000.0F);
        lastUpdateNanos = now;
        float step = BLEND_PER_SECOND * deltaSeconds;
        if (blend < target) {
            blend = Math.min(target, blend + step);
        } else if (blend > target) {
            blend = Math.max(target, blend - step);
        }
        return blend;
    }

    static boolean isMoving(Player player) {
        return player.getDeltaMovement().horizontalDistanceSqr() > MOVING_SPEED_SQR;
    }

    static double playbackTime(Player player, float partialTick) {
        return (player.tickCount + partialTick) / 20.0;
    }
}
