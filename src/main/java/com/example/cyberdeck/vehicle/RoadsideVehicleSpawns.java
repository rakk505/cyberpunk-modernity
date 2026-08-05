package com.example.cyberdeck.vehicle;

import com.example.cyberdeck.Cyberdeck;
import com.modernity.vehicle_mod.entity.FuelPoweredVehicleEntity;
import com.modernity.vehicle_mod.vehicle_mod;
import dev.modernity.neoncity.MegacityLayout;
import dev.modernity.neoncity.NeonCityGenerator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Maintains bounded moving traffic plus parked cars and motorbikes on megacity roads. */
@EventBusSubscriber(modid = Cyberdeck.MODID)
public final class RoadsideVehicleSpawns {
    static final int SPAWN_INTERVAL_TICKS = 30;
    static final int TARGET_HIGHWAY_TRAFFIC_NEARBY = 12;
    static final int TARGET_ATLAS_TRAFFIC_NEARBY = 5;
    static final int TARGET_PARKED_NEARBY = 2;
    static final int TARGET_TOTAL_NEARBY = 19;
    static final int SPAWN_BATCH = 6;
    static final int MAX_LOADED_VEHICLES = 56;
    static final int MOTORBIKE_PERCENT = 35;
    static final int MIN_FUEL_PERCENT = 5;
    static final int MAX_FUEL_PERCENT = 95;

    private static final int POPULATION_CELL_SIZE = 128;
    private static final int MAX_PLACEMENT_ATTEMPTS = 48;
    private static final int RETIRE_AFTER_TICKS = 600;
    private static final int TRAFFIC_RETIRE_AFTER_TICKS = 200;
    private static final int MIN_SPAWN_RADIUS = 28;
    private static final int MAX_SPAWN_RADIUS = 76;
    private static final double NEARBY_RADIUS = 88.0;
    private static final double HIGHWAY_NEARBY_RADIUS = 144.0;
    private static final double HIGHWAY_ACTIVATION_RADIUS = 80.0;
    private static final double RETIRE_DISTANCE = 144.0;
    private static final double MIN_SEPARATION = 11.0;
    private static final double ATLAS_ROAD_SEARCH_RADIUS = 22.0;
    private static final double MIN_PARKING_ROAD_WIDTH = 7.0;
    private static final double PARKING_SHOULDER_GAP = 0.75;
    private static final double MIN_DRIVING_LEAD = 48.0;
    private static final double MAX_DRIVING_LEAD = 144.0;
    private static final double DRIVING_LEAD_PER_BLOCK_PER_TICK = 96.0;
    private static final String MANAGED_KEY = Cyberdeck.MODID + ":roadside_vehicle_managed";

    private RoadsideVehicleSpawns() {
    }

    private record ParkingSite(BlockPos position, float yaw) {
    }

    private record TrafficFocus(Vec3 position, Vec3 forward, double lead, boolean driving) {
    }

    private enum SpawnKind {
        HIGHWAY_TRAFFIC,
        ATLAS_TRAFFIC,
        PARKED
    }

