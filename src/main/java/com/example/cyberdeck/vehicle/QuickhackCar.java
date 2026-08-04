package com.example.cyberdeck.vehicle;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/** Optional native adapter implemented by a drivable entity that exposes car quickhacks. */
public interface QuickhackCar {
    /** Handles native throttle/forced-speed input; return false to use generic entity velocity. */
    default boolean applyQuickhackThrottle(ServerLevel level, double blocksPerTick) {
        return false;
    }

    /** Handles native steering input; return false to use generic entity yaw. */
    default boolean applyQuickhackSteering(ServerLevel level, float normalizedTurn) {
        return false;
    }

    /** Handles a native emergency brake; return false to use generic zero velocity. */
    default boolean applyQuickhackBrake(ServerLevel level) {
        return false;
    }

    /** Runs provider-specific destruction hooks; the block-safe blast is added by Cyberdeck. */
    default boolean destroyForQuickhack(ServerLevel level, @Nullable Entity source) {
        return false;
    }

    /** Optional client-side camera anchor, such as a tracked driver seat or dashboard entity. */
    default @Nullable Entity quickhackCameraAnchor() {
        return null;
    }
}
