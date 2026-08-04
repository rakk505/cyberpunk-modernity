package com.example.cyberdeck.vehicle;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.npc.CityNpcEntities;
import com.example.cyberdeck.npc.NpcRole;
import com.modernity.vehicle_mod.api.RemoteControllableVehicle;
import com.modernity.vehicle_mod.api.TrafficVehicle;
import com.modernity.vehicle_mod.api.VehicleApi;
import dev.modernity.neoncity.NeonCityGenerator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Supplies bounded city traffic with drivers and road-following input. */
@EventBusSubscriber(modid = Cyberdeck.MODID)
public final class CityTrafficService {
    private static final String TRAFFIC_VEHICLE_KEY = Cyberdeck.MODID + ":traffic_vehicle";
    private static final String TRAFFIC_DRIVER_KEY = Cyberdeck.MODID + ":traffic_driver";
    private static final int INPUT_INTERVAL_TICKS = 2;
    private static final int RECONCILE_INTERVAL_TICKS = 100;
    private static final int LANE_PROBE_INTERVAL_TICKS = 6;
    private static final int ROUTE_DECISION_TICKS = 30;
    private static final int BLOCKED_REROUTE_TICKS = 24;
    private static final int ROAD_PROBE_DISTANCE = 24;
    private static final int LANE_PROBE_RADIUS = 8;
    private static final double OBSTACLE_LOOKAHEAD = 6.0;
    private static final Map<UUID, RouteState> ROUTES = new HashMap<>();

    private CityTrafficService() {
    }

    private static final class RouteState {
        private Direction heading;
        private int decisionTicks;
        private int blockedTicks;
        private int laneProbeTicks;
        private Vec3 laneTarget;

        private RouteState(Direction heading) {
            this.heading = heading;
        }
    }

    /** Creates and seats a visible civilian driver for a vehicle already in the level. */
    public static boolean assignDriver(
            ServerLevel level, Entity vehicle, RandomSource random) {
        TrafficVehicle traffic = VehicleApi.findTraffic(vehicle).orElse(null);
        if (traffic == null || !vehicle.getPassengers().isEmpty()
                || traffic.controllingDriver() != null) return false;

        CityNpc driver = CityNpcEntities.CITY_NPC.get().create(
                level, EntitySpawnReason.NATURAL);
        if (driver == null) return false;
        driver.snapTo(vehicle.getX(), vehicle.getY() + 0.25, vehicle.getZ(),
                vehicle.getYRot(), 0.0F);
        driver.finalizeSpawn(level, level.getCurrentDifficultyAt(vehicle.blockPosition()),
                EntitySpawnReason.NATURAL, null);
        driver.setRole(random.nextInt(100) < 20 ? NpcRole.CORPO : NpcRole.RESIDENT);
        driver.setSkinVariant(random.nextInt(CityNpc.SKIN_COUNT));
        driver.getPersistentData().putBoolean(TRAFFIC_DRIVER_KEY, true);
        vehicle.getPersistentData().putBoolean(TRAFFIC_VEHICLE_KEY, true);

        if (!level.addFreshEntity(driver) || !driver.startRiding(vehicle, true, true)) {
            driver.discard();
            vehicle.getPersistentData().remove(TRAFFIC_VEHICLE_KEY);
            return false;
        }
        ROUTES.put(vehicle.getUUID(), new RouteState(headingFromYaw(vehicle.getYRot())));
        return true;
    }

    public static boolean isTrafficDriver(Entity entity) {
        return entity != null
                && entity.getPersistentData().getBooleanOr(TRAFFIC_DRIVER_KEY, false);
    }

    public static boolean hasTrafficDriver(Entity vehicle) {
        return vehicle != null && vehicle.getPassengers().stream()
                .anyMatch(CityTrafficService::isTrafficDriver);
    }

    public static boolean isManagedPair(Entity vehicle, Entity rider) {
        return vehicle != null
                && rider != null
                && vehicle.getPersistentData().getBooleanOr(TRAFFIC_VEHICLE_KEY, false)
                && isTrafficDriver(rider);
    }

