package com.example.cyberdeck.effect;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-owned once-per-airborne-cycle validation for double-jump requests. */
public final class DoubleJumpGuard {
    public static final int MIN_AIRBORNE_TICKS = 2;
    public static final int COOLDOWN_TICKS = 10;
    private static final double SUPPORT_PROBE_DEPTH = 0.08;
    private static final double SUPPORT_PROBE_INSET = 0.04;

    private static final Map<UUID, State> STATES = new HashMap<>();

    private DoubleJumpGuard() {
    }

    /** Advances trusted airborne time; client packets never advance or reset this state. */
    public static void tick(ServerPlayer player) {
        State state = STATES.computeIfAbsent(player.getUUID(), ignored -> new State());
        if (isPhysicallySupported(player)) {
            state.airborneTicks = 0;
            state.consumed = false;
        } else {
            state.airborneTicks = Math.min(200, state.airborneTicks + 1);
        }
    }

    /** Atomically consumes the only allowed double jump in the current airborne cycle. */
    public static boolean tryConsume(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator() || player.isPassenger()
                || player.isInWater() || player.isInLava() || player.isFallFlying()
                || player.getAbilities().flying) {
            return false;
        }

        State state = STATES.computeIfAbsent(player.getUUID(), ignored -> new State());
        boolean supported = isPhysicallySupported(player);
        long gameTick = player.level().getGameTime();
        if (!canConsume(supported, state.airborneTicks, state.consumed,
                state.cooldownUntilTick, gameTick)) {
            if (supported) {
                state.airborneTicks = 0;
                state.consumed = false;
            }
            return false;
        }

        state.consumed = true;
        state.cooldownUntilTick = gameTick + COOLDOWN_TICKS;
        return true;
    }

    /** Pure form of the security invariant, exposed for deterministic regression tests. */
    public static boolean canConsume(boolean physicallySupported, int airborneTicks,
                                     boolean consumed, long cooldownUntilTick, long gameTick) {
        return !physicallySupported
                && airborneTicks >= MIN_AIRBORNE_TICKS
                && !consumed
                && gameTick >= cooldownUntilTick;
    }

    public static void forget(UUID playerId) {
        STATES.remove(playerId);
    }

    public static void clearAll() {
        STATES.clear();
    }

    private static boolean isPhysicallySupported(ServerPlayer player) {
        if (player.verticalCollisionBelow) {
            return true;
        }
        AABB bounds = player.getBoundingBox();
        AABB supportProbe = new AABB(
                bounds.minX + SUPPORT_PROBE_INSET,
                bounds.minY - SUPPORT_PROBE_DEPTH,
                bounds.minZ + SUPPORT_PROBE_INSET,
                bounds.maxX - SUPPORT_PROBE_INSET,
                bounds.minY + 0.01,
                bounds.maxZ - SUPPORT_PROBE_INSET);
        return !player.level().noBlockCollision(player, supportProbe);
    }

    private static final class State {
        private int airborneTicks;
        private boolean consumed;
        private long cooldownUntilTick;
    }
}
