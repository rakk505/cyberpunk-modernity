package com.example.cyberdeck.city;

import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareItems;
import com.example.cyberdeck.weapon.AmmoItems;
import com.example.cyberdeck.weapon.AmmoType;
import com.example.cyberdeck.weapon.GunType;
import com.example.cyberdeck.weapon.WeaponItems;
import dev.modernity.neoncity.MegacityLayout;
import dev.modernity.neoncity.NeonCityGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/** Deterministic placement and reward generation for both city cache variants. */
public final class CityLootGeneration {
    private static final long CACHE_SALT = 0x4341434845535631L;
    private static final long LOOT_SALT = 0x4C4F4F5453454544L;
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };
    private static final GunType[] FIREARMS = Arrays.stream(GunType.values())
            .filter(gun -> gun != GunType.MANTIS_BLADE)
            .toArray(GunType[]::new);

    public enum CacheKind {
        BLACK_LOOT,
        AMMO
    }

    private CityLootGeneration() {
    }

    /** Adds guaranteed gun, cyberware, and ammunition plus one to three additional items. */
    public static void populate(BlackLootCacheBlockEntity cache, RandomSource random) {
        cache.clearContent();
        putRandom(cache, new ItemStack(WeaponItems.gun(
                FIREARMS[random.nextInt(FIREARMS.length)]).get()), random);
        putRandom(cache, new ItemStack(CyberwareItems.item(
                Cyberware.VALUES[random.nextInt(Cyberware.VALUES.length)]).get()), random);
        AmmoType[] ammoTypes = AmmoType.values();
        AmmoType ammoType = ammoTypes[random.nextInt(ammoTypes.length)];
        int rewardSteps = (AmmoCacheBlock.MAX_REWARD - AmmoCacheBlock.MIN_REWARD)
                / AmmoCacheBlock.REWARD_STEP;
        int ammoAmount = AmmoCacheBlock.MIN_REWARD
                + random.nextInt(rewardSteps + 1) * AmmoCacheBlock.REWARD_STEP;
        putRandom(cache, new ItemStack(AmmoItems.item(ammoType).get(), ammoAmount), random);

        int extras = 1 + random.nextInt(3);
        for (int i = 0; i < extras; i++) {
            ItemStack reward;
            if (random.nextBoolean()) {
                reward = new ItemStack(WeaponItems.gun(
                        FIREARMS[random.nextInt(FIREARMS.length)]).get());
            } else {
                reward = new ItemStack(CyberwareItems.item(
                        Cyberware.VALUES[random.nextInt(Cyberware.VALUES.length)]).get());
            }
            putRandom(cache, reward, random);
        }
        cache.setChanged();
    }

    /** Places and initializes one generated cache when its complete footprint is unobstructed. */
    public static boolean place(
            ServerLevel level, BlockPos position, CacheKind kind, Direction facing, long seed) {
        Direction placementFacing = placementFacing(level, position, kind, facing);
        if (placementFacing == null) {
            return false;
        }

        BlockState state = switch (kind) {
            case BLACK_LOOT -> CityLootBlocks.BLACK_LOOT_CACHE.get().defaultBlockState()
                    .setValue(BlackLootCacheBlock.FACING, placementFacing);
            case AMMO -> CityLootBlocks.AMMO_CACHE.get().defaultBlockState()
                    .setValue(AmmoCacheBlock.FACING, placementFacing);
        };
        if (!level.setBlock(position, state, Block.UPDATE_ALL)) {
            return false;
        }
        if (kind == CacheKind.BLACK_LOOT) {
            if (!(level.getBlockEntity(position) instanceof BlackLootCacheBlockEntity cache)) {
                level.removeBlock(position, false);
                return false;
            }
            populate(cache, RandomSource.create(seed ^ LOOT_SALT));
        }
        return true;
    }

    /** Low-density cache pass for each newly generated Project Moon city chunk. */
    public static boolean decorateMegacityChunk(
            ServerLevel level,
            ChunkPos chunk,
            NeonCityGenerator.UrbanSample[][] samples) {
        long hash = mix(NeonCityGenerator.layout().seed() ^ CACHE_SALT, chunk.x(), chunk.z());
        CacheKind kind = cacheKindForHash(hash);
        if (kind == null) {
            return false;
        }
        Direction facing = HORIZONTAL[Math.floorMod((int) (hash >>> 8), HORIZONTAL.length)];
        int start = Math.floorMod((int) (hash >>> 16), 256);

        for (int attempt = 0; attempt < 256; attempt++) {
            int index = Math.floorMod(start + attempt * 73, 256);
            int localX = index & 15;
            int localZ = index >>> 4;
            if (localX < 2 || localX > 13 || localZ < 2 || localZ > 13) {
                continue;
            }
            NeonCityGenerator.UrbanSample sample = samples[localZ + 1][localX + 1];
            if (!isCacheStreet(sample)) {
                continue;
            }
            BlockPos position = new BlockPos(
                    chunk.getMinBlockX() + localX,
                    sample.groundY() + 1,
                    chunk.getMinBlockZ() + localZ);
            if (place(level, position, kind, facing, hash)) {
                return true;
            }
        }
        return false;
    }

    /** Pure density decision exposed for regression tests. */
    public static CacheKind cacheKind(long worldSeed, int chunkX, int chunkZ) {
        return cacheKindForHash(mix(worldSeed ^ CACHE_SALT, chunkX, chunkZ));
    }

    /**
     * Only black-loot caches are seeded. Ammunition caches were two of every sixteen chunks, and
     * each one paid for a bounded street search during generation to hand out a resource nothing
     * consumes; dropping them removes two thirds of all cache searching. {@link CacheKind#AMMO}
     * itself stays so hand-placed crates and existing saves keep working.
     */
    private static CacheKind cacheKindForHash(long hash) {
        return switch (Math.floorMod((int) (hash ^ (hash >>> 32)), 16)) {
            case 0 -> CacheKind.BLACK_LOOT;
            default -> null;
        };
    }

    private static boolean isCacheStreet(NeonCityGenerator.UrbanSample sample) {
        if (sample.zone() == MegacityLayout.Zone.WILDERNESS) {
            return false;
        }
        return sample.roadClass() == NeonCityGenerator.RoadClass.LOCAL_STREET
                || sample.roadClass() == NeonCityGenerator.RoadClass.SERVICE_ALLEY
                || sample.roadClass() == NeonCityGenerator.RoadClass.CENTRAL_PLAZA;
    }

    private static Direction placementFacing(
            ServerLevel level, BlockPos position, CacheKind kind, Direction facing) {
        if (facing == null || facing.getAxis() == Direction.Axis.Y
                || !clearColumn(level, position)) {
            return null;
        }
        Direction backing = backingWall(level, position, facing);
        if (backing == null) {
            return null;
        }
        Direction resolvedFacing = backing.getOpposite();
        if (kind == CacheKind.BLACK_LOOT) {
            Direction width = resolvedFacing.getClockWise();
            if (!clearColumn(level, position.relative(width))
                    || !clearColumn(level, position.relative(width.getOpposite()))) {
                return null;
            }
        }
        return resolvedFacing;
    }

    private static Direction backingWall(
            ServerLevel level, BlockPos position, Direction requestedFacing) {
        Direction preferred = requestedFacing.getOpposite();
        Direction[] directions = {
                preferred,
                preferred.getClockWise(),
                preferred.getCounterClockWise(),
                preferred.getOpposite()
        };
        for (Direction direction : directions) {
            BlockPos neighbour = position.relative(direction);
            if (level.getBlockState(neighbour).isFaceSturdy(
                    level, neighbour, direction.getOpposite())) {
                return direction;
            }
        }
        return null;
    }

    private static boolean clearColumn(ServerLevel level, BlockPos position) {
        return level.getWorldBorder().isWithinBounds(position)
                && level.isEmptyBlock(position)
                && level.isEmptyBlock(position.above())
                && level.getBlockState(position.below())
                        .isFaceSturdy(level, position.below(), Direction.UP);
    }

    private static void putRandom(
            BlackLootCacheBlockEntity cache, ItemStack reward, RandomSource random) {
        int start = random.nextInt(cache.getContainerSize());
        for (int offset = 0; offset < cache.getContainerSize(); offset++) {
            int slot = (start + offset) % cache.getContainerSize();
            if (cache.getItem(slot).isEmpty()) {
                cache.setItem(slot, reward);
                return;
            }
        }
    }

    private static long mix(long seed, int x, int z) {
        long value = seed ^ (long) x * 0x9E3779B97F4A7C15L
                ^ (long) z * 0xC2B2AE3D27D4EB4FL;
        value += 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
