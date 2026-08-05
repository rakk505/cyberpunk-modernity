package com.example.cyberdeck.vehicle;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.npc.CityNpcEntities;
import com.example.cyberdeck.npc.NpcRole;
import com.modernity.vehicle_mod.api.RemoteControllableVehicle;
import com.modernity.vehicle_mod.api.TrafficVehicle;
import com.modernity.vehicle_mod.api.VehicleApi;
import com.modernity.vehicle_mod.entity.FuelPoweredVehicleEntity;
import dev.modernity.neoncity.MegacityLayout;
import dev.modernity.neoncity.NeonCityGenerator;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
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
    /** How often driverless vehicles re-evaluate their automatic day/night headlights. */
    private static final int AUTO_HEADLIGHT_INTERVAL_TICKS = 40;
    private static final int STUCK_REPLAN_TICKS = 100;
    private static final int STUCK_RETIRE_TICKS = 360;
    private static final int UNSAFE_ROUTE_REPLAN_TICKS = 12;
    private static final int UNSAFE_ROUTE_RETIRE_TICKS = 80;
    private static final int MAX_COURSE_RECOVERIES = 3;
    private static final int RETIRE_INVALID_ROUTE_TICKS = 80;
    private static final int RETIRE_RECOVERY_INTERVAL_TICKS = 40;
    private static final int ROUTE_LOOKAHEAD_NODES = 8;
    private static final double HIGHWAY_NODE_SPACING = 12.0;
    private static final int RECENT_NODE_LIMIT = 64;
    private static final int RECENT_HIGHWAY_EDGE_LIMIT = 8;
    private static final double DESTINATION_REACHED_DISTANCE_SQR = 96.0 * 96.0;
    private static final double TARGET_REACHED_DISTANCE_SQR = 12.25;
    private static final double OBSTACLE_LOOKAHEAD = 6.0;
    private static final double JUNCTION_APPROACH_DISTANCE_SQR = 24.0 * 24.0;
    private static final double JUNCTION_CONFLICT_DISTANCE_SQR = 18.0 * 18.0;
    private static final double JUNCTION_CLEAR_DISTANCE_SQR = 28.0 * 28.0;
    private static final int JUNCTION_LEASE_TICKS = 60;
    private static final double RETIRE_PROTECTED_RADIUS_SQR = 64.0 * 64.0;
    private static final Map<UUID, RouteState> ROUTES = new HashMap<>();
    private static final Map<UUID, JunctionReservation> JUNCTION_RESERVATIONS = new HashMap<>();

    private CityTrafficService() {
    }

    private static final class RouteState {
        private final ArrayDeque<CityTrafficGraph.LaneNode> route = new ArrayDeque<>();
        private final ArrayDeque<CityTrafficGraph.NodeKey> recentOrder = new ArrayDeque<>();
        private final Set<CityTrafficGraph.NodeKey> recent = new HashSet<>();
        private final ArrayDeque<MegacityLayout.Edge> recentHighwayEdgeOrder =
                new ArrayDeque<>();
        private final Set<MegacityLayout.Edge> recentHighwayEdges = new HashSet<>();
        private final RandomSource random;
        private CityTrafficGraph.NodeKey previous;
        private Vec3 destination;
        private boolean highwayTrip;
        private MegacityLayout.Edge highwayEdge;
        private MegacityLayout.Edge previousHighwayEdge;
        private double highwayProgress;
        private boolean highwayForward;
        private int blockedTicks;
        private int stuckTicks;
        private int invalidRouteTicks;
        private int unsafeRouteTicks;
        private int courseRecoveries;
        private boolean atlasDeadEnd;
        private boolean retirementPending;
        private long nextRecoveryTick;
        private Vec3 lastPosition;
        private Vec3 recoveryPosition;

        private RouteState(UUID vehicleId, Vec3 position) {
            this.random = RandomSource.create(
                    vehicleId.getMostSignificantBits() ^ vehicleId.getLeastSignificantBits());
            this.lastPosition = position;
            this.recoveryPosition = position;
        }
    }

    private record JunctionApproach(
            CityTrafficGraph.LaneNode junction,
            CityTrafficGraph.LaneNode exit) {
    }

    private record JunctionReservation(Vec3 center, long expiresAt) {
    }

    /** Creates and seats a visible civilian driver for a vehicle already in the level. */
    public static boolean assignDriver(
            ServerLevel level, Entity vehicle, RandomSource random) {
        TrafficVehicle traffic = VehicleApi.findTraffic(vehicle).orElse(null);
        if (traffic == null || !vehicle.getPassengers().isEmpty()
                || traffic.controllingDriver() != null
                || !CityTrafficGraph.isNavigableAt(
                        vehicle.getBlockX(), vehicle.getBlockZ())) return false;

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
        if (vehicle instanceof FuelPoweredVehicleEntity fuelVehicle) {
            fuelVehicle.setFuel(Math.max(
                    fuelVehicle.getFuel(), fuelVehicle.getFuelCapacity() / 2));
        }
        RouteState route = new RouteState(vehicle.getUUID(), vehicle.position());
        if (!initializeRoute(level, vehicle, route)) {
            driver.discard();
            vehicle.getPersistentData().remove(TRAFFIC_VEHICLE_KEY);
            return false;
        }
        float heading = route.route.getFirst().yaw();
        vehicle.setYRot(heading);
        vehicle.yRotO = heading;
        ROUTES.put(vehicle.getUUID(), route);
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

    public static boolean isActiveTraffic(Entity vehicle) {
        RouteState route = vehicle == null ? null : ROUTES.get(vehicle.getUUID());
        return hasTrafficDriver(vehicle) && (route == null || !route.retirementPending);
    }

    public static boolean isHighwayTraffic(Entity vehicle) {
        RouteState route = vehicle == null ? null : ROUTES.get(vehicle.getUUID());
        return route != null && route.highwayTrip;
    }

    public static boolean isAtlasTraffic(Entity vehicle) {
        RouteState route = vehicle == null ? null : ROUTES.get(vehicle.getUUID());
        return route != null && !route.highwayTrip;
    }

    public static int plannedNodeCount(Entity vehicle) {
        RouteState route = vehicle == null ? null : ROUTES.get(vehicle.getUUID());
        return route == null ? 0 : route.route.size();
    }

    public static boolean plannedRouteStaysOnHighway(Entity vehicle) {
        RouteState route = vehicle == null ? null : ROUTES.get(vehicle.getUUID());
        return route != null && route.highwayTrip && !route.route.isEmpty()
                && route.route.stream().allMatch(node ->
                        node.network() == CityTrafficGraph.Network.HIGHWAY);
    }

    public static boolean plannedRouteUsesAtlas(Entity vehicle) {
        RouteState route = vehicle == null ? null : ROUTES.get(vehicle.getUUID());
        return route != null && !route.highwayTrip && !route.route.isEmpty()
                && route.route.stream().allMatch(node ->
                        node.network() == CityTrafficGraph.Network.ATLAS);
    }

    public static boolean plannedRouteIncludesHighwayJunction(Entity vehicle) {
        RouteState route = vehicle == null ? null : ROUTES.get(vehicle.getUUID());
        return route != null && route.route.stream().anyMatch(node ->
                NeonCityGenerator.layout().nodes().stream().anyMatch(junction -> {
                    double dx = node.position().x - junction.x();
                    double dz = node.position().z - junction.z();
                    return dx * dx + dz * dz <= 4.0;
                }));
    }

    public static int plannedUniqueNodeCount(Entity vehicle) {
        RouteState route = vehicle == null ? null : ROUTES.get(vehicle.getUUID());
        return route == null ? 0 : (int) route.route.stream()
                .map(CityTrafficGraph.LaneNode::key)
                .distinct()
                .count();
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
        JUNCTION_RESERVATIONS.remove(vehicle.getUUID());
    }

    /** Removes a managed driver together with an ambient vehicle being retired. */
    public static void retire(Entity vehicle) {
        if (vehicle == null) return;
        for (Entity passenger : java.util.List.copyOf(vehicle.getPassengers())) {
            if (isTrafficDriver(passenger)) passenger.discard();
        }
        ROUTES.remove(vehicle.getUUID());
        JUNCTION_RESERVATIONS.remove(vehicle.getUUID());
    }

    /**
     * Turns headlights on at night and off during the day for driverless vehicles. A vehicle a
     * player is actively driving keeps its manual headlight toggle so we never fight the player.
     */
    private static void updateAutomaticHeadlights(ServerLevel level) {
        // Headlights come on whenever it is dark outside — night, and also storms/eclipses.
        boolean night = !level.isBrightOutside();
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof FuelPoweredVehicleEntity vehicle) || !vehicle.isAlive()) {
                continue;
            }
            if (vehicle.getControllingPassenger()
                    instanceof net.minecraft.world.entity.player.Player) {
                continue;
            }
            if (vehicle.areHeadlightsOn() != night) {
                vehicle.setHeadlightsOn(night);
            }
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level.getGameTime() % INPUT_INTERVAL_TICKS != 0
                || !NeonCityGenerator.isMegacityWorld(level)) {
            return;
        }
        if (level.getGameTime() % AUTO_HEADLIGHT_INTERVAL_TICKS == 0) {
            updateAutomaticHeadlights(level);
        }
        if (level.getGameTime() % RECONCILE_INTERVAL_TICKS == 0) {
            for (Entity vehicle : level.getAllEntities()) {
                if (vehicle.isAlive()
                        && vehicle.getPersistentData().getBooleanOr(
                                TRAFFIC_VEHICLE_KEY, false)) {
                    ROUTES.putIfAbsent(
                            vehicle.getUUID(), new RouteState(
                                    vehicle.getUUID(), vehicle.position()));
                }
            }
        }
        JUNCTION_RESERVATIONS.entrySet().removeIf(entry ->
                entry.getValue().expiresAt() < level.getGameTime()
                        || !ROUTES.containsKey(entry.getKey()));
        var routes = ROUTES.entrySet().iterator();
        while (routes.hasNext()) {
            Map.Entry<UUID, RouteState> entry = routes.next();
            Entity vehicle = level.getEntity(entry.getKey());
            if (vehicle == null || !vehicle.isAlive()
                    || !vehicle.getPersistentData().getBooleanOr(TRAFFIC_VEHICLE_KEY, false)) {
                JUNCTION_RESERVATIONS.remove(entry.getKey());
                routes.remove();
                continue;
            }
            TrafficVehicle traffic = VehicleApi.findTraffic(vehicle).orElse(null);
            Entity driver = trafficDriver(vehicle);
            if (traffic == null || !isManagedPair(vehicle, driver)) {
                vehicle.getPersistentData().remove(TRAFFIC_VEHICLE_KEY);
                JUNCTION_RESERVATIONS.remove(entry.getKey());
                routes.remove();
                continue;
            }
            RouteState route = entry.getValue();
            if (route.retirementPending) {
                traffic.setTrafficInput(0.0F, 0.0F, true);
                if (canRetireOutOfSight(level, vehicle)) {
                    discardManagedVehicle(vehicle);
                    JUNCTION_RESERVATIONS.remove(entry.getKey());
                    routes.remove();
                } else if (level.getGameTime() >= route.nextRecoveryTick) {
                    route.nextRecoveryTick = level.getGameTime()
                            + RETIRE_RECOVERY_INTERVAL_TICKS;
                    if (recoverRoute(level, vehicle, route)) {
                        route.retirementPending = false;
                    }
                }
                continue;
            }
            if (!drive(level, vehicle, traffic, route)) {
                route.retirementPending = true;
                route.nextRecoveryTick = level.getGameTime()
                        + RETIRE_RECOVERY_INTERVAL_TICKS;
            }
        }
    }

    private static void discardManagedVehicle(Entity vehicle) {
        for (Entity passenger : java.util.List.copyOf(vehicle.getPassengers())) {
            if (isTrafficDriver(passenger)) passenger.discard();
        }
        vehicle.discard();
    }

    private static boolean recoverRoute(
            ServerLevel level, Entity vehicle, RouteState route) {
        route.route.clear();
        route.recent.clear();
        route.recentOrder.clear();
        route.previous = null;
        route.destination = null;
        route.highwayEdge = null;
        route.previousHighwayEdge = null;
        route.recentHighwayEdgeOrder.clear();
        route.recentHighwayEdges.clear();
        route.blockedTicks = 0;
        route.stuckTicks = 0;
        route.invalidRouteTicks = 0;
        route.unsafeRouteTicks = 0;
        route.courseRecoveries = 0;
        route.atlasDeadEnd = false;
        route.lastPosition = vehicle.position();
        route.recoveryPosition = vehicle.position();
        JUNCTION_RESERVATIONS.remove(vehicle.getUUID());
        return initializeRoute(level, vehicle, route);
    }

    /** Ambient removals are allowed only when they cannot pop out in a player's view. */
    public static boolean canRetireOutOfSight(ServerLevel level, Entity vehicle) {
        Vec3 center = vehicle.getBoundingBox().getCenter();
        for (var player : level.players()) {
            if (!player.isAlive()) continue;
            Vec3 toVehicle = center.subtract(player.getEyePosition());
            double distanceSqr = toVehicle.lengthSqr();
            if (distanceSqr <= RETIRE_PROTECTED_RADIUS_SQR) return false;
            if (toVehicle.normalize().dot(player.getLookAngle()) > 0.0) {
                return false;
            }
        }
        return true;
    }

    private static Entity trafficDriver(Entity vehicle) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (isTrafficDriver(passenger)) return passenger;
        }
        return null;
    }

    private static boolean drive(
            ServerLevel level, Entity vehicle, TrafficVehicle traffic, RouteState route) {
        if (vehicle instanceof FuelPoweredVehicleEntity fuelVehicle
                && fuelVehicle.getFuel() < Math.max(1, fuelVehicle.getFuelCapacity() / 4)) {
            fuelVehicle.setFuel(Math.max(1, fuelVehicle.getFuelCapacity() / 2));
        }

        double movedSqr = vehicle.position().distanceToSqr(route.lastPosition);
        route.lastPosition = vehicle.position();
        if (movedSqr < 0.0016 && Math.abs(traffic.speed()) < 0.03F) {
            route.stuckTicks += INPUT_INTERVAL_TICKS;
        } else {
            route.stuckTicks = 0;
        }
        if (horizontalDistanceSqr(vehicle.position(), route.recoveryPosition)
                >= 20.0 * 20.0) {
            route.courseRecoveries = 0;
            route.recoveryPosition = vehicle.position();
        }

        if (route.route.isEmpty() && route.atlasDeadEnd
                && !tryAtlasTurnaround(level, vehicle, route)) {
            traffic.setTrafficInput(0.0F, 0.0F, true);
            return route.stuckTicks < STUCK_RETIRE_TICKS;
        }
        if (route.route.isEmpty() && !initializeRoute(level, vehicle, route)) {
            route.invalidRouteTicks += INPUT_INTERVAL_TICKS;
            traffic.setTrafficInput(0.0F, 0.0F, true);
            return route.invalidRouteTicks < RETIRE_INVALID_ROUTE_TICKS;
        }
        while (!route.route.isEmpty()
                && horizontalDistanceSqr(vehicle.position(), route.route.getFirst().position())
                        <= TARGET_REACHED_DISTANCE_SQR) {
            rememberVisited(route, route.route.removeFirst());
        }
        if (route.route.isEmpty() && route.atlasDeadEnd) {
            if (!tryAtlasTurnaround(level, vehicle, route)) {
                traffic.setTrafficInput(0.0F, 0.0F, true);
                return route.stuckTicks < STUCK_RETIRE_TICKS;
            }
        }
        if (route.destination == null
                || horizontalDistanceSqr(vehicle.position(), route.destination)
                        <= DESTINATION_REACHED_DISTANCE_SQR) {
            chooseDestination(vehicle, route);
            route.route.clear();
            if (!initializeRoute(level, vehicle, route)) {
                route.invalidRouteTicks += INPUT_INTERVAL_TICKS;
                traffic.setTrafficInput(0.0F, 0.0F, true);
                return route.invalidRouteTicks < RETIRE_INVALID_ROUTE_TICKS;
            }
        }
        fillRoute(level, route);
        if (route.route.isEmpty()) {
            route.invalidRouteTicks += INPUT_INTERVAL_TICKS;
            traffic.setTrafficInput(0.0F, 0.0F, true);
            return route.invalidRouteTicks < RETIRE_INVALID_ROUTE_TICKS;
        }
        route.invalidRouteTicks = 0;

        CityTrafficGraph.LaneNode target = route.route.getFirst();
        double dx = target.position().x - vehicle.getX();
        double dz = target.position().z - vehicle.getZ();
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        boolean wallBlocked = traffic.blocked();
        boolean envelopeSafe = roadEnvelopeSafe(level, vehicle, target, route.highwayTrip);
        boolean physicalClearance = hasPhysicalClearance(level, vehicle, dx, dz);
        boolean unsafeRoute = !envelopeSafe;
        route.unsafeRouteTicks = unsafeRoute
                ? route.unsafeRouteTicks + INPUT_INTERVAL_TICKS : 0;
        if (route.unsafeRouteTicks >= UNSAFE_ROUTE_REPLAN_TICKS
                && tryChangeCourse(level, vehicle, route)) {
            traffic.setTrafficInput(0.0F, 0.0F, true);
            return true;
        }
        if (route.unsafeRouteTicks >= UNSAFE_ROUTE_RETIRE_TICKS) {
            traffic.setTrafficInput(0.0F, 0.0F, true);
            Cyberdeck.LOGGER.debug(
                    "Retiring traffic {} at ({},{}) toward ({},{}) after unsafe route: "
                            + "wall={}, envelope={}, clearance={}, current_road={}, target_road={}",
                    vehicle.getUUID(), vehicle.getBlockX(), vehicle.getBlockZ(),
                    Mth.floor(target.position().x), Mth.floor(target.position().z),
                    wallBlocked, envelopeSafe, physicalClearance,
                    NeonCityGenerator.sample(vehicle.getBlockX(), vehicle.getBlockZ()).roadClass(),
                    target.roadClass());
            return false;
        }
        boolean gradeClearance = route.highwayTrip && envelopeSafe;
        boolean junctionBlocked = !route.highwayTrip
                && junctionAdmissionBlocked(level, vehicle, route);
        boolean blocked = wallBlocked || unsafeRoute
                || (!physicalClearance && !gradeClearance)
                || junctionBlocked
                || hasObstacle(level, vehicle, targetYaw);
        route.blockedTicks = blocked ? route.blockedTicks + INPUT_INTERVAL_TICKS : 0;
        if (route.stuckTicks >= STUCK_REPLAN_TICKS
                && tryChangeCourse(level, vehicle, route)) {
            traffic.setTrafficInput(0.0F, 0.0F, true);
            return true;
        }
        if (route.stuckTicks >= STUCK_RETIRE_TICKS) {
            traffic.setTrafficInput(0.0F, 0.0F, true);
            Cyberdeck.LOGGER.debug(
                    "Retiring traffic {} at ({},{}) after {} stopped ticks and {} recoveries",
                    vehicle.getUUID(), vehicle.getBlockX(), vehicle.getBlockZ(),
                    route.stuckTicks, route.courseRecoveries);
            return false;
        }
        float yawError = Mth.wrapDegrees(targetYaw - vehicle.getYRot());
        // The vehicle API follows player strafe input: positive steering decreases yaw.
        float steering = Mth.clamp(-yawError / 38.0F, -1.0F, 1.0F);
        boolean braking = blocked;
        float upcomingTurn = upcomingTurn(route);
        float throttle = braking ? 0.0F
                : Math.abs(yawError) > 55.0F || upcomingTurn > 55.0F ? 0.20F
                : Math.abs(yawError) > 30.0F || upcomingTurn > 30.0F ? 0.34F
                : target.cruisingThrottle();
        traffic.setTrafficInput(throttle, steering, braking);
        return true;
    }

    private static boolean initializeRoute(
            ServerLevel level, Entity vehicle, RouteState route) {
        int x = vehicle.getBlockX();
        int z = vehicle.getBlockZ();
        boolean highway = RoadsideVehicleSpawns.isMovingTrafficRoad(
                NeonCityGenerator.roadAt(x, z));
        boolean atlas = CityTrafficGraph.isAtlasTrafficAt(x, z);
        if (!highway && !atlas) return false;
        route.highwayTrip = highway;
        if (route.destination == null) chooseDestination(vehicle, route);
        route.route.clear();
        if (highway) {
            if (!initializeHighwayRoute(level, vehicle, route)) return false;
            fillHighwayRoute(level, route);
        } else {
            route.highwayEdge = null;
            route.previousHighwayEdge = null;
            CityTrafficGraph.LaneNode entry = CityTrafficGraph.enter(
                    level, vehicle.position(), vehicle.getYRot());
            if (entry == null || entry.network() != CityTrafficGraph.Network.ATLAS) return false;
            route.route.addLast(entry);
            fillAtlasRoute(level, route);
        }
        return !route.route.isEmpty();
    }

    private static void fillRoute(ServerLevel level, RouteState route) {
        if (route.highwayTrip) {
            fillHighwayRoute(level, route);
        } else {
            fillAtlasRoute(level, route);
        }
    }

    private static void fillAtlasRoute(ServerLevel level, RouteState route) {
        route.atlasDeadEnd = false;
        while (route.route.size() < ROUTE_LOOKAHEAD_NODES && !route.route.isEmpty()) {
            CityTrafficGraph.LaneNode tail = route.route.peekLast();
            CityTrafficGraph.NodeKey previous = route.route.size() > 1
                    ? secondLast(route.route).key() : route.previous;
            Set<CityTrafficGraph.NodeKey> occupied = new HashSet<>(route.recent);
            route.route.forEach(node -> occupied.add(node.key()));
            CityTrafficGraph.LaneNode next = CityTrafficGraph.chooseSuccessor(
                    level, tail, previous, occupied, route.destination, false, route.random);
            if (next == null || occupied.contains(next.key())
                    || next.network() != CityTrafficGraph.Network.ATLAS) {
                route.atlasDeadEnd = true;
                return;
            }
            route.route.addLast(next);
        }
    }

    private static boolean tryAtlasTurnaround(
            ServerLevel level, Entity vehicle, RouteState route) {
        if (route.highwayTrip) return false;
        float reverseYaw = Mth.wrapDegrees(vehicle.getYRot() + 180.0F);
        double radians = Math.toRadians(reverseYaw);
        double reverseX = -Math.sin(radians) * 3.0;
        double reverseZ = Math.cos(radians) * 3.0;
        if (hasObstacle(level, vehicle, reverseYaw)
                || !hasPhysicalClearance(level, vehicle, reverseX, reverseZ)) {
            return false;
        }
        CityTrafficGraph.LaneNode entry = CityTrafficGraph.enter(
                level, vehicle.position(), reverseYaw);
        if (entry == null || entry.network() != CityTrafficGraph.Network.ATLAS) return false;

        clearCoursePlan(route);
        chooseDestination(vehicle, route);
        route.highwayTrip = false;
        route.route.addLast(entry);
        fillAtlasRoute(level, route);
        route.courseRecoveries = Math.max(1, route.courseRecoveries);
        route.recoveryPosition = vehicle.position();
        route.stuckTicks = 0;
        route.blockedTicks = 0;
        route.invalidRouteTicks = 0;
        route.unsafeRouteTicks = 0;
        return !route.route.isEmpty();
    }

    private static boolean tryChangeCourse(
            ServerLevel level, Entity vehicle, RouteState route) {
        if (route.courseRecoveries >= MAX_COURSE_RECOVERIES) return false;
        int attempt = ++route.courseRecoveries;

        if (!route.highwayTrip && attempt >= 2) {
            boolean turnedAround = tryAtlasTurnaround(level, vehicle, route);
            if (turnedAround) return true;
        }

        clearCoursePlan(route);
        chooseDestination(vehicle, route);
        boolean rebuilt = initializeRoute(level, vehicle, route);
        if (rebuilt) {
            route.stuckTicks = 0;
            route.blockedTicks = 0;
            route.invalidRouteTicks = 0;
            route.unsafeRouteTicks = 0;
            route.recoveryPosition = vehicle.position();
        }
        return rebuilt;
    }

    private static void clearCoursePlan(RouteState route) {
        route.route.clear();
        route.recent.clear();
        route.recentOrder.clear();
        route.previous = null;
        route.destination = null;
        route.atlasDeadEnd = false;
        route.highwayEdge = null;
        route.previousHighwayEdge = null;
        route.recentHighwayEdgeOrder.clear();
        route.recentHighwayEdges.clear();
    }

    private static boolean junctionAdmissionBlocked(
            ServerLevel level, Entity vehicle, RouteState route) {
        JunctionReservation owned = JUNCTION_RESERVATIONS.get(vehicle.getUUID());
        if (owned != null) {
            if (horizontalDistanceSqr(vehicle.position(), owned.center())
                    > JUNCTION_CLEAR_DISTANCE_SQR) {
                JUNCTION_RESERVATIONS.remove(vehicle.getUUID());
                owned = null;
            } else {
                JUNCTION_RESERVATIONS.put(vehicle.getUUID(), new JunctionReservation(
                        owned.center(), level.getGameTime() + JUNCTION_LEASE_TICKS));
            }
        }

        JunctionApproach approach = nextJunction(level, route);
        if (approach == null || horizontalDistanceSqr(
                vehicle.position(), approach.junction().position())
                > JUNCTION_APPROACH_DISTANCE_SQR) {
            return false;
        }
        if (owned != null && horizontalDistanceSqr(
                vehicle.position(), owned.center()) <= 8.0 * 8.0) {
            return false;
        }
        for (Map.Entry<UUID, JunctionReservation> entry : JUNCTION_RESERVATIONS.entrySet()) {
            if (entry.getKey().equals(vehicle.getUUID())
                    || entry.getValue().expiresAt() < level.getGameTime()) {
                continue;
            }
            if (junctionsConflict(
                    approach.junction().position(), entry.getValue().center())) {
                return true;
            }
        }
        if (exitLaneOccupied(level, vehicle, approach.exit())) return true;
        JUNCTION_RESERVATIONS.put(vehicle.getUUID(), new JunctionReservation(
                approach.junction().position(), level.getGameTime() + JUNCTION_LEASE_TICKS));
        return false;
    }

    private static JunctionApproach nextJunction(
            ServerLevel level, RouteState route) {
        var iterator = route.route.iterator();
        while (iterator.hasNext()) {
            CityTrafficGraph.LaneNode node = iterator.next();
            if (!CityTrafficGraph.isJunction(level, node) || !iterator.hasNext()) continue;
            return new JunctionApproach(node, iterator.next());
        }
        return null;
    }

    private static boolean exitLaneOccupied(
            ServerLevel level, Entity vehicle, CityTrafficGraph.LaneNode exit) {
        Vec3 point = exit.position();
        AABB scan = new AABB(
                point.x - 6.0, point.y - 2.0, point.z - 6.0,
                point.x + 6.0, point.y + 2.0, point.z + 6.0);
        double laneHalfWidth = Math.min(
                vehicle.getBoundingBox().getXsize(), vehicle.getBoundingBox().getZsize()) * 0.5;
        return !level.getEntities(vehicle, scan, candidate -> candidate.isAlive()
                && !vehicle.getPassengers().contains(candidate)
                && VehicleApi.findTraffic(candidate).isPresent()
                && occupiesLanePoint(
                        candidate.getBoundingBox(), point, exit.yaw(), laneHalfWidth)).isEmpty();
    }

    public static boolean junctionsConflict(Vec3 first, Vec3 second) {
        return horizontalDistanceSqr(first, second) <= JUNCTION_CONFLICT_DISTANCE_SQR;
    }

    public static boolean occupiesLanePoint(
            AABB candidate, Vec3 point, float yaw, double laneHalfWidth) {
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double rightX = Math.cos(radians);
        double rightZ = Math.sin(radians);
        Vec3 center = candidate.getCenter();
        double dx = center.x - point.x;
        double dz = center.z - point.z;
        double longitudinal = Math.abs(dx * forwardX + dz * forwardZ);
        double lateral = Math.abs(dx * rightX + dz * rightZ);
        double candidateHalfWidth = Math.min(candidate.getXsize(), candidate.getZsize()) * 0.5;
        return longitudinal <= 4.5 + candidateHalfWidth
                && lateral <= laneHalfWidth + candidateHalfWidth + 0.35;
    }

    private static boolean initializeHighwayRoute(
            ServerLevel level, Entity vehicle, RouteState route) {
        MegacityLayout.ConnectionProjection projection = NeonCityGenerator.layout()
                .nearestConnection(vehicle.getX(), vehicle.getZ()).orElse(null);
        if (projection == null || projection.distance() > 18.0) return false;
        route.highwayEdge = projection.edge();
        route.highwayProgress = projection.progress();
        double tangentLength = Math.max(1.0,
                Math.hypot(projection.tangentX(), projection.tangentZ()));
        double tangentX = projection.tangentX() / tangentLength;
        double tangentZ = projection.tangentZ() / tangentLength;
        double yawRadians = Math.toRadians(vehicle.getYRot());
        double vehicleX = -Math.sin(yawRadians);
        double vehicleZ = Math.cos(yawRadians);
        route.highwayForward = vehicleX * tangentX + vehicleZ * tangentZ >= 0.0;
        route.previousHighwayEdge = null;
        rememberHighwayEdge(route, route.highwayEdge);
        return true;
    }

    private static void fillHighwayRoute(ServerLevel level, RouteState route) {
        while (route.route.size() < ROUTE_LOOKAHEAD_NODES && route.highwayEdge != null) {
            MegacityLayout.CurvePoint current = MegacityLayout.curvePoint(
                    route.highwayEdge, route.highwayProgress);
            double derivative = Math.max(1.0,
                    Math.hypot(current.tangentX(), current.tangentZ()));
            double delta = HIGHWAY_NODE_SPACING / derivative;
            double nextProgress = route.highwayProgress
                    + (route.highwayForward ? delta : -delta);
            if (nextProgress > 0.995 || nextProgress < 0.005) {
                double endpointProgress = route.highwayForward ? 1.0 : 0.0;
                CityTrafficGraph.LaneNode junction = CityTrafficGraph.highwayNode(
                        level, route.highwayEdge, endpointProgress, route.highwayForward);
                if (junction == null) return;
                route.highwayProgress = endpointProgress;
                CityTrafficGraph.LaneNode tail = route.route.peekLast();
                if (tail == null || !tail.key().equals(junction.key())) {
                    route.route.addLast(junction);
                }
                if (!advanceHighwayEdge(route)) return;
                continue;
            }
            route.highwayProgress = nextProgress;
            CityTrafficGraph.LaneNode node = CityTrafficGraph.highwayNode(
                    level, route.highwayEdge, nextProgress, route.highwayForward);
            if (node == null) return;
            route.route.addLast(node);
        }
    }

    private static boolean advanceHighwayEdge(RouteState route) {
        MegacityLayout.Node junction = route.highwayForward
                ? route.highwayEdge.second() : route.highwayEdge.first();
        var candidates = NeonCityGenerator.layout().groundEdges().stream()
                .filter(edge -> edge != route.highwayEdge
                        && edge != route.previousHighwayEdge
                        && (edge.first().equals(junction) || edge.second().equals(junction)))
                .toList();
        if (candidates.isEmpty()) {
            // A terminal district is still a paved hub. Merge to its center and return on
            // the opposite lane instead of inventing an off-road continuation.
            route.previousHighwayEdge = null;
            route.highwayForward = !route.highwayForward;
            route.highwayProgress = route.highwayForward ? 0.0 : 1.0;
            return true;
        }
        var freshCandidates = candidates.stream()
                .filter(edge -> !route.recentHighwayEdges.contains(edge))
                .toList();
        var selectionPool = freshCandidates.isEmpty() ? candidates : freshCandidates;
        MegacityLayout.Edge selected = selectionPool.stream()
                .min(java.util.Comparator.comparingDouble(edge -> {
                    MegacityLayout.Node far = edge.first().equals(junction)
                            ? edge.second() : edge.first();
                    double dx = far.x() - route.destination.x;
                    double dz = far.z() - route.destination.z;
                    return dx * dx + dz * dz;
                }))
                .orElse(selectionPool.getFirst());
        route.previousHighwayEdge = route.highwayEdge;
        route.highwayEdge = selected;
        route.highwayForward = selected.first().equals(junction);
        route.highwayProgress = route.highwayForward ? 0.0 : 1.0;
        rememberHighwayEdge(route, selected);
        return true;
    }

    private static void rememberHighwayEdge(
            RouteState route, MegacityLayout.Edge edge) {
        if (route.recentHighwayEdges.add(edge)) {
            route.recentHighwayEdgeOrder.addLast(edge);
        }
        while (route.recentHighwayEdgeOrder.size() > RECENT_HIGHWAY_EDGE_LIMIT) {
            route.recentHighwayEdges.remove(route.recentHighwayEdgeOrder.removeFirst());
        }
    }

    private static void chooseDestination(Entity vehicle, RouteState route) {
        var nodes = NeonCityGenerator.layout().nodes();
        var candidates = nodes.stream()
                .filter(node -> {
                    double dx = node.x() - vehicle.getX();
                    double dz = node.z() - vehicle.getZ();
                    double distanceSqr = dx * dx + dz * dz;
                    return distanceSqr >= 360.0 * 360.0
                            && distanceSqr <= 1800.0 * 1800.0;
                })
                .toList();
        var target = candidates.isEmpty()
                ? nodes.get(route.random.nextInt(nodes.size()))
                : candidates.get(route.random.nextInt(candidates.size()));
        route.destination = new Vec3(target.x(), vehicle.getY(), target.z());
        route.recent.clear();
        route.recentOrder.clear();
    }

    private static void rememberVisited(RouteState route, CityTrafficGraph.LaneNode node) {
        CityTrafficGraph.NodeKey key = node.key();
        route.previous = key;
        if (route.recent.add(key)) route.recentOrder.addLast(key);
        while (route.recentOrder.size() > RECENT_NODE_LIMIT) {
            route.recent.remove(route.recentOrder.removeFirst());
        }
    }

    private static CityTrafficGraph.LaneNode secondLast(
            ArrayDeque<CityTrafficGraph.LaneNode> route) {
        var iterator = route.descendingIterator();
        iterator.next();
        return iterator.next();
    }

    private static float upcomingTurn(RouteState route) {
        if (route.route.size() < 2) return 0.0F;
        var iterator = route.route.iterator();
        float first = iterator.next().yaw();
        float second = iterator.next().yaw();
        return Math.abs(Mth.wrapDegrees(second - first));
    }

    private static double horizontalDistanceSqr(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return dx * dx + dz * dz;
    }

    private static boolean roadEnvelopeSafe(
            ServerLevel level,
            Entity vehicle,
            CityTrafficGraph.LaneNode target,
            boolean highwayTrip) {
        for (int step = 0; step <= 5; step++) {
            double progress = step / 5.0;
            int x = Mth.floor(Mth.lerp(progress, vehicle.getX(), target.position().x));
            int z = Mth.floor(Mth.lerp(progress, vehicle.getZ(), target.position().z));
            NeonCityGenerator.RoadClass roadClass = NeonCityGenerator.roadAt(x, z);
            if (highwayTrip
                    ? !NeonCityGenerator.isHighwayRoadClass(roadClass)
                    : !nearNavigableRoad(x, z)) {
                return false;
            }
        }
        return true;
    }

    private static boolean nearNavigableRoad(int x, int z) {
        if (CityTrafficGraph.isAtlasTrafficAt(x, z)) return true;
        for (int offset = 1; offset <= 2; offset++) {
            if (CityTrafficGraph.isAtlasTrafficAt(x + offset, z)
                    || CityTrafficGraph.isAtlasTrafficAt(x - offset, z)
                    || CityTrafficGraph.isAtlasTrafficAt(x, z + offset)
                    || CityTrafficGraph.isAtlasTrafficAt(x, z - offset)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPhysicalClearance(
            ServerLevel level, Entity vehicle, double dx, double dz) {
        double length = Math.max(0.001, Math.hypot(dx, dz));
        double distance = Math.min(3.0, length);
        AABB ahead = vehicle.getBoundingBox().move(
                dx / length * distance, 0.25, dz / length * distance);
        return level.noCollision(vehicle, ahead);
    }

    private static boolean hasObstacle(
            ServerLevel level, Entity vehicle, float yaw) {
        double radians = Math.toRadians(yaw);
        Vec3 lookahead = new Vec3(
                -Math.sin(radians) * OBSTACLE_LOOKAHEAD,
                0.0,
                Math.cos(radians) * OBSTACLE_LOOKAHEAD);
        // The broad AABB is only a cheap candidate query. The oriented lane test below
        // prevents shoulder parking from blocking diagonal and curved traffic lanes.
        AABB scan = vehicle.getBoundingBox().expandTowards(lookahead).inflate(2.0, 0.5, 2.0);
        return !level.getEntities(vehicle, scan, candidate -> candidate.isAlive()
                && !vehicle.getPassengers().contains(candidate)
                && (candidate instanceof net.minecraft.world.entity.LivingEntity
                        || VehicleApi.findTraffic(candidate).isPresent())
                && occupiesForwardLane(
                        vehicle.getBoundingBox(), candidate.getBoundingBox(), yaw)).isEmpty();
    }

    public static boolean occupiesForwardLane(AABB vehicle, AABB candidate, float yaw) {
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double rightX = Math.cos(radians);
        double rightZ = Math.sin(radians);
        Vec3 origin = vehicle.getCenter();
        Vec3 obstacle = candidate.getCenter();
        double dx = obstacle.x - origin.x;
        double dz = obstacle.z - origin.z;
        double longitudinal = dx * forwardX + dz * forwardZ;
        double lateral = Math.abs(dx * rightX + dz * rightZ);
        double vehicleHalfWidth = Math.min(vehicle.getXsize(), vehicle.getZsize()) * 0.5;
        double candidateHalfWidth = Math.min(candidate.getXsize(), candidate.getZsize()) * 0.5;
        double overlapMargin = 0.35;
        return longitudinal >= -(candidateHalfWidth + overlapMargin)
                && longitudinal <= OBSTACLE_LOOKAHEAD + candidateHalfWidth
                && lateral <= vehicleHalfWidth + candidateHalfWidth + overlapMargin;
    }

}
