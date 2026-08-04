package com.example.cyberdeck.npc;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.city.CityWorlds;
import com.example.cyberdeck.trauma.TraumaTeamEvents;
import com.example.cyberdeck.vehicle.CityTrafficService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Maintains a bounded, evenly distributed pedestrian population in explicit city presets. */
public final class CityNpcSpawns {
    private static final int SPAWN_INTERVAL = 100;
    private static final int TARGET_NEARBY = 12;
    private static final int SPAWN_BATCH = 2;
    private static final int POPULATION_CELL_SIZE = 96;
    private static final int MAX_PER_CELL = 8;
    private static final int MAX_LOADED_POPULATION = 64;
    private static final int MAX_PLACEMENT_ATTEMPTS = 12;
    private static final int RETIRE_AFTER_TICKS = 600;
    private static final double RETIRE_DISTANCE = 112.0;
    private static final double NEARBY_RADIUS = 72.0;
    private static final double MIN_SEPARATION = 9.0;

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level.dimension() != Level.OVERWORLD
                || level.getGameTime() % SPAWN_INTERVAL != 0
                || !CityWorlds.isCity(level)) {
            return;
        }

        List<CityNpc> managed = managedCivilians(level);
        reconcilePopulation(level, managed);
        managed.removeIf(npc -> !npc.isAlive());

        Map<Long, Integer> cellCounts = populationCellCounts(managed);
        // Nearby players share one population budget instead of independently duplicating crowds.
        Set<Long> processedPopulationCells = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            long cell = populationCell(player.blockPosition());
            if (processedPopulationCells.add(cell)) {
                replenish(level, player, managed, cellCounts);
            }
        }
    }

    private void replenish(ServerLevel level, ServerPlayer player, List<CityNpc> managed,
                           Map<Long, Integer> cellCounts) {
        AABB area = player.getBoundingBox().inflate(NEARBY_RADIUS);
        List<CityNpc> nearby = level.getEntitiesOfClass(
                CityNpc.class, area,
                npc -> npc.isAlive() && !CityTrafficService.isTrafficDriver(npc));
        if (nearby.size() >= TARGET_NEARBY) {
            return;
        }

        long playerCell = populationCell(player.blockPosition());
        int wanted = desiredSpawnCount(
                nearby.size(), cellCounts.getOrDefault(playerCell, 0), managed.size());
        if (wanted <= 0) {
            return;
        }
        RandomSource random = level.getRandom();
        int spawned = 0;
        int attempts = 0;
        while (spawned < wanted && attempts++ < MAX_PLACEMENT_ATTEMPTS
                && managed.size() < MAX_LOADED_POPULATION) {
            BlockPos position = CityWorlds.findPedestrianAreaNear(
                    level, player.blockPosition(), 12, 54, 40, random);
            if (position == null) {
                Cyberdeck.LOGGER.debug("No loaded walkable city street found near {} in {}",
                        player.getScoreboardName(), CityWorlds.kind(level));
                break;
            }

            long candidateCell = populationCell(position);
            if (cellCounts.getOrDefault(candidateCell, 0) >= MAX_PER_CELL
                    || !hasSpawnSeparation(level, position)) {
                continue;
            }
            CityNpc npc = CityNpcEntities.CITY_NPC.get().create(
                    level, EntitySpawnReason.NATURAL);
            if (npc == null) {
                continue;
            }
            npc.snapTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5,
                    random.nextFloat() * 360.0f, 0.0f);
            if (!level.noCollision(npc)) {
                npc.discard();
                continue;
            }
            npc.finalizeSpawn(level, level.getCurrentDifficultyAt(position),
                    EntitySpawnReason.NATURAL, null);
            int roleRoll = random.nextInt(100);
            NpcRole role = roleForRoll(roleRoll,
                    roleRoll < 5 && isExecSpawnSite(level, position));
            npc.setRole(role);
            npc.setSkinVariant(skinVariant(position, managed.size(), role));
            npc.setHomeTo(position, 28);
            npc.markPopulationManaged(position);
            if (level.addFreshEntity(npc)) {
                spawned++;
                managed.add(npc);
                cellCounts.merge(candidateCell, 1, Integer::sum);
            }
        }
        if (spawned > 0) {
            Cyberdeck.LOGGER.debug("Spawned {} city civilians near {} in {}",
                    spawned, player.getScoreboardName(), CityWorlds.kind(level));
        }
    }

    private static List<CityNpc> managedCivilians(ServerLevel level) {
        List<CityNpc> managed = new ArrayList<>();
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (entity instanceof CityNpc npc && npc.isAlive() && npc.isPopulationManaged()) {
                managed.add(npc);
            }
        }
        return managed;
    }

    private static void reconcilePopulation(ServerLevel level, List<CityNpc> managed) {
        List<ServerPlayer> players = level.players();
        for (CityNpc npc : managed) {
            if (shouldRetire(npc.tickCount,
                    nearestPlayerDistanceSqr(npc, players) <= RETIRE_DISTANCE * RETIRE_DISTANCE)) {
                npc.discard();
            }
        }
        managed.removeIf(npc -> !npc.isAlive());

        Map<Long, List<CityNpc>> byCell = new HashMap<>();
        for (CityNpc npc : managed) {
            byCell.computeIfAbsent(populationCell(npc.populationHome()), ignored -> new ArrayList<>())
                    .add(npc);
        }
        Comparator<CityNpc> farthestFirst = Comparator
                .comparingDouble((CityNpc npc) -> nearestPlayerDistanceSqr(npc, players))
                .reversed()
                .thenComparingInt(npc -> -npc.tickCount);
        for (List<CityNpc> residents : byCell.values()) {
            retireOverflow(residents, MAX_PER_CELL, farthestFirst);
        }
        managed.removeIf(npc -> !npc.isAlive());
        retireOverflow(managed, MAX_LOADED_POPULATION, farthestFirst);
    }

    private static void retireOverflow(List<CityNpc> civilians, int cap,
                                       Comparator<CityNpc> priority) {
        int overflow = civilians.size() - cap;
        if (overflow <= 0) {
            return;
        }
        civilians.sort(priority);
        for (int index = 0; index < overflow; index++) {
            civilians.get(index).discard();
        }
    }

    private static double nearestPlayerDistanceSqr(CityNpc npc, List<ServerPlayer> players) {
        double nearest = Double.POSITIVE_INFINITY;
        for (ServerPlayer player : players) {
            if (player.isAlive() && !player.isSpectator()) {
                nearest = Math.min(nearest, npc.distanceToSqr(player));
            }
        }
        return nearest;
    }

    private static Map<Long, Integer> populationCellCounts(List<CityNpc> managed) {
        Map<Long, Integer> counts = new HashMap<>();
        for (CityNpc npc : managed) {
            counts.merge(populationCell(npc.populationHome()), 1, Integer::sum);
        }
        return counts;
    }

    private static long populationCell(BlockPos position) {
        int cellX = Math.floorDiv(position.getX(), POPULATION_CELL_SIZE);
        int cellZ = Math.floorDiv(position.getZ(), POPULATION_CELL_SIZE);
        return ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
    }

    public static boolean hasSpawnSeparation(ServerLevel level, BlockPos position) {
        AABB area = new AABB(position).inflate(MIN_SEPARATION, 3.0, MIN_SEPARATION);
        return level.getEntitiesOfClass(
                CityNpc.class, area,
                npc -> npc.isAlive() && !CityTrafficService.isTrafficDriver(npc)).isEmpty();
    }

    public static int skinVariant(BlockPos position, int memberIndex) {
        return skinVariant(position, memberIndex, NpcRole.RESIDENT);
    }

    public static int skinVariant(BlockPos position, int memberIndex, NpcRole role) {
        long value = position.asLong() ^ (long) memberIndex * 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        int mixed = (int) (value ^ (value >>> 31));
        return switch (role) {
            case RESIDENT -> Math.floorMod(mixed, 6);
            case CORPO -> 6 + Math.floorMod(mixed, 2);
            case EXEC -> CityNpc.MISSION_TARGET_SKIN;
        };
    }

    /** Stable role distribution: 70% Resident, 25% Corpo, and 5% Exec at valid Exec sites. */
    public static NpcRole roleForRoll(int percentileRoll, boolean execSite) {
        int roll = Math.floorMod(percentileRoll, 100);
        if (roll < 5 && execSite) {
            return NpcRole.EXEC;
        }
        return roll < 30 ? NpcRole.CORPO : NpcRole.RESIDENT;
    }

    /** Execs require an aerodyne-sized plaza with a substantial building close to its edge. */
    public static boolean isExecSpawnSite(ServerLevel level, BlockPos position) {
        return TraumaTeamEvents.hasLandingClearance(level, position)
                && hasNearbyBuilding(level, position);
    }

    private static boolean hasNearbyBuilding(ServerLevel level, BlockPos position) {
        for (int radius = 8; radius <= 18; radius += 2) {
            for (int offset = -radius; offset <= radius; offset += 2) {
                if (isBuildingColumn(level, position.offset(offset, 0, -radius))
                        || isBuildingColumn(level, position.offset(offset, 0, radius))
                        || isBuildingColumn(level, position.offset(-radius, 0, offset))
                        || isBuildingColumn(level, position.offset(radius, 0, offset))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isBuildingColumn(ServerLevel level, BlockPos feet) {
        if (!level.isLoaded(feet)) {
            return false;
        }
        int solid = 0;
        for (int y = 0; y < 7; y++) {
            if (level.getBlockState(feet.above(y)).blocksMotion()) {
                solid++;
            }
        }
        return solid >= 5;
    }

    /** Pure population budget shared with regression tests. */
    public static int desiredSpawnCount(int nearby) {
        return desiredSpawnCount(nearby, 0, 0);
    }

    /** Pure three-tier population budget shared with regression tests. */
    public static int desiredSpawnCount(int nearby, int residentsInCell, int loadedPopulation) {
        int present = Math.max(0, nearby);
        int residents = Math.max(0, residentsInCell);
        int loaded = Math.max(0, loadedPopulation);
        return Math.max(0, Math.min(SPAWN_BATCH, Math.min(
                TARGET_NEARBY - present,
                Math.min(MAX_PER_CELL - residents, MAX_LOADED_POPULATION - loaded))));
    }

    public static boolean shouldRetire(int ageTicks, boolean hasNearbyPlayer) {
        return ageTicks >= RETIRE_AFTER_TICKS && !hasNearbyPlayer;
    }

    public static int targetNearby() {
        return TARGET_NEARBY;
    }

    public static int spawnBatch() {
        return SPAWN_BATCH;
    }

    public static int spawnInterval() {
        return SPAWN_INTERVAL;
    }

    public static double nearbyRadius() {
        return NEARBY_RADIUS;
    }

    public static int maxPerCell() {
        return MAX_PER_CELL;
    }

    public static int maxLoadedPopulation() {
        return MAX_LOADED_POPULATION;
    }
}