    /** Relinquishes traffic ownership so hacking or a player can take control immediately. */
    public static void releaseForControl(ServerLevel level, Entity vehicle) {
        if (vehicle == null
                || !vehicle.getPersistentData().getBooleanOr(TRAFFIC_VEHICLE_KEY, false)) {
            return;
        }
        for (Entity passenger : java.util.List.copyOf(vehicle.getPassengers())) {
            if (!(passenger instanceof CityNpc driver) || !isTrafficDriver(driver)) continue;
            driver.stopRiding();
            driver.getPersistentData().remove(TRAFFIC_DRIVER_KEY);
            BlockPos home = vehicle.blockPosition();
            driver.snapTo(vehicle.getX() + 2.0, vehicle.getY(), vehicle.getZ() + 2.0,
                    vehicle.getYRot(), 0.0F);
            driver.setHomeTo(home, 28);
            driver.markPopulationManaged(home);
        }
        VehicleApi.findTraffic(vehicle).ifPresent(
                traffic -> traffic.setTrafficInput(0.0F, 0.0F, true));
        VehicleApi.find(vehicle).ifPresent(RemoteControllableVehicle::clearRemoteInput);
        vehicle.getPersistentData().remove(TRAFFIC_VEHICLE_KEY);
        ROUTES.remove(vehicle.getUUID());
    }

