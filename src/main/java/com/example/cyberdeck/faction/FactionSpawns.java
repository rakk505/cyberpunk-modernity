package com.example.cyberdeck.faction;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.city.CityWorlds;
import dev.modernity.neoncity.NeonCityGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Sparse public-area patrol generation. Each occupied spatial cell gets at most one probability-
 * gated attempt per epoch; successful patrols contain one or two soldiers and are inserted only
 * outside every nearby player's line of sight.
 */
public final class FactionSpawns {
    public static final int SPAWN_INTERVAL = 1_200;
    public static final int SPAWN_CHANCE_DENOMINATOR = 4;
    public static final int MIN_PATROL_SIZE = 1;
    public static final int MAX_PATROL_SIZE = 2;
    public static final int NEARBY_CAP = 4;
    public static final int LOADED_WORLD_CAP = 12;
    private static final int MIN_DISTANCE = 48;
    private static final int MAX_DISTANCE = 72;
    private static final double NEARBY_RADIUS = 96.0;
    private static final int POPULATION_CELL_SIZE = 64;
    private static final double PATROL_SEPARATION = 24.0;
    private static final long CLUSTER_SALT = 0x434C55535445524CL;
    private static final int[][] BASE_FORMATION = {
            {0, 0}, {2, 1}, {-2, 1}, {1, -2}, {-1, -2}, {3, 0}
    };

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level.dimension() != Level.OVERWORLD
                || CityWorlds.kind(level) != CityWorlds.Kind.NEON_MEGACITY
                || level.getGameTime() % SPAWN_INTERVAL != 0) {
            return;
        }

        long epoch = level.getGameTime() / SPAWN_INTERVAL;
        Map<Long, ServerPlayer> populationCells = new TreeMap<>();
        for (ServerPlayer player : level.players()) {
            if (!player.isAlive() || player.isCreative() || player.isSpectator()) {
                continue;
            }
            int cellX = Math.floorDiv(player.getBlockX(), POPULATION_CELL_SIZE);
            int cellZ = Math.floorDiv(player.getBlockZ(), POPULATION_CELL_SIZE);
            long cellKey = pack(cellX, cellZ);
            populationCells.merge(cellKey, player, FactionSpawns::stableRepresentative);
        }
        for (Map.Entry<Long, ServerPlayer> entry : populationCells.entrySet()) {
            ServerPlayer player = entry.getValue();
            int cellX = Math.floorDiv(player.getBlockX(), POPULATION_CELL_SIZE);
            int cellZ = Math.floorDiv(player.getBlockZ(), POPULATION_CELL_SIZE);
            RandomSource random = RandomSource.create(clusterSeed(
                    level.getSeed(), epoch, cellX, cellZ));
            trySpawnCluster(level, player, random);
        }
    }

    private void trySpawnCluster(ServerLevel level, ServerPlayer player, RandomSource random) {
        if (random.nextInt(SPAWN_CHANCE_DENOMINATOR) != 0) {
            return;
        }
        AABB nearbyArea = player.getBoundingBox().inflate(NEARBY_RADIUS);
        int nearby = level.getEntitiesOfClass(FactionEnemy.class, nearbyArea,
                enemy -> enemy.isAlive() && enemy.isAmbientPatrol()).size();
        int capacity = NEARBY_CAP - nearby;
        int loadedPatrols = 0;
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (entity instanceof FactionEnemy enemy
                    && enemy.isAlive() && enemy.isAmbientPatrol()) {
                loadedPatrols++;
            }
        }
        capacity = Math.min(capacity, LOADED_WORLD_CAP - loadedPatrols);
        if (capacity < MIN_PATROL_SIZE) {
            return;
        }
        int requested = Math.min(capacity,
                MIN_PATROL_SIZE + random.nextInt(MAX_PATROL_SIZE - MIN_PATROL_SIZE + 1));

        BlockPos anchor = findSpawnAnchor(level, player, random);
        if (anchor == null) {
            return;
        }
        if (!level.getEntitiesOfClass(
                FactionEnemy.class,
                new AABB(anchor).inflate(PATROL_SEPARATION),
                enemy -> enemy.isAlive() && enemy.isAmbientPatrol()).isEmpty()) {
            return;
        }
        int rotation = random.nextInt(4);
        List<BlockPos> positions = resolveFormation(level, anchor, rotation, requested);
        if (positions.size() < requested) {
            return; // all-or-nothing: never emit a broken partial squad
        }

        Faction faction = Faction.VALUES[random.nextInt(Faction.VALUES.length)];
        java.util.UUID patrolId = new java.util.UUID(random.nextLong(), random.nextLong());
        List<FactionEnemy> members = new ArrayList<>(requested);
        for (BlockPos position : positions) {
            FactionEnemy enemy = createMember(level, position, anchor, faction, random);
            if (enemy == null) {
                for (FactionEnemy member : members) {
                    member.discard();
                }
                return;
            }
            enemy.setAlertGroupId(patrolId);
            members.add(enemy);
        }
        for (FactionEnemy member : members) {
            if (!isConcealedFromPlayers(level, member)) {
                for (FactionEnemy squadMember : members) {
                    squadMember.discard();
                }
                return;
            }
        }
        for (FactionEnemy member : members) {
            if (!level.addFreshEntity(member)) {
                // Roll back members already inserted and discard those not yet inserted so a
                // failed insertion can never leave a partial pseudo-squad behind.
                for (FactionEnemy squadMember : members) {
                    squadMember.discard();
                }
                return;
            }
        }
        Cyberdeck.LOGGER.info("Spawned {}-member {} district patrol near {}",
                members.size(), members.getFirst().getDistrict().code(), player.getScoreboardName());
    }

    private static BlockPos findSpawnAnchor(ServerLevel level, ServerPlayer player,
                                            RandomSource random) {
        for (int attempt = 0; attempt < 48; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            int distance = MIN_DISTANCE + random.nextInt(MAX_DISTANCE - MIN_DISTANCE + 1);
            int x = player.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = player.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            BlockPos position = CityWorlds.resolvePedestrianFeet(
                    level, x, z, player.getBlockY());
            if (position != null && isPublicPatrolArea(NeonCityGenerator.sample(x, z))) {
                return position;
            }
        }
        return null;
    }

    private static List<BlockPos> resolveFormation(ServerLevel level, BlockPos anchor,
                                                   int rotation, int size) {
        List<BlockPos> positions = new ArrayList<>(size);
        for (BlockPos offset : formationOffsets(rotation, size)) {
            BlockPos horizontal = anchor.offset(offset.getX(), 0, offset.getZ());
            BlockPos position;
            if (CityWorlds.isCity(level)) {
                position = CityWorlds.resolvePedestrianFeet(
                        level, horizontal.getX(), horizontal.getZ(), anchor.getY());
                if (position == null
                        || !isPublicPatrolArea(NeonCityGenerator.sample(
                                position.getX(), position.getZ()))) {
                    return List.of();
                }
            } else {
                position = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, horizontal);
                if (!isSafeFeet(level, position)) {
                    return List.of();
                }
            }
            positions.add(position);
        }
        return positions;
    }

    private static FactionEnemy createMember(ServerLevel level, BlockPos position, BlockPos home,
                                             Faction faction, RandomSource random) {
        FactionEnemy enemy = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.NATURAL);
        if (enemy == null) {
            return null;
        }
        enemy.snapTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5,
                random.nextFloat() * 360.0f, 0.0f);
        if (!level.noCollision(enemy)) {
            enemy.discard();
            return null;
        }
        enemy.finalizeSpawn(level, level.getCurrentDifficultyAt(position),
                EntitySpawnReason.NATURAL, null);
        enemy.setHome(home);
        enemy.setAmbientPatrol(true);
        FactionSquads.equip(enemy, faction, random);
        return enemy;
    }

    private static boolean isConcealedFromPlayers(ServerLevel level, FactionEnemy enemy) {
        for (ServerPlayer observer : level.players()) {
            if (observer.isSpectator()) {
                continue;
            }
            if (observer.distanceToSqr(enemy) < MIN_DISTANCE * MIN_DISTANCE
                    || observer.hasLineOfSight(enemy)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isPublicPatrolArea(NeonCityGenerator.UrbanSample sample) {
        if (sample == null || sample.district() == null
                || sample.zone() == dev.modernity.neoncity.MegacityLayout.Zone.OUTSKIRTS
                || sample.zone() == dev.modernity.neoncity.MegacityLayout.Zone.BORDER_WALLED
                || sample.zone() == dev.modernity.neoncity.MegacityLayout.Zone.BORDER_FOREST
                || sample.zone() == dev.modernity.neoncity.MegacityLayout.Zone.BORDER_CLIFF
                || sample.zone() == dev.modernity.neoncity.MegacityLayout.Zone.WILDERNESS) {
            return false;
        }
        return isPublicPatrolRoadClass(sample.roadClass());
    }

    public static boolean isPublicPatrolRoadClass(NeonCityGenerator.RoadClass roadClass) {
        return switch (roadClass) {
            case CENTRAL_PLAZA, DISTRICT_BOULEVARD, LOCAL_STREET, PARK -> true;
            default -> false;
        };
    }

    private static boolean isSafeFeet(ServerLevel level, BlockPos position) {
        return level.isLoaded(position)
                && level.getWorldBorder().isWithinBounds(position)
                && level.getBlockState(position.below()).blocksMotion()
                && level.isEmptyBlock(position)
                && level.isEmptyBlock(position.above());
    }

    /** Pure, unique formation offsets used by tests and runtime. */
    public static List<BlockPos> formationOffsets(int rotation, int size) {
        int count = Math.min(Math.max(0, size), BASE_FORMATION.length);
        List<BlockPos> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int x = BASE_FORMATION[index][0];
            int z = BASE_FORMATION[index][1];
            int rotatedX = switch (rotation & 3) {
                case 1 -> -z;
                case 2 -> -x;
                case 3 -> z;
                default -> x;
            };
            int rotatedZ = switch (rotation & 3) {
                case 1 -> x;
                case 2 -> -z;
                case 3 -> -x;
                default -> z;
            };
            result.add(new BlockPos(rotatedX, 0, rotatedZ));
        }
        return List.copyOf(result);
    }

    public static long clusterSeed(long worldSeed, long epoch, int cellX, int cellZ) {
        long value = worldSeed ^ CLUSTER_SALT ^ epoch * 0x9E3779B97F4A7C15L
                ^ (long) cellX * 0xC2B2AE3D27D4EB4FL
                ^ (long) cellZ * 0x165667B19E3779F9L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static ServerPlayer stableRepresentative(ServerPlayer first, ServerPlayer second) {
        return first.getUUID().compareTo(second.getUUID()) <= 0 ? first : second;
    }
}
