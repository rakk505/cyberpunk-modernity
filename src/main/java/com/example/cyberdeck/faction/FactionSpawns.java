package com.example.cyberdeck.faction;

import com.example.cyberdeck.city.CityWorlds;
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
 * Deterministic faction squad generation. Each occupied spatial cell gets at most one spawn attempt
 * per epoch; a successful spawn is a fixed four-member formation sharing one faction and patrol
 * anchor. This avoids singleton trickles, duplicate offsets, and partial pseudo-squads.
 */
public final class FactionSpawns {
    public static final int SPAWN_INTERVAL = 600;
    public static final int CLUSTER_SIZE = 4;
    public static final int MIN_CLUSTER_SIZE = CLUSTER_SIZE;
    public static final int NEARBY_CAP = 12;
    private static final int MIN_DISTANCE = 26;
    private static final int MAX_DISTANCE = 46;
    private static final double NEARBY_RADIUS = 72.0;
    private static final long CLUSTER_SALT = 0x434C55535445524CL;
    private static final int[][] BASE_FORMATION = {
            {0, 0}, {2, 1}, {-2, 1}, {1, -2}, {-1, -2}, {3, 0}
    };

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level.dimension() != Level.OVERWORLD
                || level.getGameTime() % SPAWN_INTERVAL != 0) {
            return;
        }

        long epoch = level.getGameTime() / SPAWN_INTERVAL;
        Map<Long, ServerPlayer> populationCells = new TreeMap<>();
        for (ServerPlayer player : level.players()) {
            int cellX = Math.floorDiv(player.getBlockX(), 128);
            int cellZ = Math.floorDiv(player.getBlockZ(), 128);
            long cellKey = pack(cellX, cellZ);
            populationCells.merge(cellKey, player, FactionSpawns::stableRepresentative);
        }
        for (Map.Entry<Long, ServerPlayer> entry : populationCells.entrySet()) {
            ServerPlayer player = entry.getValue();
            int cellX = Math.floorDiv(player.getBlockX(), 128);
            int cellZ = Math.floorDiv(player.getBlockZ(), 128);
            RandomSource random = RandomSource.create(clusterSeed(
                    level.getSeed(), epoch, cellX, cellZ));
            trySpawnCluster(level, player, random);
        }
    }

    private void trySpawnCluster(ServerLevel level, ServerPlayer player, RandomSource random) {
        AABB nearbyArea = player.getBoundingBox().inflate(NEARBY_RADIUS);
        int nearby = level.getEntitiesOfClass(FactionEnemy.class, nearbyArea,
                FactionEnemy::isAlive).size();
        int capacity = NEARBY_CAP - nearby;
        if (capacity < MIN_CLUSTER_SIZE) {
            return;
        }
        int requested = CLUSTER_SIZE;

        BlockPos anchor = findSpawnAnchor(level, player, random);
        if (anchor == null) {
            return;
        }
        int rotation = random.nextInt(4);
        List<BlockPos> positions = resolveFormation(level, anchor, rotation, requested);
        if (positions.size() < requested) {
            return; // all-or-nothing: never emit a broken partial squad
        }

        Faction faction = Faction.VALUES[random.nextInt(Faction.VALUES.length)];
        List<FactionEnemy> members = new ArrayList<>(requested);
        for (BlockPos position : positions) {
            FactionEnemy enemy = createMember(level, position, anchor, faction, random);
            if (enemy == null) {
                for (FactionEnemy member : members) {
                    member.discard();
                }
                return;
            }
            members.add(enemy);
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
    }

    private static BlockPos findSpawnAnchor(ServerLevel level, ServerPlayer player,
                                            RandomSource random) {
        if (CityWorlds.isCity(level)) {
            return CityWorlds.findStreetNear(level, player.blockPosition(),
                    MIN_DISTANCE, MAX_DISTANCE, 36, random);
        }
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            int distance = MIN_DISTANCE + random.nextInt(MAX_DISTANCE - MIN_DISTANCE + 1);
            int x = player.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = player.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            BlockPos position = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,
                    new BlockPos(x, 0, z));
            if (isSafeFeet(level, position)) {
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
                position = horizontal;
                if (!CityWorlds.isWalkableStreet(level, position)) {
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
        FactionSquads.equip(enemy, faction, random);
        return enemy;
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
