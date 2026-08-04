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
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Supplies bounded city traffic with drivers and road-following input. */
@EventBusSubscriber(modid = Cyberdeck.MODID)
public final class CityTrafficService {
    private static final String TRAFFIC_VEHICLE_KEY = Cyberdeck.MODID + ":traffic_vehicle";
    private static final String TRAFFIC_DRIVER_KEY = Cyberdeck.MODID + ":traffic_driver";
    private static final int INPUT_INTERVAL_TICKS = 2;
    private static final int RECONCILE_INTERVAL_TICKS = 100;
    private static final int STUCK_RECOVERY_TICKS = 80;
    private static final int UNSAFE_ROUTE_RETIRE_TICKS = 12;
    private static final int RETIRE_INVALID_ROUTE_TICKS = 80;
    private static final int RETIRE_RECOVERY_INTERVAL_TICKS = 40;
    private static final int ROUTE_LOOKAHEAD_NODES = 8;
    private static final double HIGHWAY_NODE_SPACING = 12.0;
    private static final int RECENT_NODE_LIMIT = 64;
    private static final int RECENT_HIGHWAY_EDGE_LIMIT = 8;
    private static final double DESTINATION_REACHED_DISTANCE_SQR = 96.0 * 96.0;
    private static final double TARGET_REACHED_DISTANCE_SQR = 12.25;
    private static final double OBSTACLE_LOOKAHEAD = 6.0;
    private static final double RETIRE_PROTECTED_RADIUS_SQR = 64.0 * 64.0;
    private static final Map<UUID, RouteState> ROUTES = new HashMap<>();

    private enum RouteMode {
        LOCAL,
        HIGHWAY
    }

    private CityTrafficService() {
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ROUTES.clear();
        CityTrafficGraph.clearCaches();
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
        private RouteMode mode;
        private MegacityLayout.Edge highwayEdge;
        private MegacityLayout.Edge previousHighwayEdge;
        private double highwayProgress;
        private boolean highwayForward;
        private int blockedTicks;
        private int stuckTicks;
        private int invalidRouteTicks;
        private int unsafeRouteTicks;
        private boolean retirementPending;
        private long nextRecoveryTick;
        private Vec3 lastPosition;

        private RouteState(UUID vehicleId, Vec3 position) {
            this.random = RandomSource.create(
                    vehicleId.getMostSignificantBits() ^ vehicleId.getLeastSignificantBits());
            this.lastPosition = position;
        }
    }

