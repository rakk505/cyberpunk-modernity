package dev.modernity.neoncity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;

/** Deterministic, sparse supply crates placed after a city's structures and terrain. */
public final class UrbanCrateGeneration {
    private static final long CRATE_SALT = 0x4352415445534954L;
    private static final long POSITION_SALT = 0x4352415445504F53L;
    private static final long LOOT_SALT = 0x43524154454C4F4FL;
    private static final String MANAGED_CRATE_TAG = "neoncity_urban_supply_crate";
    private static final int CHUNK_FREQUENCY = 7;
    private static final int MAX_PLANS_PER_CHUNK = 24;
    private static final int MIN_LOCAL_COORDINATE = 2;
    private static final int MAX_LOCAL_COORDINATE = 13;
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    public enum LootTier {
        COMMON(table("urban_supply_common")),
        TECH(table("urban_supply_tech")),
        RARE(table("urban_supply_rare"));

        private final ResourceKey<LootTable> lootTable;

        LootTier(ResourceKey<LootTable> lootTable) {
            this.lootTable = lootTable;
        }

        public ResourceKey<LootTable> lootTable() {
            return lootTable;
        }
    }

    /** One ranked placement option. Multiple options let live geometry reject blocked sites. */
    public record CratePlan(
            BlockPos position,
            Direction facing,
            LootTier tier,
            long lootSeed,
            NeonCityGenerator.RoadClass roadClass) {
        public CratePlan {
            position = position.immutable();
            if (facing == null || facing.getAxis().isVertical()) {
                throw new IllegalArgumentException("supply crate must face horizontally");
            }
        }
    }

    private record RankedPlan(long rank, CratePlan plan) {
    }

    private UrbanCrateGeneration() {
    }

    /** Places at most one crate and never reinitializes loot on an existing managed crate. */
    public static int decorateChunk(
            ServerLevel level,
            ChunkPos chunk,
            NeonCityGenerator.UrbanSample[][] samples) {
        List<CratePlan> plans = plans(NeonCityGenerator.layout().seed(), chunk, samples);
        for (CratePlan plan : plans) {
            if (isManagedCrate(level, plan.position())) {
                return 0;
            }
        }
        for (CratePlan plan : plans) {
            if (placePlannedCrate(level, plan)) {
                return 1;
            }
        }
        return 0;
    }

    static List<CratePlan> plans(
            long layoutSeed,
            ChunkPos chunk,
            NeonCityGenerator.UrbanSample[][] samples) {
        if (!isSelectedChunk(layoutSeed, chunk) || samples.length < 18) {
            return List.of();
        }
        for (NeonCityGenerator.UrbanSample[] row : samples) {
            if (row.length < 18) {
                return List.of();
            }
        }

        ArrayList<RankedPlan> candidates = new ArrayList<>();
        for (int localZ = MIN_LOCAL_COORDINATE; localZ <= MAX_LOCAL_COORDINATE; localZ++) {
            for (int localX = MIN_LOCAL_COORDINATE; localX <= MAX_LOCAL_COORDINATE; localX++) {
                NeonCityGenerator.UrbanSample sample = samples[localZ + 1][localX + 1];
                if (!isEligible(sample)) {
                    continue;
                }
                int worldX = chunk.getMinBlockX() + localX;
                int worldZ = chunk.getMinBlockZ() + localZ;
                long rank = MegacityLayout.mix(layoutSeed ^ POSITION_SALT, worldX, worldZ);
                Direction facing = HORIZONTAL_DIRECTIONS[
                        Math.floorMod((int) (rank >>> 32), HORIZONTAL_DIRECTIONS.length)];
                candidates.add(new RankedPlan(
                        rank,
                        new CratePlan(
                                new BlockPos(worldX, sample.groundY() + 1, worldZ),
                                facing,
                                lootTier(sample, rank),
                                MegacityLayout.mix(layoutSeed ^ LOOT_SALT, worldX, worldZ),
                                sample.roadClass())));
            }
        }
        candidates.sort(Comparator.comparingLong(RankedPlan::rank));
        return candidates.stream()
                .limit(MAX_PLANS_PER_CHUNK)
                .map(RankedPlan::plan)
                .toList();
    }

