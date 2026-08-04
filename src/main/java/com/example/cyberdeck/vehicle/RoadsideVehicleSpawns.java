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
import net.minecraft.core.Direction;
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
    static final int TARGET_LOCAL_TRAFFIC_NEARBY = 4;
    static final int TARGET_TOTAL_NEARBY = 16;
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

    @SubscribeEvent
    public static void onVehicleClaimed(EntityMountEvent event) {
        if (event.isMounting()
                && event.getEntityBeingMounted() instanceof FuelPoweredVehicleEntity vehicle) {
            Entity rider = event.getEntityMounting();
            if (CityTrafficService.isManagedPair(vehicle, rider)) return;
            if (vehicle.level() instanceof ServerLevel level) {
                CityTrafficService.releaseForControl(level, vehicle);
            }
            releaseManagedOwnership(vehicle);
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
        int nearbyLocalTraffic = 0;
        for (FuelPoweredVehicleEntity vehicle : vehicles) {
            if (!vehicle.isAlive()) continue;
            if (nearbyArea.intersects(vehicle.getBoundingBox())) {
                boolean activeTraffic = CityTrafficService.isActiveTraffic(vehicle);
                boolean inactiveTraffic = CityTrafficService.hasTrafficDriver(vehicle)
                        && !activeTraffic;
                if (!inactiveTraffic) nearby++;
            }
            if (highwayArea.intersects(vehicle.getBoundingBox())
                    && CityTrafficService.isActiveTraffic(vehicle)
                    && NeonCityGenerator.isHighwayRoadClass(NeonCityGenerator.sample(
                            vehicle.getBlockX(), vehicle.getBlockZ()).roadClass())) {
                nearbyHighwayTraffic++;
            }
            if (nearbyArea.intersects(vehicle.getBoundingBox())
                    && CityTrafficService.isActiveTraffic(vehicle)
                    && isLocalTrafficRoad(NeonCityGenerator.sample(
                            vehicle.getBlockX(), vehicle.getBlockZ()).roadClass())) {
                nearbyLocalTraffic++;
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

        // Preserve the current highway population while independently restoring district traffic.
        if (highwayActive && nearbyHighwayTraffic < TARGET_HIGHWAY_TRAFFIC_NEARBY) {
            for (FuelPoweredVehicleEntity vehicle : vehicles) {
                if (nearbyHighwayTraffic >= TARGET_HIGHWAY_TRAFFIC_NEARBY) break;
                if (!vehicle.isAlive()
                        || !highwayArea.intersects(vehicle.getBoundingBox())
                        || !vehicle.getPersistentData().getBooleanOr(MANAGED_KEY, false)
                        || !vehicle.getPassengers().isEmpty()
                        || !isMovingTrafficRoad(NeonCityGenerator.sample(
                                vehicle.getBlockX(), vehicle.getBlockZ()).roadClass())
                        || isMotorbike(vehicle)) {
                    continue;
                }
                if (CityTrafficService.assignDriver(level, vehicle, level.getRandom())) {
                    nearbyHighwayTraffic++;
                }
            }
        }

        if (nearbyLocalTraffic < TARGET_LOCAL_TRAFFIC_NEARBY) {
            for (FuelPoweredVehicleEntity vehicle : vehicles) {
                if (nearbyLocalTraffic >= TARGET_LOCAL_TRAFFIC_NEARBY) break;
                if (!vehicle.isAlive()
                        || !nearbyArea.intersects(vehicle.getBoundingBox())
                        || !isManagedVehicle(vehicle)
                        || !vehicle.getPassengers().isEmpty()
                        || !isLocalTrafficRoad(NeonCityGenerator.sample(
                                vehicle.getBlockX(), vehicle.getBlockZ()).roadClass())) {
                    continue;
                }
                if (CityTrafficService.assignDriver(level, vehicle, level.getRandom())) {
                    nearbyLocalTraffic++;
                }
            }
        }

        int highwayWanted = highwayActive
                ? Math.max(0, TARGET_HIGHWAY_TRAFFIC_NEARBY - nearbyHighwayTraffic) : 0;
        int localTrafficWanted = Math.max(
                0, TARGET_LOCAL_TRAFFIC_NEARBY - nearbyLocalTraffic);
        int wanted = Math.min(
                SPAWN_BATCH,
                Math.min(Math.max(highwayWanted + localTrafficWanted,
                                TARGET_TOTAL_NEARBY - nearby),
                        MAX_LOADED_VEHICLES - vehicles.size()));
        if (wanted <= 0) return;

        RandomSource random = level.getRandom();
        int spawned = 0;
        int spawnedHighway = 0;
        int spawnedLocalTraffic = 0;
        int attempts = 0;
        while (spawned < wanted && attempts++ < MAX_PLACEMENT_ATTEMPTS) {
            boolean highwayRemaining = spawnedHighway < highwayWanted;
            boolean localTrafficRemaining = spawnedLocalTraffic < localTrafficWanted;
            // Alternate while both quotas are short so an unavailable highway segment cannot
            // consume the entire placement budget and starve valid district traffic.
            boolean highway = highwayRemaining
                    && (!localTrafficRemaining || (attempts & 1) == 1);
            boolean localTraffic = localTrafficRemaining && !highway;
            boolean motorbike = selectsMotorbike(random, highway);
            ParkingSite site = highway
                    ? findHighwayTrafficSite(level, player, focus, nearestHighway, random)
                    : localTraffic
                            ? findLocalTrafficSite(level, focus, random)
                            : findParkingSite(level, player, focus, random, motorbike);
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
            markManagedVehicle(vehicle);
            VehicleQuickhackService.markCompatibleCar(vehicle);
            if (level.addFreshEntity(vehicle)) {
                vehicles.add(vehicle);
                if (highway || localTraffic) {
                    if (!CityTrafficService.assignDriver(level, vehicle, random)) {
                        vehicle.discard();
                        vehicles.remove(vehicle);
                        continue;
                    }
                }
                spawned++;
                if (highway) spawnedHighway++;
                if (localTraffic) spawnedLocalTraffic++;
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

    private static boolean isMotorbike(FuelPoweredVehicleEntity vehicle) {
        return vehicle instanceof com.modernity.vehicle_mod.entity.MotorbikeEntity;
    }

    private static ParkingSite findLocalTrafficSite(
            ServerLevel level, TrafficFocus focus, RandomSource random) {
        double angle = random.nextDouble() * Math.PI * 2.0;
        int radius = MIN_SPAWN_RADIUS
                + random.nextInt(MAX_SPAWN_RADIUS - MIN_SPAWN_RADIUS + 1);
        int x = (int) Math.floor(focus.position().x + Math.cos(angle) * radius);
        int z = (int) Math.floor(focus.position().z + Math.sin(angle) * radius);
        NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(x, z);
        if (!isLocalTrafficRoad(sample.roadClass())) return null;

        int alongXScore = localTrafficRoadScore(x, z, true);
        int alongZScore = localTrafficRoadScore(x, z, false);
        if (Math.max(alongXScore, alongZScore) < 3) return null;
        boolean alongX = alongXScore > alongZScore;
        Direction heading = alongX
                ? (random.nextBoolean() ? Direction.EAST : Direction.WEST)
                : (random.nextBoolean() ? Direction.SOUTH : Direction.NORTH);
        BlockPos probe = new BlockPos(x, sample.groundY() + 1, z);
        if (!level.hasChunkAt(probe)) return null;
        int surfaceY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (Math.abs(surfaceY - (sample.groundY() + 1)) > 2) return null;
        BlockPos position = new BlockPos(x, surfaceY, z);
        if (!isVehicleSurface(level, position)) return null;
        float yaw = switch (heading) {
            case WEST -> 90.0F;
            case NORTH -> 180.0F;
            case EAST -> 270.0F;
            default -> 0.0F;
        };
        CityTrafficGraph.LaneNode entry = CityTrafficGraph.enter(
                level, Vec3.atBottomCenterOf(position), yaw);
        if (entry == null || CityTrafficGraph.successors(level, entry).isEmpty()) return null;
        return new ParkingSite(position, entry.yaw());
    }

    private static ParkingSite findParkingSite(
            ServerLevel level,
            ServerPlayer player,
            TrafficFocus focus,
            RandomSource random,
            boolean motorbike) {
        double angle = random.nextDouble() * Math.PI * 2.0;
        int radius = MIN_SPAWN_RADIUS
                + random.nextInt(MAX_SPAWN_RADIUS - MIN_SPAWN_RADIUS + 1);
        int x = (int) Math.floor(focus.position().x + Math.cos(angle) * radius);
        int z = (int) Math.floor(focus.position().z + Math.sin(angle) * radius);
        NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(x, z);
        if (!isParkableRoad(sample.roadClass(), motorbike)) return null;

        boolean alongX = roadScore(x, z, true, motorbike)
                > roadScore(x, z, false, motorbike);
        Direction shoulder = alongX
                ? (random.nextBoolean() ? Direction.NORTH : Direction.SOUTH)
                : (random.nextBoolean() ? Direction.WEST : Direction.EAST);
        BlockPos roadEdge = moveTowardRoadEdge(x, z, sample.groundY(), shoulder, motorbike);
        if (!level.hasChunkAt(roadEdge)) return null;

        int surfaceY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                roadEdge.getX(),
                roadEdge.getZ());
        if (Math.abs(surfaceY - (sample.groundY() + 1)) > 2) return null;
        BlockPos position = new BlockPos(roadEdge.getX(), surfaceY, roadEdge.getZ());
        if (!isVehicleSurface(level, position)) {
            return null;
        }

        float yaw = alongX ? 90.0F : 0.0F;
        if (random.nextBoolean()) yaw += 180.0F;
        return new ParkingSite(position, yaw);
    }

    private static BlockPos moveTowardRoadEdge(
            int x, int z, int groundY, Direction direction, boolean motorbike) {
        BlockPos best = new BlockPos(x, groundY + 1, z);
        for (int step = 1; step <= 6; step++) {
            int candidateX = x + direction.getStepX() * step;
            int candidateZ = z + direction.getStepZ() * step;
            NeonCityGenerator.UrbanSample candidate =
                    NeonCityGenerator.sample(candidateX, candidateZ);
            if (!isParkableRoad(candidate.roadClass(), motorbike)
                    || Math.abs(candidate.groundY() - groundY) > 1) {
                break;
            }
            best = new BlockPos(candidateX, candidate.groundY() + 1, candidateZ);
        }
        return best;
    }

    private static int roadScore(int x, int z, boolean alongX, boolean motorbike) {
        int score = 0;
        for (int offset : new int[] {-6, -3, 3, 6}) {
            int sampleX = alongX ? x + offset : x;
            int sampleZ = alongX ? z : z + offset;
            if (isParkableRoad(
                    NeonCityGenerator.sample(sampleX, sampleZ).roadClass(), motorbike)) {
                score++;
            }
        }
        return score;
    }

    private static int localTrafficRoadScore(int x, int z, boolean alongX) {
        int score = 0;
        for (int offset : new int[] {-8, -4, 4, 8}) {
            int sampleX = alongX ? x + offset : x;
            int sampleZ = alongX ? z : z + offset;
            if (isLocalTrafficRoad(
                    NeonCityGenerator.sample(sampleX, sampleZ).roadClass())) {
                score++;
            }
        }
        return score;
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

    static boolean isParkableRoad(
            NeonCityGenerator.RoadClass roadClass, boolean motorbike) {
        return roadClass == NeonCityGenerator.RoadClass.LOCAL_STREET
                || roadClass == NeonCityGenerator.RoadClass.DISTRICT_BOULEVARD
                || (motorbike && roadClass == NeonCityGenerator.RoadClass.SERVICE_ALLEY);
    }

    public static boolean isMovingTrafficRoad(NeonCityGenerator.RoadClass roadClass) {
        return NeonCityGenerator.isHighwayRoadClass(roadClass);
    }

    public static boolean isLocalTrafficRoad(NeonCityGenerator.RoadClass roadClass) {
        return roadClass == NeonCityGenerator.RoadClass.LOCAL_STREET
                || roadClass == NeonCityGenerator.RoadClass.DISTRICT_BOULEVARD;
    }

    static boolean supportsMovingTraffic(NeonCityGenerator.RoadClass roadClass) {
        return isMovingTrafficRoad(roadClass) || isLocalTrafficRoad(roadClass);
    }

    static boolean isManagedVehicle(Entity vehicle) {
        return vehicle != null
                && vehicle.getPersistentData().getBooleanOr(MANAGED_KEY, false);
    }

    static void markManagedVehicle(Entity vehicle) {
        if (vehicle != null) vehicle.getPersistentData().putBoolean(MANAGED_KEY, true);
    }

    static void releaseManagedOwnership(Entity vehicle) {
        if (vehicle != null) vehicle.getPersistentData().remove(MANAGED_KEY);
    }

    public static int randomizedFuelLevel(int capacity, RandomSource random) {
        if (capacity <= 0) return 0;
        int percent = MIN_FUEL_PERCENT
                + random.nextInt(MAX_FUEL_PERCENT - MIN_FUEL_PERCENT + 1);
        return Math.max(1, Math.min(capacity, Math.round(capacity * percent / 100.0F)));
    }

    static boolean selectsMotorbike(RandomSource random, boolean highway) {
        return !highway && random.nextInt(100) < MOTORBIKE_PERCENT;
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
                releaseManagedOwnership(vehicle);
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