    /** Creates and seats a visible civilian driver for a vehicle already in the level. */
    public static boolean assignDriver(
            ServerLevel level, Entity vehicle, RandomSource random) {
        TrafficVehicle traffic = VehicleApi.findTraffic(vehicle).orElse(null);
        if (traffic == null || !vehicle.getPassengers().isEmpty()
                || traffic.controllingDriver() != null
                || !RoadsideVehicleSpawns.supportsMovingTraffic(NeonCityGenerator.roadAt(
                        vehicle.getBlockX(), vehicle.getBlockZ()))) return false;

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

    public static int plannedNodeCount(Entity vehicle) {
        RouteState route = vehicle == null ? null : ROUTES.get(vehicle.getUUID());
        return route == null ? 0 : route.route.size();
    }

    public static boolean plannedRouteStaysOnHighway(Entity vehicle) {
        RouteState route = vehicle == null ? null : ROUTES.get(vehicle.getUUID());
        return route != null && route.mode == RouteMode.HIGHWAY && !route.route.isEmpty()
                && route.route.stream().allMatch(node ->
                        NeonCityGenerator.isHighwayRoadClass(node.roadClass()));
    }

    public static boolean plannedRouteStaysOnLocalRoad(Entity vehicle) {
        RouteState route = vehicle == null ? null : ROUTES.get(vehicle.getUUID());
        return route != null && route.mode == RouteMode.LOCAL && !route.route.isEmpty()
                && route.route.stream().allMatch(node ->
                        RoadsideVehicleSpawns.isLocalTrafficRoad(node.roadClass()));
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

    static void forgetInMemoryRoute(Entity vehicle) {
        if (vehicle != null) ROUTES.remove(vehicle.getUUID());
    }

    static boolean restorePersistedRoute(ServerLevel level, Entity vehicle) {
        if (vehicle == null || !vehicle.isAlive()
                || !vehicle.getPersistentData().getBooleanOr(TRAFFIC_VEHICLE_KEY, false)
                || !isManagedPair(vehicle, trafficDriver(vehicle))) {
            return false;
        }
        RouteState route = ROUTES.computeIfAbsent(
                vehicle.getUUID(), ignored -> new RouteState(
                        vehicle.getUUID(), vehicle.position()));
        if (route.mode == null && route.route.isEmpty()) {
            route.retirementPending = !initializeRoute(level, vehicle, route);
        }
        return route.mode != null && !route.route.isEmpty() && !route.retirementPending;
    }

    /** Test seam for exercising the real route and TrafficVehicle input path outside megacity. */
    static boolean tickManagedVehicleForTest(ServerLevel level, Entity vehicle) {
        RouteState route = vehicle == null ? null : ROUTES.get(vehicle.getUUID());
        TrafficVehicle traffic = vehicle == null
                ? null : VehicleApi.findTraffic(vehicle).orElse(null);
        return route != null
                && traffic != null
                && isManagedPair(vehicle, trafficDriver(vehicle))
                && drive(level, vehicle, traffic, route);
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
                            vehicle.getUUID(), new RouteState(
                                    vehicle.getUUID(), vehicle.position()));
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
            RouteState route = entry.getValue();
            if (route.mode == null) {
                if (route.route.isEmpty() && initializeRoute(level, vehicle, route)) {
                    route.retirementPending = false;
                } else {
                    traffic.setTrafficInput(0.0F, 0.0F, true);
                    route.retirementPending = true;
                    route.nextRecoveryTick = level.getGameTime()
                            + RETIRE_RECOVERY_INTERVAL_TICKS;
                }
            }
            if (route.retirementPending) {
                traffic.setTrafficInput(0.0F, 0.0F, true);
                if (canRetireOutOfSight(level, vehicle)) {
                    discardManagedVehicle(vehicle);
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
        route.mode = null;
        route.highwayEdge = null;
        route.previousHighwayEdge = null;
        route.recentHighwayEdgeOrder.clear();
        route.recentHighwayEdges.clear();
        route.blockedTicks = 0;
        route.stuckTicks = 0;
        route.invalidRouteTicks = 0;
        route.unsafeRouteTicks = 0;
        route.lastPosition = vehicle.position();
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
        if (VehicleQuickhackService.isBrakeActive(vehicle)) {
            traffic.setTrafficInput(0.0F, 0.0F, true);
            route.stuckTicks = 0;
            route.lastPosition = vehicle.position();
            return true;
        }

        double movedSqr = vehicle.position().distanceToSqr(route.lastPosition);
        route.lastPosition = vehicle.position();
        if (movedSqr < 0.0016 && Math.abs(traffic.speed()) < 0.03F) {
            route.stuckTicks += INPUT_INTERVAL_TICKS;
        } else {
            route.stuckTicks = 0;
        }

        if (route.route.isEmpty() && !initializeRoute(level, vehicle, route)) {
            route.invalidRouteTicks += INPUT_INTERVAL_TICKS;
            traffic.setTrafficInput(0.0F, 0.0F, true);
            return route.invalidRouteTicks < RETIRE_INVALID_ROUTE_TICKS;
        }
        while (!route.route.isEmpty()
                && horizontalDistanceSqr(vehicle.position(), route.route.getFirst().position())
                        <= TARGET_REACHED_DISTANCE_SQR) {
            rememberVisited(route, route.route.removeFirst().key());
        }
        if (route.destination == null
                || horizontalDistanceSqr(vehicle.position(), route.destination)
                        <= DESTINATION_REACHED_DISTANCE_SQR) {
            if (route.mode == RouteMode.HIGHWAY) {
                chooseHighwayDestination(vehicle, route);
            } else {
                chooseLocalDestination(vehicle, route);
            }
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
        boolean envelopeSafe = roadEnvelopeSafe(level, vehicle, target, route.mode);
        boolean physicalClearance = hasPhysicalClearance(level, vehicle, dx, dz);
        boolean unsafeRoute = !envelopeSafe;
        route.unsafeRouteTicks = wallBlocked || unsafeRoute
                ? route.unsafeRouteTicks + INPUT_INTERVAL_TICKS : 0;
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
        boolean gradeClearance = route.mode == RouteMode.HIGHWAY && envelopeSafe;
        boolean blocked = wallBlocked || unsafeRoute
                || (!physicalClearance && !gradeClearance)
                || hasObstacle(level, vehicle, targetYaw);
        route.blockedTicks = blocked ? route.blockedTicks + INPUT_INTERVAL_TICKS : 0;
        if (route.stuckTicks >= STUCK_RECOVERY_TICKS) {
            traffic.setTrafficInput(0.0F, 0.0F, true);
            Cyberdeck.LOGGER.debug(
                    "Retiring stuck traffic {} at ({},{}) after {} ticks",
                    vehicle.getUUID(), vehicle.getBlockX(), vehicle.getBlockZ(),
                    route.stuckTicks);
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
        NeonCityGenerator.RoadClass roadClass = NeonCityGenerator.roadAt(
                vehicle.getBlockX(), vehicle.getBlockZ());
        route.route.clear();
        if (RoadsideVehicleSpawns.isMovingTrafficRoad(roadClass)) {
            route.mode = RouteMode.HIGHWAY;
            if (route.destination == null) chooseHighwayDestination(vehicle, route);
            if (!initializeHighwayRoute(level, vehicle, route)) return false;
            fillHighwayRoute(level, route);
        } else if (RoadsideVehicleSpawns.isLocalTrafficRoad(roadClass)) {
            route.mode = RouteMode.LOCAL;
            if (route.destination == null) chooseLocalDestination(vehicle, route);
            if (!initializeLocalRoute(level, vehicle, route)) return false;
            fillLocalRoute(level, route);
            if (route.route.size() < 2) return false;
        } else {
            route.mode = null;
            return false;
        }
        return !route.route.isEmpty();
    }

    private static void fillRoute(ServerLevel level, RouteState route) {
        if (route.mode == RouteMode.HIGHWAY) {
            fillHighwayRoute(level, route);
        } else if (route.mode == RouteMode.LOCAL) {
            fillLocalRoute(level, route);
        }
    }

    private static boolean initializeLocalRoute(
            ServerLevel level, Entity vehicle, RouteState route) {
        CityTrafficGraph.LaneNode entry = CityTrafficGraph.enter(
                level, vehicle.position(), vehicle.getYRot());
        if (entry == null) return false;
        route.route.addLast(entry);
        return true;
    }

    private static void fillLocalRoute(ServerLevel level, RouteState route) {
        while (route.route.size() < ROUTE_LOOKAHEAD_NODES && !route.route.isEmpty()) {
            CityTrafficGraph.LaneNode tail = route.route.getLast();
            CityTrafficGraph.NodeKey previous = route.route.size() > 1
                    ? secondLast(route.route).key() : route.previous;
            Set<CityTrafficGraph.NodeKey> avoid = new HashSet<>(route.recent);
            for (CityTrafficGraph.LaneNode node : route.route) avoid.add(node.key());
            CityTrafficGraph.LaneNode next = CityTrafficGraph.chooseSuccessor(
                    level, tail, previous, avoid, route.destination, false, route.random);
            if (next == null || avoid.contains(next.key())) return;
            route.route.addLast(next);
        }
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

    private static void chooseHighwayDestination(Entity vehicle, RouteState route) {
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

    private static void chooseLocalDestination(Entity vehicle, RouteState route) {
        double distance = 240.0 + route.random.nextDouble() * 180.0;
        double yaw = Math.toRadians(vehicle.getYRot()
                + (route.random.nextFloat() - 0.5F) * 80.0F);
        route.destination = vehicle.position().add(
                -Math.sin(yaw) * distance, 0.0, Math.cos(yaw) * distance);
        route.recent.clear();
        route.recentOrder.clear();
    }

    private static void rememberVisited(RouteState route, CityTrafficGraph.NodeKey key) {
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
            RouteMode mode) {
        for (int step = 0; step <= 5; step++) {
            double progress = step / 5.0;
            int x = Mth.floor(Mth.lerp(progress, vehicle.getX(), target.position().x));
            int z = Mth.floor(Mth.lerp(progress, vehicle.getZ(), target.position().z));
            NeonCityGenerator.RoadClass roadClass =
                    NeonCityGenerator.sample(x, z).roadClass();
            if (mode == RouteMode.HIGHWAY
                    ? !NeonCityGenerator.isHighwayRoadClass(roadClass)
                    : !nearNavigableRoad(x, z, roadClass)) {
                return false;
            }
        }
        return true;
    }

    private static boolean nearNavigableRoad(
            int x, int z, NeonCityGenerator.RoadClass center) {
        if (CityTrafficGraph.isNavigableRoad(center)) return true;
        for (int offset = 1; offset <= 2; offset++) {
            if (CityTrafficGraph.isNavigableRoad(NeonCityGenerator.roadAt(x + offset, z))
                    || CityTrafficGraph.isNavigableRoad(NeonCityGenerator.roadAt(x - offset, z))
                    || CityTrafficGraph.isNavigableRoad(NeonCityGenerator.roadAt(x, z + offset))
                    || CityTrafficGraph.isNavigableRoad(NeonCityGenerator.roadAt(x, z - offset))) {
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
        AABB scan = vehicle.getBoundingBox().expandTowards(lookahead).inflate(1.0, 0.5, 1.0);
        return !level.getEntities(vehicle, scan, candidate -> candidate.isAlive()
                && !vehicle.getPassengers().contains(candidate)
                && (candidate instanceof net.minecraft.world.entity.LivingEntity
                        || VehicleApi.findTraffic(candidate).isPresent())).isEmpty();
    }

}