    /** Removes a managed driver together with an ambient vehicle being retired. */
    public static void retire(Entity vehicle) {
        if (vehicle == null) return;
        for (Entity passenger : java.util.List.copyOf(vehicle.getPassengers())) {
            if (isTrafficDriver(passenger)) passenger.discard();
        }
        ROUTES.remove(vehicle.getUUID());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level.getGameTime() % INPUT_INTERVAL_TICKS != 0
                || !NeonCityGenerator.isMegacityWorld(level)) {
            return;
        }
        if (level.getGameTime() % RECONCILE_INTERVAL_TICKS == 0) {
            for (Entity vehicle : level.getAllEntities()) {
                if (vehicle.isAlive()
                        && vehicle.getPersistentData().getBooleanOr(
                                TRAFFIC_VEHICLE_KEY, false)) {
                    ROUTES.putIfAbsent(
                            vehicle.getUUID(), new RouteState(headingFromYaw(vehicle.getYRot())));
                }
            }
        }
        var routes = ROUTES.entrySet().iterator();
        while (routes.hasNext()) {
            Map.Entry<UUID, RouteState> entry = routes.next();
            Entity vehicle = level.getEntity(entry.getKey());
            if (vehicle == null || !vehicle.isAlive()
                    || !vehicle.getPersistentData().getBooleanOr(TRAFFIC_VEHICLE_KEY, false)) {
                routes.remove();
                continue;
            }
            TrafficVehicle traffic = VehicleApi.findTraffic(vehicle).orElse(null);
            Entity driver = trafficDriver(vehicle);
            if (traffic == null || !isManagedPair(vehicle, driver)) {
                vehicle.getPersistentData().remove(TRAFFIC_VEHICLE_KEY);
                routes.remove();
                continue;
            }
            drive(level, vehicle, traffic, entry.getValue());
        }
    }

    private static Entity trafficDriver(Entity vehicle) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (isTrafficDriver(passenger)) return passenger;
        }
        return null;
    }

    private static void drive(
            ServerLevel level, Entity vehicle, TrafficVehicle traffic, RouteState route) {
        boolean blocked = traffic.blocked() || hasObstacle(level, vehicle, route.heading);
        route.blockedTicks = blocked ? route.blockedTicks + INPUT_INTERVAL_TICKS : 0;
        route.decisionTicks -= INPUT_INTERVAL_TICKS;
        if (route.decisionTicks <= 0
                || route.blockedTicks >= BLOCKED_REROUTE_TICKS) {
            route.heading = chooseHeading(vehicle, route.heading, route.blockedTicks);
            route.decisionTicks = ROUTE_DECISION_TICKS;
            route.blockedTicks = 0;
            route.laneProbeTicks = 0;
        }

        route.laneProbeTicks -= INPUT_INTERVAL_TICKS;
        if (route.laneTarget == null || route.laneProbeTicks <= 0) {
            route.laneTarget = laneTarget(vehicle.position(), route.heading);
            route.laneProbeTicks = LANE_PROBE_INTERVAL_TICKS;
        }
        Vec3 target = route.laneTarget;
        double dx = target.x - vehicle.getX();
        double dz = target.z - vehicle.getZ();
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yawError = Mth.wrapDegrees(targetYaw - vehicle.getYRot());
        float steering = Mth.clamp(yawError / 38.0F, -1.0F, 1.0F);
        boolean braking = blocked || Math.abs(yawError) > 78.0F;
        float throttle = braking ? 0.0F : Math.abs(yawError) > 42.0F ? 0.28F : 0.52F;
        traffic.setTrafficInput(throttle, steering, braking);
    }

    private static Direction chooseHeading(Entity vehicle, Direction previous, int blockedTicks) {
        Direction best = previous;
        int bestScore = Integer.MIN_VALUE;
        for (Direction candidate : Direction.Plane.HORIZONTAL) {
            if (candidate == previous.getOpposite() && blockedTicks < BLOCKED_REROUTE_TICKS) {
                continue;
            }
            int score = roadRun(vehicle.blockPosition(), candidate) * 10;
            if (candidate == previous) score += 7;
            if (candidate == previous.getClockWise()) score += 2;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return bestScore > 0 ? best : previous.getOpposite();
    }

    private static int roadRun(BlockPos origin, Direction direction) {
        int score = 0;
        int baseY = NeonCityGenerator.sample(origin.getX(), origin.getZ()).groundY();
        for (int distance = 4; distance <= ROAD_PROBE_DISTANCE; distance += 4) {
            int x = origin.getX() + direction.getStepX() * distance;
            int z = origin.getZ() + direction.getStepZ() * distance;
            NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(x, z);
            if (!RoadsideVehicleSpawns.isTrafficRoad(sample.roadClass())
                    || Math.abs(sample.groundY() - baseY) > 2) {
                break;
            }
            score++;
        }
        return score;
    }

    private static Vec3 laneTarget(Vec3 position, Direction heading) {
        Direction right = heading.getClockWise();
        double baseX = position.x + heading.getStepX() * 12.0;
        double baseZ = position.z + heading.getStepZ() * 12.0;
        int minimum = 0;
        int maximum = 0;
        for (int offset = -1; offset >= -LANE_PROBE_RADIUS; offset--) {
            if (!isTrafficRoad(baseX + right.getStepX() * offset,
                    baseZ + right.getStepZ() * offset)) break;
            minimum = offset;
        }
        for (int offset = 1; offset <= LANE_PROBE_RADIUS; offset++) {
            if (!isTrafficRoad(baseX + right.getStepX() * offset,
                    baseZ + right.getStepZ() * offset)) break;
            maximum = offset;
        }
        double laneOffset = (minimum + maximum) * 0.5
                + Math.max(0.75, (maximum - minimum) * 0.18);
        return new Vec3(
                baseX + right.getStepX() * laneOffset,
                position.y,
                baseZ + right.getStepZ() * laneOffset);
    }

    private static boolean isTrafficRoad(double x, double z) {
        return RoadsideVehicleSpawns.isTrafficRoad(
                NeonCityGenerator.sample(Mth.floor(x), Mth.floor(z)).roadClass());
    }

    private static boolean hasObstacle(
            ServerLevel level, Entity vehicle, Direction heading) {
        Vec3 lookahead = new Vec3(
                heading.getStepX() * OBSTACLE_LOOKAHEAD,
                0.0,
                heading.getStepZ() * OBSTACLE_LOOKAHEAD);
        AABB scan = vehicle.getBoundingBox().expandTowards(lookahead).inflate(1.0, 0.5, 1.0);
        return !level.getEntities(vehicle, scan, candidate -> candidate.isAlive()
                && !vehicle.getPassengers().contains(candidate)
                && (candidate instanceof net.minecraft.world.entity.LivingEntity
                        || VehicleApi.findTraffic(candidate).isPresent())).isEmpty();
    }

    static Direction headingFromYaw(float yaw) {
        int quadrant = Math.floorMod(Mth.floor(yaw / 90.0F + 0.5F), 4);
        return switch (quadrant) {
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            case 3 -> Direction.EAST;
            default -> Direction.SOUTH;
        };
    }
}
