package com.example.cyberdeck.vehicle;

import com.example.cyberdeck.Cyberdeck;
import com.modernity.vehicle_mod.entity.FuelPoweredVehicleEntity;
import com.modernity.vehicle_mod.vehicle_mod;
import dev.modernity.neoncity.NeonCityGenerator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Maintains a small population of fuelled, parked cars and motorbikes on megacity roads. */
@EventBusSubscriber(modid = Cyberdeck.MODID)
public final class RoadsideVehicleSpawns {
    static final int SPAWN_INTERVAL_TICKS = 100;
    static final int TARGET_NEARBY = 6;
    static final int SPAWN_BATCH = 2;
    static final int MAX_LOADED_VEHICLES = 24;
    static final int MOTORBIKE_PERCENT = 35;
    static final int MIN_FUEL_PERCENT = 5;
    static final int MAX_FUEL_PERCENT = 95;

    private static final int POPULATION_CELL_SIZE = 128;
    private static final int MAX_PLACEMENT_ATTEMPTS = 24;
    private static final int RETIRE_AFTER_TICKS = 600;
    private static final int MIN_SPAWN_RADIUS = 20;
    private static final int MAX_SPAWN_RADIUS = 72;
    private static final double NEARBY_RADIUS = 88.0;
    private static final double RETIRE_DISTANCE = 144.0;
    private static final double MIN_SEPARATION = 11.0;
    private static final String MANAGED_KEY = Cyberdeck.MODID + ":roadside_vehicle_managed";

    private RoadsideVehicleSpawns() {
    }

    private record ParkingSite(BlockPos position, float yaw) {
    }

    @SubscribeEvent
    public static void onVehicleClaimed(EntityMountEvent event) {
        if (event.isMounting()
                && event.getEntityBeingMounted() instanceof FuelPoweredVehicleEntity vehicle) {
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
        if (vehicles.size() >= MAX_LOADED_VEHICLES) return;

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
        AABB nearbyArea = player.getBoundingBox().inflate(NEARBY_RADIUS);
        int nearby = 0;
        for (FuelPoweredVehicleEntity vehicle : vehicles) {
            if (vehicle.isAlive() && nearbyArea.intersects(vehicle.getBoundingBox())) nearby++;
        }
        int wanted = Math.min(
                SPAWN_BATCH,
                Math.min(TARGET_NEARBY - nearby, MAX_LOADED_VEHICLES - vehicles.size()));
        if (wanted <= 0) return;

        RandomSource random = level.getRandom();
        int spawned = 0;
        int attempts = 0;
        while (spawned < wanted && attempts++ < MAX_PLACEMENT_ATTEMPTS) {
            boolean motorbike = random.nextInt(100) < MOTORBIKE_PERCENT;
            ParkingSite site = findParkingSite(level, player, random, motorbike);
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
                spawned++;
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

    private static ParkingSite findParkingSite(
            ServerLevel level,
            ServerPlayer player,
            RandomSource random,
            boolean motorbike) {
        double angle = random.nextDouble() * Math.PI * 2.0;
        int radius = MIN_SPAWN_RADIUS
                + random.nextInt(MAX_SPAWN_RADIUS - MIN_SPAWN_RADIUS + 1);
        int x = (int) Math.floor(player.getX() + Math.cos(angle) * radius);
        int z = (int) Math.floor(player.getZ() + Math.sin(angle) * radius);
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
        if (!level.getBlockState(position.below()).isSolid()
                || !level.isEmptyBlock(position)) {
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

    static boolean isParkableRoad(
            NeonCityGenerator.RoadClass roadClass, boolean motorbike) {
        return roadClass == NeonCityGenerator.RoadClass.LOCAL_STREET
                || roadClass == NeonCityGenerator.RoadClass.DISTRICT_BOULEVARD
                || (motorbike && roadClass == NeonCityGenerator.RoadClass.SERVICE_ALLEY);
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
            if (!vehicle.getPassengers().isEmpty()) {
                vehicle.getPersistentData().putBoolean(MANAGED_KEY, false);
                continue;
            }
            if (vehicle.tickCount >= RETIRE_AFTER_TICKS
                    && nearestPlayerDistanceSqr(vehicle, level.players())
                            > RETIRE_DISTANCE * RETIRE_DISTANCE) {
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
