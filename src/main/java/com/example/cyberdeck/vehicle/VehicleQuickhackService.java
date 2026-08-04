package com.example.cyberdeck.vehicle;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.defense.ExplosiveCanisterBlock;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-authoritative mechanics shared by compatible drivable car entities. */
@EventBusSubscriber(modid = Cyberdeck.MODID)
public final class VehicleQuickhackService {
    public static final int SPEED_DURATION_TICKS = 6 * 20;
    public static final double FORCED_SPEED_BLOCKS_PER_TICK = 1.15;
    public static final double REMOTE_DRIVE_SPEED_BLOCKS_PER_TICK = 0.65;
    public static final float REMOTE_TURN_DEGREES_PER_TICK = 4.5F;

    /**
     * Data packs and optional vehicle integrations can opt entity types into car quickhacks without
     * a compile-time dependency on this mod.
     */
    public static final TagKey<net.minecraft.world.entity.EntityType<?>> QUICKHACK_CARS =
            TagKey.create(Registries.ENTITY_TYPE,
                    Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "quickhack_cars"));

    private static final String PREFIX = Cyberdeck.MODID + ":vehicle_quickhack_";
    private static final String SPEED_UNTIL = PREFIX + "speed_until";
    private static final Set<UUID> SPEEDING_CARS = ConcurrentHashMap.newKeySet();
    private static final java.util.Map<UUID, RemoteInput> REMOTE_INPUTS =
            new ConcurrentHashMap<>();

    private record RemoteInput(float throttle, float turn, boolean braking) {
    }

    private VehicleQuickhackService() {
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (SPEEDING_CARS.contains(entity.getUUID())) {
            tick(level, entity);
        }
        RemoteInput remoteInput = REMOTE_INPUTS.get(entity.getUUID());
        if (remoteInput != null && !applyRemoteState(level, entity, remoteInput)) {
            REMOTE_INPUTS.remove(entity.getUUID());
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        CompoundTag data = event.getEntity().getPersistentData();
        long speedUntil = data.getLongOr(SPEED_UNTIL, Long.MIN_VALUE);
        if (speedUntil > level.getGameTime() && isCar(event.getEntity())) {
            SPEEDING_CARS.add(event.getEntity().getUUID());
        } else if (speedUntil != Long.MIN_VALUE) {
            data.remove(SPEED_UNTIL);
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)) {
            return;
        }
        SPEEDING_CARS.remove(event.getEntity().getUUID());
        REMOTE_INPUTS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SPEEDING_CARS.clear();
        REMOTE_INPUTS.clear();
    }

    /** True for native adapters, tagged entity types, and explicitly marked compatibility actors. */
    public static boolean isCar(@Nullable Entity entity) {
        return entity != null
                && !entity.isRemoved()
                && (entity instanceof QuickhackCar
                        || entity.getType().builtInRegistryHolder().is(QUICKHACK_CARS)
                        || entity.getData(
                                VehicleQuickhackAttachments.COMPATIBLE_CAR.get()));
    }

    /** Allows a spawn integration to opt in one entity when its type cannot safely be tagged. */
    public static void markCompatibleCar(Entity entity) {
        entity.setData(VehicleQuickhackAttachments.COMPATIBLE_CAR.get(), true);
    }

    /** Starts or refreshes six seconds of forced forward acceleration. */
    public static boolean speed(ServerLevel level, Entity car) {
        if (!valid(level, car)) {
            return false;
        }
        car.getPersistentData().putLong(SPEED_UNTIL,
                level.getGameTime() + SPEED_DURATION_TICKS);
        SPEEDING_CARS.add(car.getUUID());
        applyForwardSpeed(level, car, FORCED_SPEED_BLOCKS_PER_TICK);
        return true;
    }

    /** Cancels forced acceleration and clears all velocity immediately. */
    public static boolean brake(ServerLevel level, Entity car) {
        if (!valid(level, car)) {
            return false;
        }
        clearForcedSpeed(car);
        if (!(car instanceof QuickhackCar adapter && adapter.applyQuickhackBrake(level))) {
            car.setDeltaMovement(Vec3.ZERO);
            car.hurtMarked = true;
        }
        return true;
    }

    /**
     * Applies one validated remote-driving input sample. Packet code must call this on the server
     * thread after verifying that the sender owns the active control session.
     */
    public static boolean applyRemoteInput(
            ServerLevel level, Entity car, float throttle, float turn, boolean braking) {
        if (!valid(level, car)) {
            return false;
        }
        REMOTE_INPUTS.put(car.getUUID(), new RemoteInput(
                Mth.clamp(throttle, -1.0F, 1.0F),
                Mth.clamp(turn, -1.0F, 1.0F), braking));
        return true;
    }

    public static void clearRemoteInput(Entity car) {
        if (car != null) {
            REMOTE_INPUTS.remove(car.getUUID());
        }
    }

    private static boolean applyRemoteState(
            ServerLevel level, Entity car, RemoteInput input) {
        if (!valid(level, car)) {
            return false;
        }
        if (input.braking()) {
            return brake(level, car);
        }

        float safeThrottle = input.throttle();
        float safeTurn = input.turn();
        if (!(car instanceof QuickhackCar adapter
                && adapter.applyQuickhackSteering(level, safeTurn))) {
            car.setYRot(car.getYRot() + safeTurn * REMOTE_TURN_DEGREES_PER_TICK);
        }
        double speed = isSpeeding(level, car)
                ? FORCED_SPEED_BLOCKS_PER_TICK
                : safeThrottle * REMOTE_DRIVE_SPEED_BLOCKS_PER_TICK;
        applyForwardSpeed(level, car, speed);
        return true;
    }

    /** Destroys only the car while the canister-strength blast damages nearby entities. */
    public static boolean detonate(
            ServerLevel level, Entity car, @Nullable Entity source) {
        if (!valid(level, car)) {
            return false;
        }
        double x = car.getX();
        double y = car.getY() + car.getBbHeight() * 0.5;
        double z = car.getZ();
        clearForcedSpeed(car);
        clearRemoteInput(car);
        if (!(car instanceof QuickhackCar adapter
                && adapter.destroyForQuickhack(level, source))) {
            car.ejectPassengers();
            car.discard();
        }
        ExplosiveCanisterBlock.explodeDeviceAt(level, x, y, z, source);
        return true;
    }

    /** Maintains an active speed quickhack after the vehicle's normal physics tick. */
    public static void tick(ServerLevel level, Entity car) {
        if (!isCar(car) || car.level() != level) {
            return;
        }
        CompoundTag data = car.getPersistentData();
        long endTick = data.getLongOr(SPEED_UNTIL, Long.MIN_VALUE);
        if (endTick == Long.MIN_VALUE) {
            return;
        }
        if (level.getGameTime() >= endTick) {
            data.remove(SPEED_UNTIL);
            SPEEDING_CARS.remove(car.getUUID());
            return;
        }
        applyForwardSpeed(level, car, FORCED_SPEED_BLOCKS_PER_TICK);
    }

    public static boolean isSpeeding(ServerLevel level, Entity car) {
        return isCar(car)
                && car.level() == level
                && car.getPersistentData().getLongOr(SPEED_UNTIL, Long.MIN_VALUE)
                        > level.getGameTime();
    }

    private static void clearForcedSpeed(Entity car) {
        car.getPersistentData().remove(SPEED_UNTIL);
        SPEEDING_CARS.remove(car.getUUID());
    }

    private static void applyForwardSpeed(ServerLevel level, Entity car, double speed) {
        if (car instanceof QuickhackCar adapter
                && adapter.applyQuickhackThrottle(level, speed)) {
            return;
        }
        double radians = Math.toRadians(car.getYRot());
        double x = -Math.sin(radians) * speed;
        double z = Math.cos(radians) * speed;
        car.setDeltaMovement(x, car.getDeltaMovement().y, z);
        car.hurtMarked = true;
    }

    private static boolean valid(ServerLevel level, Entity car) {
        return isCar(car) && car.level() == level && car.isAlive();
    }
}
