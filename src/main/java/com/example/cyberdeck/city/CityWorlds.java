package com.example.cyberdeck.city;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import dev.modernity.neoncity.NeonCityGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/** Shared, strict detection and street navigation for supported city presets. */
public final class CityWorlds {
    public enum Kind {
        NONE(-1),
        CYBERDECK(-60),
        NEON_CITY(0),
        NEON_MEGACITY(72),
        CITY17(-64, true);

        private final int streetY;
        private final boolean dynamicStreetY;

        Kind(int streetY) {
            this(streetY, false);
        }

        Kind(int streetY, boolean dynamicStreetY) {
            this.streetY = streetY;
            this.dynamicStreetY = dynamicStreetY;
        }

        public int streetY() {
            return streetY;
        }

        public boolean usesDynamicStreetY() {
            return dynamicStreetY;
        }
    }

    private static final ResourceKey<DimensionType> NEON_MEGACITY_DIMENSION_TYPE =
            ResourceKey.create(Registries.DIMENSION_TYPE,
                    Identifier.fromNamespaceAndPath("neoncity", "megacity_overworld"));
    private static final Map<ServerLevel, Boolean> LEGACY_NEON_LEDGER_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final int[][] CARDINAL_DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private CityWorlds() {
    }

    /**
     * Returns a supported city kind. Ordinary noise worlds and vanilla flat presets return
     * {@link Kind#NONE}; Project Moon's marked noise world is an explicit supported exception.
     */
    public static Kind kind(ServerLevel level) {
        // Project Moon keeps vanilla noise terrain but identifies its generated overworld with a
        // custom dimension type bundled directly in Cyberdeck.
        if (level.dimensionTypeRegistration().is(NEON_MEGACITY_DIMENSION_TYPE)) {
            return Kind.NEON_MEGACITY;
        }

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof FlatLevelSource flat)) {
            return Kind.NONE;
        }
        Kind signature = classifyLayers(flat.settings().getLayers());
        if (signature == Kind.CYBERDECK) {
            // The generated-city flag distinguishes the finished Cyberdeck preset from a plain
            // custom flat world that happens to use the same base layers.
            return level.getDataStorage().computeIfAbsent(CityData.TYPE).isBuilt()
                    ? Kind.CYBERDECK : Kind.NONE;
        }
        if (signature == Kind.NEON_CITY) {
            // Cyberdeck 1.1 embedded this generator directly. Its persisted ledger distinguishes
            // those official legacy worlds from a vanilla custom flat world with similar layers.
            return hasLegacyNeonLedger(level) ? Kind.NEON_CITY : Kind.NONE;
        }
        if (signature == Kind.CITY17) {
            return ModList.get().isLoaded("city17") ? Kind.CITY17 : Kind.NONE;
        }
        return Kind.NONE;
    }

    /** Pure Project Moon dimension-marker classifier exposed for regression tests. */
    public static Kind classifyDimensionType(ResourceKey<DimensionType> dimensionType) {
        return NEON_MEGACITY_DIMENSION_TYPE.equals(dimensionType)
                ? Kind.NEON_MEGACITY : Kind.NONE;
    }

    /** Pure layer classifier exposed for regression tests. */
    public static Kind classifyLayers(List<BlockState> rawLayers) {
        List<BlockState> layers = new ArrayList<>();
        for (BlockState state : rawLayers) {
            if (state != null && !state.isAir()) {
                layers.add(state);
            }
        }

        BlockState black = Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState();
        BlockState cyan = Blocks.CONCRETE.pick(DyeColor.CYAN).defaultBlockState();
        BlockState purple = Blocks.CONCRETE.pick(DyeColor.PURPLE).defaultBlockState();
        if (layers.size() == 5
                && layers.get(0).is(Blocks.BEDROCK)
                && layers.subList(1, 5).stream().allMatch(state -> state.is(black.getBlock()))) {
            return Kind.CYBERDECK;
        }
        if (layers.size() == 2
                && layers.get(0).is(black.getBlock())
                && layers.get(1).is(cyan.getBlock())) {
            return Kind.NEON_CITY;
        }
        if (layers.size() == 1 && layers.get(0).is(purple.getBlock())) {
            return Kind.CITY17;
        }
        return Kind.NONE;
    }

    public static boolean isCity(ServerLevel level) {
        return kind(level) != Kind.NONE;
    }

    /** Returns whether {@code feet} is a clear pedestrian location at the preset's street deck. */
    public static boolean isWalkableStreet(ServerLevel level, BlockPos feet) {
        Kind kind = kind(level);
        if (kind == Kind.NONE || (!kind.usesDynamicStreetY()
                && feet.getY() != kind.streetY() + 1)
                || !hasFullyLoadedChunk(level, feet.getX(), feet.getZ())
                || !level.getWorldBorder().isWithinBounds(feet)) {
            return false;
        }
        BlockPos floorPos = feet.below();
        BlockState floor = level.getBlockState(floorPos);
        if (!floor.blocksMotion() || !level.isEmptyBlock(feet) || !level.isEmptyBlock(feet.above())) {
            return false;
        }
        if (kind == Kind.CITY17) {
            BlockPos heightmapFeet = level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet);
            return heightmapFeet.getY() == feet.getY()
                    && isCity17PedestrianSurface(floor)
                    && hasCity17StreetConnectivity(level, feet);
        }
        // The finite Cyberdeck preset explicitly paves pedestrian routes with gray concrete. Its
        // untouched black-concrete void must not become an infinite civilian spawning plane.
        return kind != Kind.CYBERDECK
                || floor.is(Blocks.CONCRETE.pick(DyeColor.GRAY));
    }

    /** Road and plaza surfaces emitted by the Arnis-derived Taiwan atlas. */
    public static boolean isCity17PedestrianSurface(BlockState floor) {
        return floor.is(Blocks.CONCRETE.pick(DyeColor.GRAY))
                || floor.is(Blocks.CONCRETE_POWDER.pick(DyeColor.GRAY));
    }

    /** Resolves a street foot position, including the Taiwan atlas's per-column terrain height. */
    public static BlockPos resolveStreetFeet(ServerLevel level, int x, int z, int preferredY) {
        Kind kind = kind(level);
        if (kind == Kind.NONE) {
            return null;
        }
        BlockPos candidate;
        if (kind.usesDynamicStreetY()) {
            BlockPos probe = new BlockPos(x, preferredY, z);
            if (!hasFullyLoadedChunk(level, x, z)) {
                return null;
            }
            candidate = level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe);
        } else {
            candidate = new BlockPos(x, kind.streetY() + 1, z);
        }
        return isWalkableStreet(level, candidate) ? candidate : null;
    }

    /** Resolves civilian space, restricted to Arnis neighborhoods and parks in Project Moon. */
    public static BlockPos resolvePedestrianFeet(ServerLevel level, int x, int z, int preferredY) {
        BlockPos candidate = resolveStreetFeet(level, x, z, preferredY);
        if (candidate == null) {
            return null;
        }
        return kind(level) != Kind.NEON_MEGACITY
                || NeonCityGenerator.isCivilianPedestrianArea(level, x, z)
                ? candidate : null;
    }

    private static boolean hasLegacyNeonLedger(ServerLevel level) {
        if (Boolean.TRUE.equals(LEGACY_NEON_LEDGER_CACHE.get(level))) {
            return true;
        }
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        Path dimensionRoot = DimensionType.getStorageFolder(level.dimension(), worldRoot);
        Path ledger = dimensionRoot.resolve("data").resolve("neoncity")
                .resolve("generated_chunks_v1.dat");
        boolean exists = Files.isRegularFile(ledger);
        if (exists) {
            LEGACY_NEON_LEDGER_CACHE.put(level, true);
        }
        return exists;
    }

    private static boolean hasCity17StreetConnectivity(ServerLevel level, BlockPos feet) {
        int connected = 0;
        for (int[] direction : CARDINAL_DIRECTIONS) {
            BlockPos probe = feet.offset(direction[0], 0, direction[1]);
            if (!hasFullyLoadedChunk(level, probe.getX(), probe.getZ())) {
                continue;
            }
            BlockPos neighborFeet = level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe);
            if (Math.abs(neighborFeet.getY() - feet.getY()) <= 1
                    && level.isEmptyBlock(neighborFeet)
                    && level.isEmptyBlock(neighborFeet.above())
                    && isCity17PedestrianSurface(level.getBlockState(neighborFeet.below()))) {
                connected++;
            }
        }
        return connected >= 2;
    }

    /** Finds a loaded street location in a ring around an origin. */
    public static BlockPos findStreetNear(ServerLevel level, BlockPos origin,
                                          int minDistance, int maxDistance,
                                          int attempts, RandomSource random) {
        return findNear(level, origin, minDistance, maxDistance, attempts, random, false);
    }

    /** Finds a loaded civilian destination without selecting Project Moon highways. */
    public static BlockPos findPedestrianAreaNear(ServerLevel level, BlockPos origin,
                                                   int minDistance, int maxDistance,
                                                   int attempts, RandomSource random) {
        return findNear(level, origin, minDistance, maxDistance, attempts, random, true);
    }

    private static BlockPos findNear(ServerLevel level, BlockPos origin,
                                     int minDistance, int maxDistance,
                                     int attempts, RandomSource random,
                                     boolean pedestriansOnly) {
        Kind kind = kind(level);
        if (kind == Kind.NONE) {
            return null;
        }
        for (int attempt = 0; attempt < attempts; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            int distance = minDistance + random.nextInt(maxDistance - minDistance + 1);
            int x = origin.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = origin.getZ() + (int) Math.round(Math.sin(angle) * distance);
            BlockPos candidate = resolveFeet(
                    level, x, z, origin.getY(), pedestriansOnly);
            if (candidate != null) {
                return candidate;
            }
        }

        // Generated streets can be sparse around large parcels. Keep a deterministic fallback,
        // but cap it to the caller's attempt budget. The old unbounded perimeter walk could test
        // thousands of columns in one server tick.
        int perimeterProbes = 0;
        for (int radius = minDistance; radius <= maxDistance; radius += 2) {
            for (int offset = -radius; offset <= radius; offset += 2) {
                if (perimeterProbes++ >= attempts) return null;
                BlockPos north = resolveFeet(level,
                        origin.getX() + offset, origin.getZ() - radius, origin.getY(),
                        pedestriansOnly);
                if (north != null) {
                    return north;
                }
                if (perimeterProbes++ >= attempts) return null;
                BlockPos south = resolveFeet(level,
                        origin.getX() + offset, origin.getZ() + radius, origin.getY(),
                        pedestriansOnly);
                if (south != null) {
                    return south;
                }
                if (perimeterProbes++ >= attempts) return null;
                BlockPos west = resolveFeet(level,
                        origin.getX() - radius, origin.getZ() + offset, origin.getY(),
                        pedestriansOnly);
                if (west != null) {
                    return west;
                }
                if (perimeterProbes++ >= attempts) return null;
                BlockPos east = resolveFeet(level,
                        origin.getX() + radius, origin.getZ() + offset, origin.getY(),
                        pedestriansOnly);
                if (east != null) {
                    return east;
                }
            }
        }
        return null;
    }

    private static BlockPos resolveFeet(ServerLevel level, int x, int z, int preferredY,
                                        boolean pedestriansOnly) {
        return pedestriansOnly
                ? resolvePedestrianFeet(level, x, z, preferredY)
                : resolveStreetFeet(level, x, z, preferredY);
    }

    /** Finds the farthest sampled street point away from a gunshot. */
    public static BlockPos findStreetAway(ServerLevel level, BlockPos origin, Vec3 threat,
                                          int minDistance, int maxDistance,
                                          int attempts, RandomSource random) {
        return findAway(level, origin, threat, minDistance, maxDistance, attempts, random, false);
    }

    /** Finds a civilian escape destination without choosing a Project Moon highway. */
    public static BlockPos findPedestrianAreaAway(ServerLevel level, BlockPos origin, Vec3 threat,
                                                   int minDistance, int maxDistance,
                                                   int attempts, RandomSource random) {
        return findAway(level, origin, threat, minDistance, maxDistance, attempts, random, true);
    }

    private static BlockPos findAway(ServerLevel level, BlockPos origin, Vec3 threat,
                                     int minDistance, int maxDistance,
                                     int attempts, RandomSource random,
                                     boolean pedestriansOnly) {
        Kind kind = kind(level);
        if (kind == Kind.NONE) {
            return null;
        }
        double awayAngle = Math.atan2(origin.getZ() + 0.5 - threat.z,
                origin.getX() + 0.5 - threat.x);
        BlockPos best = null;
        double bestDistance = Vec3.atCenterOf(origin).distanceToSqr(threat);
        for (int attempt = 0; attempt < attempts; attempt++) {
            double angle = awayAngle + (random.nextDouble() - 0.5) * Math.PI * 0.9;
            int distance = minDistance + random.nextInt(maxDistance - minDistance + 1);
            BlockPos candidate = resolveFeet(
                    level,
                    origin.getX() + (int) Math.round(Math.cos(angle) * distance),
                    origin.getZ() + (int) Math.round(Math.sin(angle) * distance),
                    origin.getY(), pedestriansOnly);
            if (candidate == null) {
                continue;
            }
            double threatDistance = Vec3.atCenterOf(candidate).distanceToSqr(threat);
            if (threatDistance > bestDistance) {
                bestDistance = threatDistance;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * {@link ServerLevel#isLoaded(BlockPos)} can be true while a chunk holder is still promoting
     * to a full {@code LevelChunk}. Reading blocks at that point makes the server thread wait for
     * generation. Runtime population searches must skip those candidates instead.
     */
    public static boolean hasFullyLoadedChunk(ServerLevel level, BlockPos position) {
        return hasFullyLoadedChunk(level, position.getX(), position.getZ());
    }

    public static boolean hasFullyLoadedChunk(ServerLevel level, int blockX, int blockZ) {
        return level.getChunkSource().getChunkNow(
                Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16)) != null;
    }
}