    @SubscribeEvent
    public static void onVehicleClaimed(EntityMountEvent event) {
        if (event.isMounting()
                && event.getEntityBeingMounted() instanceof FuelPoweredVehicleEntity vehicle) {
            Entity rider = event.getEntityMounting();
            if (CityTrafficService.isManagedPair(vehicle, rider)) return;
            if (vehicle.level() instanceof ServerLevel level) {
                CityTrafficService.releaseForControl(level, vehicle);
            }
            vehicle.getPersistentData().putBoolean(MANAGED_KEY, false);
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level.dimension() != Level.OVERWORLD
                || level.getGameTime() % SPAWN_INTERVAL_TICKS != 0
                || level.players().isEmpty()
                || !NeonCityGenerator.isMegacityWorld(level)) {
            return;
        }

        List<FuelPoweredVehicleEntity> vehicles = loadedVehicles(level);
        retireUnclaimedVehicles(level, vehicles);
        vehicles.removeIf(vehicle -> !vehicle.isAlive());
        Set<Long> processedCells = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            if (processedCells.add(populationCell(player.blockPosition()))) {
                replenish(level, player, vehicles);
            }
            if (vehicles.size() >= MAX_LOADED_VEHICLES) break;
        }
    }

    private static void replenish(
            ServerLevel level,
            ServerPlayer player,
            List<FuelPoweredVehicleEntity> vehicles) {
        TrafficFocus focus = trafficFocus(player);
        Vec3 populationOffset = focus.position().subtract(player.position()).scale(0.65);
        AABB nearbyArea = player.getBoundingBox().move(populationOffset).inflate(NEARBY_RADIUS);
        AABB highwayArea = player.getBoundingBox().move(populationOffset)
                .inflate(HIGHWAY_NEARBY_RADIUS);
        int nearby = 0;
        int nearbyHighwayTraffic = 0;
        int nearbyAtlasTraffic = 0;
        int nearbyParked = 0;
        for (FuelPoweredVehicleEntity vehicle : vehicles) {
            if (!vehicle.isAlive()) continue;
            if (nearbyArea.intersects(vehicle.getBoundingBox())) {
                if (CityTrafficService.isActiveTraffic(vehicle)) {
                    if (CityTrafficService.isAtlasTraffic(vehicle)) nearbyAtlasTraffic++;
                } else if (!CityTrafficService.hasTrafficDriver(vehicle)) {
                    nearbyParked++;
                }
                nearby++;
            }
            if (highwayArea.intersects(vehicle.getBoundingBox())
                    && CityTrafficService.isActiveTraffic(vehicle)
                    && CityTrafficService.isHighwayTraffic(vehicle)) {
                nearbyHighwayTraffic++;
            }
        }

        MegacityLayout.ConnectionProjection playerHighway = NeonCityGenerator.layout()
                .nearestConnection(player.getX(), player.getZ()).orElse(null);
        boolean highwayActive = playerHighway != null
                && playerHighway.distance() <= HIGHWAY_ACTIVATION_RADIUS;
        MegacityLayout.ConnectionProjection nearestHighway = focus.driving()
                ? NeonCityGenerator.layout().nearestConnection(
                        focus.position().x, focus.position().z).orElse(playerHighway)
                : playerHighway;
        boolean atlasActive = NeonCityGenerator.nearestAtlasTrafficRoad(
                focus.position().x, focus.position().z, 48.0).isPresent();

        int highwayWanted = highwayActive
                ? Math.max(0, TARGET_HIGHWAY_TRAFFIC_NEARBY - nearbyHighwayTraffic) : 0;
        int atlasWanted = atlasActive
                ? Math.max(0, TARGET_ATLAS_TRAFFIC_NEARBY - nearbyAtlasTraffic) : 0;
        int parkedWanted = atlasActive
                ? Math.max(0, TARGET_PARKED_NEARBY - nearbyParked) : 0;
        int wanted = desiredSpawnCount(
                highwayWanted, atlasWanted, parkedWanted, nearby, vehicles.size());
        if (wanted <= 0) return;

        RandomSource random = level.getRandom();
        int spawned = 0;
        int spawnedHighway = 0;
        int spawnedAtlas = 0;
        int attempts = 0;
        while (spawned < wanted && attempts++ < MAX_PLACEMENT_ATTEMPTS) {
            SpawnKind kind = spawnedHighway < highwayWanted
                    ? SpawnKind.HIGHWAY_TRAFFIC
                    : spawnedAtlas < atlasWanted
                            ? SpawnKind.ATLAS_TRAFFIC : SpawnKind.PARKED;
            boolean moving = kind != SpawnKind.PARKED;
            boolean motorbike = !moving && random.nextInt(100) < MOTORBIKE_PERCENT;
            ParkingSite site = switch (kind) {
                case HIGHWAY_TRAFFIC -> findHighwayTrafficSite(
                        level, player, focus, nearestHighway, random);
                case ATLAS_TRAFFIC -> findAtlasTrafficSite(
                        level, player, focus, random);
                case PARKED -> findParkingSite(level, focus, random, motorbike);
            };
            if (site == null || !hasVehicleSeparation(vehicles, site.position())) continue;

            EntityType<? extends FuelPoweredVehicleEntity> type = randomType(random, motorbike);
            FuelPoweredVehicleEntity vehicle = type.create(level, EntitySpawnReason.STRUCTURE);
            if (vehicle == null) continue;
            BlockPos position = site.position();
            vehicle.snapTo(
                    position.getX() + 0.5,
                    position.getY(),
                    position.getZ() + 0.5,
                    site.yaw(),
                    0.0F);
            if (!level.noCollision(vehicle)) {
                vehicle.discard();
                continue;
            }

            vehicle.setFuel(randomizedFuelLevel(vehicle.getFuelCapacity(), random));
            vehicle.setPersistenceRequired();
            vehicle.getPersistentData().putBoolean(MANAGED_KEY, true);
            VehicleQuickhackService.markCompatibleCar(vehicle);
            if (level.addFreshEntity(vehicle)) {
                vehicles.add(vehicle);
                if (moving) {
                    if (!CityTrafficService.assignDriver(level, vehicle, random)) {
                        vehicle.discard();
                        vehicles.remove(vehicle);
                        continue;
                    }
                }
                spawned++;
                switch (kind) {
                    case HIGHWAY_TRAFFIC -> spawnedHighway++;
                    case ATLAS_TRAFFIC -> spawnedAtlas++;
                    case PARKED -> { }
                }
            } else {
                vehicle.discard();
            }
        }
        if (spawned > 0) {
            Cyberdeck.LOGGER.debug(
                    "Spawned {} roadside vehicles near {}",
                    spawned,
                    player.getScoreboardName());
        }
    }

    private static ParkingSite findHighwayTrafficSite(
            ServerLevel level,
            ServerPlayer player,
            TrafficFocus focus,
            MegacityLayout.ConnectionProjection nearest,
            RandomSource random) {
        if (nearest == null) return null;
        MegacityLayout.Edge edge = nearest.edge();
        double edgeLength = Math.max(1.0, Math.hypot(
                edge.second().x() - edge.first().x(),
                edge.second().z() - edge.first().z()));
        Vec3 look = player.getLookAngle();
        for (int attempt = 0; attempt < 8; attempt++) {
            double direction;
            double distance;
            if (focus.driving()) {
                double tangentLength = Math.max(1.0,
                        Math.hypot(nearest.tangentX(), nearest.tangentZ()));
                double along = focus.forward().x * nearest.tangentX() / tangentLength
                        + focus.forward().z * nearest.tangentZ() / tangentLength;
                direction = along >= 0.0 ? 1.0 : -1.0;
                distance = focus.lead() + 24.0 + random.nextDouble() * 64.0;
            } else {
                distance = 48.0 + random.nextDouble() * 72.0;
                direction = random.nextBoolean() ? 1.0 : -1.0;
            }
            double progress = Mth.clamp(
                    nearest.progress() + direction * distance / edgeLength, 0.02, 0.98);
            MegacityLayout.CurvePoint point = MegacityLayout.curvePoint(edge, progress);
            double tangentLength = Math.max(1.0,
                    Math.hypot(point.tangentX(), point.tangentZ()));
            double forwardX = point.tangentX() / tangentLength;
            double forwardZ = point.tangentZ() / tangentLength;
            if (random.nextBoolean()) {
                forwardX = -forwardX;
                forwardZ = -forwardZ;
            }
            double laneX = point.x() + forwardZ * 4.5;
            double laneZ = point.z() - forwardX * 4.5;
            double fromPlayerX = laneX - player.getX();
            double fromPlayerZ = laneZ - player.getZ();
            double dot = (fromPlayerX * look.x + fromPlayerZ * look.z)
                    / Math.max(0.01, Math.hypot(fromPlayerX, fromPlayerZ)
                            * Math.hypot(look.x, look.z));
            if (!focus.driving() && dot > 0.25) continue;

            int x = Mth.floor(laneX);
            int z = Mth.floor(laneZ);
            NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(x, z);
            if (!NeonCityGenerator.isHighwayRoadClass(sample.roadClass())) continue;
            BlockPos probe = new BlockPos(x, sample.groundY() + 1, z);
            if (!level.hasChunkAt(probe)) continue;
            int surfaceY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(surfaceY - (sample.groundY() + 1)) > 2) continue;
            BlockPos position = new BlockPos(x, surfaceY, z);
            if (!isVehicleSurface(level, position)) continue;
            float yaw = (float) Math.toDegrees(Math.atan2(-forwardX, forwardZ));
            return new ParkingSite(position, yaw);
        }
        return null;
    }

    private static ParkingSite findAtlasTrafficSite(
            ServerLevel level,
            ServerPlayer player,
            TrafficFocus focus,
            RandomSource random
    ) {
        Vec3 look = player.getLookAngle();
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = MIN_SPAWN_RADIUS
                    + random.nextDouble() * (MAX_SPAWN_RADIUS - MIN_SPAWN_RADIUS);
            double probeX = focus.position().x + Math.cos(angle) * radius;
            double probeZ = focus.position().z + Math.sin(angle) * radius;
            NeonCityGenerator.AtlasRoadPoint road = NeonCityGenerator.nearestAtlasTrafficRoad(
                    probeX, probeZ, ATLAS_ROAD_SEARCH_RADIUS).orElse(null);
            if (road == null) continue;
            double tangentLength = Math.max(
                    0.001, Math.hypot(road.tangentX(), road.tangentZ()));
            double forwardX = road.tangentX() / tangentLength;
            double forwardZ = road.tangentZ() / tangentLength;
            if (random.nextBoolean()) {
                forwardX = -forwardX;
                forwardZ = -forwardZ;
            }
            double laneOffset = Mth.clamp(road.width() * 0.22, 1.1, 2.6);
            double laneX = road.x() + forwardZ * laneOffset;
            double laneZ = road.z() - forwardX * laneOffset;
            int x = Mth.floor(laneX);
            int z = Mth.floor(laneZ);
            if (!NeonCityGenerator.isAtlasTrafficRoadAt(x, z)) continue;

            double fromPlayerX = laneX - player.getX();
            double fromPlayerZ = laneZ - player.getZ();
            double dot = (fromPlayerX * look.x + fromPlayerZ * look.z)
                    / Math.max(0.01, Math.hypot(fromPlayerX, fromPlayerZ)
                            * Math.hypot(look.x, look.z));
            if (!focus.driving() && dot > 0.25) continue;
            BlockPos probe = new BlockPos(x, NeonCityGenerator.CITY_GROUND_Y + 1, z);
            if (!level.hasChunkAt(probe)) continue;
            int surfaceY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(surfaceY - (NeonCityGenerator.CITY_GROUND_Y + 1)) > 2) continue;
            BlockPos position = new BlockPos(x, surfaceY, z);
            if (!isVehicleSurface(level, position)) continue;
            float yaw = (float) Math.toDegrees(Math.atan2(-forwardX, forwardZ));
            return new ParkingSite(position, yaw);
        }
        return null;
    }

    private static ParkingSite findParkingSite(
            ServerLevel level,
            TrafficFocus focus,
            RandomSource random,
            boolean motorbike) {
        double angle = random.nextDouble() * Math.PI * 2.0;
        double radius = MIN_SPAWN_RADIUS
                + random.nextDouble() * (MAX_SPAWN_RADIUS - MIN_SPAWN_RADIUS);
        double probeX = focus.position().x + Math.cos(angle) * radius;
        double probeZ = focus.position().z + Math.sin(angle) * radius;
        NeonCityGenerator.AtlasRoadPoint road = NeonCityGenerator.nearestAtlasTrafficRoad(
                probeX, probeZ, ATLAS_ROAD_SEARCH_RADIUS).orElse(null);
        if (road == null || road.width() < MIN_PARKING_ROAD_WIDTH) return null;

        double tangentLength = Math.max(
                0.001, Math.hypot(road.tangentX(), road.tangentZ()));
        double forwardX = road.tangentX() / tangentLength;
        double forwardZ = road.tangentZ() / tangentLength;
        float yaw = (float) Math.toDegrees(Math.atan2(-forwardX, forwardZ));
        if (random.nextBoolean()) yaw += 180.0F;
        double vehicleHalfWidth = motorbike ? 0.7 : 1.3;
        int firstSide = random.nextBoolean() ? 1 : -1;
        for (int sideAttempt = 0; sideAttempt < 2; sideAttempt++) {
            int side = sideAttempt == 0 ? firstSide : -firstSide;
            double normalX = forwardZ * side;
            double normalZ = -forwardX * side;
            double baseOffset = road.width() * 0.5
                    + vehicleHalfWidth + PARKING_SHOULDER_GAP;
            for (int extra = 0; extra <= 5; extra++) {
                double centerX = road.x() + normalX * (baseOffset + extra);
                double centerZ = road.z() + normalZ * (baseOffset + extra);
                int x = Mth.floor(centerX);
                int z = Mth.floor(centerZ);
                BlockPos loaded = new BlockPos(
                        x, NeonCityGenerator.CITY_GROUND_Y + 1, z);
                if (!level.hasChunkAt(loaded)) continue;
                int surfaceY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (Math.abs(surfaceY - (NeonCityGenerator.CITY_GROUND_Y + 1)) > 2) {
                    continue;
                }
                BlockPos position = new BlockPos(x, surfaceY, z);
                if (hasFlatParkingFootprint(level, position, yaw, motorbike)) {
                    return new ParkingSite(position, yaw);
                }
            }
        }
        return null;
    }

    private static boolean hasFlatParkingFootprint(
            ServerLevel level, BlockPos center, float yaw, boolean motorbike) {
        double halfLength = motorbike ? 1.5 : 2.6;
        double halfWidth = motorbike ? 0.7 : 1.3;
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double rightX = Math.cos(radians);
        double rightZ = Math.sin(radians);
        int minimumY = Integer.MAX_VALUE;
        int maximumY = Integer.MIN_VALUE;
        Set<Long> checked = new HashSet<>();
        for (double along = -halfLength; along <= halfLength + 0.01; along += 0.75) {
            for (double across = -halfWidth; across <= halfWidth + 0.01; across += 0.75) {
                int x = Mth.floor(center.getX() + 0.5
                        + forwardX * along + rightX * across);
                int z = Mth.floor(center.getZ() + 0.5
                        + forwardZ * along + rightZ * across);
                long column = BlockPos.asLong(x, 0, z);
                if (!checked.add(column)) continue;
                if (NeonCityGenerator.atlasRoadAt(x, z)
                                != NeonCityGenerator.AtlasRoadClass.NONE
                        || NeonCityGenerator.isHighwayRoadClass(
                                NeonCityGenerator.roadAt(x, z))) {
                    return false;
                }
                if (!level.hasChunkAt(new BlockPos(
                        x, NeonCityGenerator.CITY_GROUND_Y + 1, z))) {
                    return false;
                }
                int surfaceY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (Math.abs(surfaceY - center.getY()) > 1) return false;
                BlockPos surface = new BlockPos(x, surfaceY, z);
                if (!isVehicleSurface(level, surface)
                        || !level.getBlockState(surface.above()).isAir()) {
                    return false;
                }
                minimumY = Math.min(minimumY, surfaceY);
                maximumY = Math.max(maximumY, surfaceY);
            }
        }
        return maximumY - minimumY <= 1;
    }

    private static boolean isVehicleSurface(ServerLevel level, BlockPos position) {
        var at = level.getBlockState(position);
        var below = level.getBlockState(position.below());
        if (at.is(Blocks.SNOW)) {
            return below.isSolid();
        }
        if (!at.isAir()) return false;
        if (below.isSolid()) return true;
        return below.is(Blocks.SNOW)
                && level.getBlockState(position.below(2)).isSolid();
    }

    static double drivingLeadDistance(ServerPlayer player) {
        return trafficFocus(player).lead();
    }

    private static TrafficFocus trafficFocus(ServerPlayer player) {
        Entity root = player;
        while (root.getVehicle() != null) root = root.getVehicle();
        boolean driving = root != player;
        Vec3 movement = driving ? root.getDeltaMovement() : player.getDeltaMovement();
        Vec3 horizontal = new Vec3(movement.x, 0.0, movement.z);
        if (horizontal.lengthSqr() < 0.0016) {
            Vec3 look = player.getLookAngle();
            horizontal = new Vec3(look.x, 0.0, look.z);
        }
        Vec3 forward = horizontal.lengthSqr() < 1.0E-6
                ? new Vec3(0.0, 0.0, 1.0) : horizontal.normalize();
        double lead = driving
                ? drivingLeadForSpeed(movement.horizontalDistance()) : 0.0;
        return new TrafficFocus(player.position().add(forward.scale(lead)),
                forward, lead, driving);
    }

    public static double drivingLeadForSpeed(double horizontalBlocksPerTick) {
        return Mth.clamp(MIN_DRIVING_LEAD
                        + Math.max(0.0, horizontalBlocksPerTick)
                                * DRIVING_LEAD_PER_BLOCK_PER_TICK,
                MIN_DRIVING_LEAD, MAX_DRIVING_LEAD);
    }

    public static boolean isMovingTrafficRoad(NeonCityGenerator.RoadClass roadClass) {
        return NeonCityGenerator.isHighwayRoadClass(roadClass);
    }

    public static int targetParkedNearby() {
        return TARGET_PARKED_NEARBY;
    }

    /** Pure bounded population budget shared with regression tests. */
    public static int desiredSpawnCount(
            int highwayWanted,
            int atlasWanted,
            int parkedWanted,
            int nearby,
            int loadedVehicles) {
        int demand = Math.max(0, highwayWanted)
                + Math.max(0, atlasWanted)
                + Math.max(0, parkedWanted);
        int nearbyCapacity = Math.max(0, TARGET_TOTAL_NEARBY - Math.max(0, nearby));
        int loadedCapacity = Math.max(
                0, MAX_LOADED_VEHICLES - Math.max(0, loadedVehicles));
        return Math.min(SPAWN_BATCH, Math.min(demand,
                Math.min(nearbyCapacity, loadedCapacity)));
    }

    public static int randomizedFuelLevel(int capacity, RandomSource random) {
        if (capacity <= 0) return 0;
        int percent = MIN_FUEL_PERCENT
                + random.nextInt(MAX_FUEL_PERCENT - MIN_FUEL_PERCENT + 1);
        return Math.max(1, Math.min(capacity, Math.round(capacity * percent / 100.0F)));
    }

    private static EntityType<? extends FuelPoweredVehicleEntity> randomType(
            RandomSource random, boolean motorbike) {
        if (motorbike) {
            return switch (random.nextInt(3)) {
                case 0 -> vehicle_mod.MOTORBIKE.get();
                case 1 -> vehicle_mod.HARLEY_MOTORCYCLE.get();
                default -> vehicle_mod.CYBERPUNK_MOTORBIKE.get();
            };
        }
        return switch (random.nextInt(6)) {
            case 0 -> vehicle_mod.ORANGE_HYPERCAR.get();
            case 1 -> vehicle_mod.BMW_M3_GTR.get();
            case 2 -> vehicle_mod.DATSUN_240Z.get();
            case 3 -> vehicle_mod.JEEP_WRANGLER.get();
            case 4 -> vehicle_mod.TURBOWAGON.get();
            default -> vehicle_mod.DUNE_BUGGY.get();
        };
    }

    private static List<FuelPoweredVehicleEntity> loadedVehicles(ServerLevel level) {
        List<FuelPoweredVehicleEntity> vehicles = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof FuelPoweredVehicleEntity vehicle && vehicle.isAlive()) {
                vehicles.add(vehicle);
            }
        }
        return vehicles;
    }

    private static void retireUnclaimedVehicles(
            ServerLevel level, List<FuelPoweredVehicleEntity> vehicles) {
        for (FuelPoweredVehicleEntity vehicle : vehicles) {
            if (!vehicle.getPersistentData().getBooleanOr(MANAGED_KEY, false)) continue;
            if (!vehicle.getPassengers().isEmpty()
                    && !CityTrafficService.hasTrafficDriver(vehicle)) {
                vehicle.getPersistentData().putBoolean(MANAGED_KEY, false);
                continue;
            }
            boolean traffic = CityTrafficService.hasTrafficDriver(vehicle);
            int retirementAge = traffic ? TRAFFIC_RETIRE_AFTER_TICKS : RETIRE_AFTER_TICKS;
            double retirementDistance = traffic ? 112.0 : RETIRE_DISTANCE;
            if (vehicle.tickCount >= retirementAge
                    && nearestPlayerDistanceSqr(vehicle, level.players())
                            > retirementDistance * retirementDistance
                    && CityTrafficService.canRetireOutOfSight(level, vehicle)) {
                CityTrafficService.retire(vehicle);
                vehicle.discard();
            }
        }
    }

    private static boolean hasVehicleSeparation(
            List<FuelPoweredVehicleEntity> vehicles, BlockPos position) {
        double centerX = position.getX() + 0.5;
        double centerZ = position.getZ() + 0.5;
        double minimumSqr = MIN_SEPARATION * MIN_SEPARATION;
        for (FuelPoweredVehicleEntity vehicle : vehicles) {
            double dx = vehicle.getX() - centerX;
            double dz = vehicle.getZ() - centerZ;
            if (dx * dx + dz * dz < minimumSqr) return false;
        }
        return true;
    }

    private static double nearestPlayerDistanceSqr(
            FuelPoweredVehicleEntity vehicle, List<ServerPlayer> players) {
        double nearest = Double.POSITIVE_INFINITY;
        for (ServerPlayer player : players) {
            if (player.isAlive() && !player.isSpectator()) {
                nearest = Math.min(nearest, vehicle.distanceToSqr(player));
            }
        }
        return nearest;
    }

    private static long populationCell(BlockPos position) {
        int x = Math.floorDiv(position.getX(), POPULATION_CELL_SIZE);
        int z = Math.floorDiv(position.getZ(), POPULATION_CELL_SIZE);
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