    static boolean isSelectedChunk(long layoutSeed, ChunkPos chunk) {
        long hash = MegacityLayout.mix(layoutSeed ^ CRATE_SALT, chunk.x(), chunk.z());
        return Math.floorMod((int) (hash ^ (hash >>> 32)), CHUNK_FREQUENCY) == 0;
    }

    static boolean placePlannedCrate(ServerLevel level, CratePlan plan) {
        if (isManagedCrate(level, plan.position()) || !canPlace(level, plan)) {
            return false;
        }

        BlockPos position = plan.position();
        BlockState previous = level.getBlockState(position);
        Direction backing = backingWall(level, plan);
        if (backing == null) {
            return false;
        }
        BlockState barrel = Blocks.BARREL.defaultBlockState()
                .setValue(BarrelBlock.FACING, backing.getOpposite());
        if (!level.setBlock(position, barrel, Block.UPDATE_ALL)) {
            return false;
        }
        if (!(level.getBlockEntity(position)
                instanceof RandomizableContainerBlockEntity container)) {
            level.setBlock(position, previous, Block.UPDATE_ALL);
            return false;
        }
        container.setLootTable(plan.tier().lootTable());
        container.setLootTableSeed(plan.lootSeed());
        container.getPersistentData().putBoolean(MANAGED_CRATE_TAG, true);
        container.setChanged();
        level.sendBlockUpdated(position, barrel, barrel, Block.UPDATE_CLIENTS);
        return true;
    }

    private static boolean canPlace(ServerLevel level, CratePlan plan) {
        BlockPos position = plan.position();
        BlockState current = level.getBlockState(position);
        BlockState above = level.getBlockState(position.above());
        BlockPos floor = position.below();
        if ((!current.isAir() && !current.canBeReplaced())
                || !above.isAir()
                || !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
            return false;
        }
        return backingWall(level, plan) != null;
    }

    private static Direction backingWall(ServerLevel level, CratePlan plan) {
        Direction preferred = plan.facing().getOpposite();
        Direction[] directions = {
                preferred,
                preferred.getClockWise(),
                preferred.getCounterClockWise(),
                preferred.getOpposite()
        };
        for (Direction direction : directions) {
            BlockPos neighbour = plan.position().relative(direction);
            if (level.getBlockState(neighbour).isFaceSturdy(
                    level, neighbour, direction.getOpposite())) {
                return direction;
            }
        }
        return null;
    }

    static boolean isManagedCrate(ServerLevel level, BlockPos position) {
        if (!(level.getBlockEntity(position)
                instanceof RandomizableContainerBlockEntity container)) {
            return false;
        }
        if (container.getPersistentData().getBoolean(MANAGED_CRATE_TAG).orElse(false)) {
            return true;
        }
        ResourceKey<LootTable> lootTable = container.getLootTable();
        if (lootTable == null) {
            return false;
        }
        for (LootTier tier : LootTier.values()) {
            if (tier.lootTable().equals(lootTable)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEligible(NeonCityGenerator.UrbanSample sample) {
        if (sample.zone() == MegacityLayout.Zone.WILDERNESS
                || sample.zone() == MegacityLayout.Zone.BORDER_WALLED
                || sample.zone() == MegacityLayout.Zone.BORDER_FOREST
                || sample.zone() == MegacityLayout.Zone.BORDER_CLIFF) {
            return false;
        }
        return switch (sample.roadClass()) {
            case SERVICE_ALLEY -> sample.zone() == MegacityLayout.Zone.BACKSTREETS;
            case HIGHWAY_BUFFER, EXTRACTION_SITE, CONTAINER_PORT -> true;
            default -> false;
        };
    }

    private static LootTier lootTier(NeonCityGenerator.UrbanSample sample, long hash) {
        int roll = Math.floorMod((int) (hash ^ (hash >>> 32)), 100);
        int rareChance = sample.zone() == MegacityLayout.Zone.NEST ? 8 : 3;
        if (roll < rareChance) {
            return LootTier.RARE;
        }
        int techChance = switch (sample.roadClass()) {
            case EXTRACTION_SITE, CONTAINER_PORT -> 46;
            default -> 30;
        };
        return roll < techChance ? LootTier.TECH : LootTier.COMMON;
    }

    private static ResourceKey<LootTable> table(String name) {
        return ResourceKey.create(
                Registries.LOOT_TABLE,
                Identifier.fromNamespaceAndPath("neoncity", "chests/" + name));
    }
}
