package com.example.cyberdeck.movement;

import com.example.cyberdeck.Cyberdeck;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/** Server-authoritative dash/slide state, validation, and movement simulation. */
public final class TacticalMovement {
    private static final float MAX_INPUT_AXIS = 1.0001F;
    private static final double MIN_INPUT_LENGTH_SQUARED = 0.01;
    private static final double MAX_INPUT_LENGTH_SQUARED = 2.001;
    private static final double MIN_SLIDE_SPEED_SQUARED = 0.0081;

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Cyberdeck.MODID);

    /**
     * Intentionally has no persistence codec: action/cooldown state is not saved to disk or copied
     * onto a respawned player. The stream codec only synchronizes live animation state.
     */
    public static final Supplier<AttachmentType<TacticalMovementState>> STATE =
            ATTACHMENT_TYPES.register("tactical_movement", () -> AttachmentType
                    .builder(TacticalMovementState::idle)
                    .sync(TacticalMovementState.STREAM_CODEC)
                    .build());

    public static TacticalMovementState get(Player player) {
        return player.getData(STATE.get());
    }

    /** Called by the weapon system after a server-authoritative shot is accepted. */
    public static void markShot(ServerPlayer player) {
        player.setData(STATE.get(), get(player).withLastShotTick(player.level().getGameTime()));
    }

    /**
     * Validates and starts a requested move. The client supplies intent only; timing, direction,
     * speed, pose, and cooldown remain server-owned.
     */
    public static boolean request(
            ServerPlayer player,
            TacticalAction action,
            float forward,
            float strafe) {
        if (action == null || !validInputAxes(forward, strafe) || !action.isMovementAction()) {
            return false;
        }

        long gameTick = player.level().getGameTime();
        TacticalMovementState current = get(player);
        if (!canStart(current, action, gameTick) || !canPlayerStart(player, action)) {
            return false;
        }

        Vec3 direction = yawRelativeDirection(player.getYRot(), forward, strafe);
        if (direction.horizontalDistanceSqr() < 0.99) {
            return false;
        }

        // Do not let a custom packet invent an input direction. The requested axes must agree
        // with the most recent vanilla movement intent already accepted by the server.
        Vec3 acceptedIntent = player.getLastClientMoveIntent();
        double acceptedLengthSquared = acceptedIntent.horizontalDistanceSqr();
        if (acceptedLengthSquared < MIN_INPUT_LENGTH_SQUARED) {
            return false;
        }
        acceptedIntent = new Vec3(acceptedIntent.x, 0.0, acceptedIntent.z).normalize();
        if (direction.dot(acceptedIntent) < 0.95) {
            return false;
        }
        direction = acceptedIntent;

        TacticalMovementState started = TacticalMovementState.begin(
                current, action, gameTick, direction.x, direction.z);
        player.setData(STATE.get(), started);
        if (action == TacticalAction.SLIDE) {
            applySlidePose(player);
            player.setSprinting(false);
        }
        applyVelocity(player, started, gameTick);
        return true;
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        TacticalMovementState state = get(player);
        if (!state.action().isMovementAction()) {
            return;
        }

        long gameTick = player.level().getGameTime();
        if (!state.isActiveAt(gameTick) || !canPlayerContinue(player, state.action())) {
            finish(player, state);
            return;
        }

        if (state.action() == TacticalAction.SLIDE) {
            applySlidePose(player);
        }
        applyVelocity(player, state, gameTick);
    }

    /** Pure packet-axis validation, exposed for regression tests. */
    public static boolean validInputAxes(float forward, float strafe) {
        if (!Float.isFinite(forward) || !Float.isFinite(strafe)
                || Math.abs(forward) > MAX_INPUT_AXIS
                || Math.abs(strafe) > MAX_INPUT_AXIS) {
            return false;
        }
        double lengthSquared = (double) forward * forward + (double) strafe * strafe;
        return lengthSquared >= MIN_INPUT_LENGTH_SQUARED
                && lengthSquared <= MAX_INPUT_LENGTH_SQUARED;
    }

    /**
     * Pure conversion from local input to a normalized horizontal world direction. Positive
     * forward follows view yaw; positive strafe points to the player's right.
     */
    public static Vec3 yawRelativeDirection(float yawDegrees, float forward, float strafe) {
        if (!validInputAxes(forward, strafe)) {
            return Vec3.ZERO;
        }
        double yawRadians = Math.toRadians(yawDegrees);
        double sin = Math.sin(yawRadians);
        double cos = Math.cos(yawRadians);
        double x = -sin * forward - cos * strafe;
        double z = cos * forward - sin * strafe;
        double length = Math.sqrt(x * x + z * z);
        return length < 1.0e-6 ? Vec3.ZERO : new Vec3(x / length, 0.0, z / length);
    }

    /** Pure cooldown/state validation, independent of an entity or level. */
    public static boolean canStart(
            TacticalMovementState state, TacticalAction action, long gameTick) {
        return action != null
                && action.isMovementAction()
                && (state.action() == TacticalAction.NONE || !state.isActiveAt(gameTick))
                && gameTick >= state.cooldownUntilTick();
    }

    /** Pure normalized action progress in the inclusive range [0, 1]. */
    public static double actionProgress(
            long actionStartTick, long actionEndTick, long gameTick) {
        long duration = actionEndTick - actionStartTick;
        if (duration <= 0L) {
            return 1.0;
        }
        return clamp01((double) (gameTick - actionStartTick) / duration);
    }

    /** Pure speed curve used identically by request startup and subsequent server ticks. */
    public static double speedFor(TacticalAction action, double progress) {
        double t = clamp01(progress);
        double eased = t * t * (3.0 - 2.0 * t);
        return switch (action) {
            case DASH -> lerp(1.15, 0.46, eased);
            case SLIDE -> lerp(0.78, 0.14, eased);
            case NONE -> 0.0;
        };
    }

    /** Pure horizontal replacement that preserves existing vertical momentum. */
    public static Vec3 velocityFor(Vec3 currentVelocity, Vec3 direction, double speed) {
        return new Vec3(direction.x * speed, currentVelocity.y, direction.z * speed);
    }

    public static boolean firedRecently(
            TacticalMovementState state, long gameTick, int windowTicks) {
        return state.lastShotTick() >= 0L
                && gameTick >= state.lastShotTick()
                && gameTick - state.lastShotTick() <= Math.max(0, windowTicks);
    }

    private static boolean canPlayerStart(ServerPlayer player, TacticalAction action) {
        if (!player.isAlive()
                || player.isSpectator()
                || player.isPassenger()
                || player.isSleeping()
                || player.isFallFlying()
                || player.isInWater()
                || player.isInLava()
                || player.getAbilities().flying
                || player.getForcedPose() != null
                || !player.onGround()
                || player.horizontalCollision) {
            return false;
        }
        if (action == TacticalAction.SLIDE) {
            Vec3 velocity = player.getDeltaMovement();
            return player.isSprinting()
                    && velocity.x * velocity.x + velocity.z * velocity.z
                    >= MIN_SLIDE_SPEED_SQUARED;
        }
        return true;
    }

    private static boolean canPlayerContinue(ServerPlayer player, TacticalAction action) {
        if (!player.isAlive()
                || player.isSpectator()
                || player.isPassenger()
                || player.isSleeping()
                || player.isFallFlying()
                || player.isInWater()
                || player.isInLava()
                || player.getAbilities().flying
                || player.horizontalCollision) {
            return false;
        }
        return action != TacticalAction.SLIDE || player.onGround();
    }

    private static void applyVelocity(
            ServerPlayer player, TacticalMovementState state, long gameTick) {
        double progress = actionProgress(
                state.actionStartTick(), state.actionEndTick(), gameTick);
        double speed = speedFor(state.action(), progress);
        Vec3 direction = new Vec3(state.directionX(), 0.0, state.directionZ());
        player.setDeltaMovement(velocityFor(player.getDeltaMovement(), direction, speed));
        player.hurtMarked = true;
    }

    private static void finish(ServerPlayer player, TacticalMovementState state) {
        if (state.action() == TacticalAction.SLIDE) {
            safelyExitSlidePose(player);
        }
        player.setData(STATE.get(), state.finish());
    }

    private static void applySlidePose(ServerPlayer player) {
        player.setForcedPose(Pose.SWIMMING);
        if (!player.hasPose(Pose.SWIMMING)) {
            player.setPose(Pose.SWIMMING);
            player.refreshDimensions();
        }
    }

    private static void safelyExitSlidePose(ServerPlayer player) {
        if (player.getForcedPose() == Pose.SWIMMING) {
            player.setForcedPose(null);
        }
        if (!player.hasPose(Pose.SWIMMING) || player.isInWater()) {
            return;
        }

        Pose target = player.isShiftKeyDown() ? Pose.CROUCHING : Pose.STANDING;
        if (!canOccupyPose(player, target)) {
            target = Pose.CROUCHING;
        }
        if (!canOccupyPose(player, target)) {
            // A low ceiling is safer than forcing the standing hitbox into blocks.
            return;
        }
        player.setPose(target);
        player.refreshDimensions();
    }

    private static boolean canOccupyPose(ServerPlayer player, Pose pose) {
        AABB bounds = player.getLocalBoundsForPose(pose)
                .move(player.position())
                .deflate(1.0e-4);
        return player.level().noCollision(player, bounds);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }
}
