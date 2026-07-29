package com.example.cyberdeck.movement;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Transient movement state synchronized from the server to clients tracking the player.
 *
 * <p>The direction is always horizontal and normalized. World game ticks are used throughout so
 * renderers can derive action progress without a packet every tick.
 */
public record TacticalMovementState(
        TacticalAction action,
        long actionStartTick,
        long actionEndTick,
        long cooldownUntilTick,
        double directionX,
        double directionZ,
        long lastShotTick) {

    public static final long NO_SHOT_TICK = -1L;
    private static final double MIN_DIRECTION_LENGTH_SQUARED = 1.0e-8;

    public static final StreamCodec<RegistryFriendlyByteBuf, TacticalMovementState> STREAM_CODEC =
            StreamCodec.ofMember(TacticalMovementState::encode, TacticalMovementState::decode);

    public TacticalMovementState {
        action = action == null ? TacticalAction.NONE : action;
        if (action == TacticalAction.NONE) {
            directionX = 0.0;
            directionZ = 0.0;
        } else if (!Double.isFinite(directionX) || !Double.isFinite(directionZ)) {
            directionX = 0.0;
            directionZ = 0.0;
        } else {
            double lengthSquared = directionX * directionX + directionZ * directionZ;
            if (lengthSquared < MIN_DIRECTION_LENGTH_SQUARED) {
                directionX = 0.0;
                directionZ = 0.0;
            } else {
                double inverseLength = 1.0 / Math.sqrt(lengthSquared);
                directionX *= inverseLength;
                directionZ *= inverseLength;
            }
        }
    }

    public static TacticalMovementState idle() {
        return new TacticalMovementState(TacticalAction.NONE, 0L, 0L, 0L, 0.0, 0.0, NO_SHOT_TICK);
    }

    public static TacticalMovementState begin(
            TacticalMovementState previous,
            TacticalAction action,
            long startTick,
            double directionX,
            double directionZ) {
        long endTick = startTick + action.durationTicks();
        return new TacticalMovementState(
                action,
                startTick,
                endTick,
                endTick + action.recoveryTicks(),
                directionX,
                directionZ,
                previous.lastShotTick);
    }

    public boolean isActiveAt(long gameTick) {
        return action.isMovementAction()
                && gameTick >= actionStartTick
                && gameTick < actionEndTick;
    }

    public TacticalMovementState finish() {
        return new TacticalMovementState(
                TacticalAction.NONE,
                actionStartTick,
                actionEndTick,
                cooldownUntilTick,
                0.0,
                0.0,
                lastShotTick);
    }

    public TacticalMovementState withLastShotTick(long gameTick) {
        return new TacticalMovementState(
                action,
                actionStartTick,
                actionEndTick,
                cooldownUntilTick,
                directionX,
                directionZ,
                Math.max(lastShotTick, gameTick));
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(action.networkId());
        buffer.writeVarLong(actionStartTick);
        buffer.writeVarLong(actionEndTick);
        buffer.writeVarLong(cooldownUntilTick);
        buffer.writeDouble(directionX);
        buffer.writeDouble(directionZ);
        buffer.writeVarLong(lastShotTick);
    }

    private static TacticalMovementState decode(RegistryFriendlyByteBuf buffer) {
        TacticalAction action = TacticalAction.fromNetworkId(buffer.readVarInt())
                .orElse(TacticalAction.NONE);
        return new TacticalMovementState(
                action,
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readVarLong());
    }
}
