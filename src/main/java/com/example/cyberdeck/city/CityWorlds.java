package com.example.cyberdeck.city;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/** Shared, strict detection and street navigation for supported city presets. */
public final class CityWorlds {
    public enum Kind {
        NONE(-1),
        CYBERDECK(-60),
        NEON_CITY(0);

        private final int streetY;

        Kind(int streetY) {
            this.streetY = streetY;
        }

        public int streetY() {
            return streetY;
        }
    }

    private CityWorlds() {
    }

    /**
     * Returns a supported city kind. Normal/noise worlds and ordinary vanilla flat presets return
     * {@link Kind#NONE}, which is the hard gate used by civilian spawning.
     */
    public static Kind kind(ServerLevel level) {
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
            // The layer signature is intentionally strict, and the companion mod must also be
            // loaded so an ordinary vanilla black/cyan superflat never gains civilians.
            return ModList.get().isLoaded("neoncity") ? Kind.NEON_CITY : Kind.NONE;
        }
        return Kind.NONE;
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
        return Kind.NONE;
    }

    public static boolean isCity(ServerLevel level) {
        return kind(level) != Kind.NONE;
    }

    /** Returns whether {@code feet} is a clear pedestrian location at the preset's street deck. */
    public static boolean isWalkableStreet(ServerLevel level, BlockPos feet) {
        Kind kind = kind(level);
        if (kind == Kind.NONE || feet.getY() != kind.streetY() + 1
                || !level.isLoaded(feet) || !level.getWorldBorder().isWithinBounds(feet)) {
            return false;
        }
        BlockPos floorPos = feet.below();
        BlockState floor = level.getBlockState(floorPos);
        if (!floor.blocksMotion() || !level.isEmptyBlock(feet) || !level.isEmptyBlock(feet.above())) {
            return false;
        }
        // The finite Cyberdeck preset explicitly paves pedestrian routes with gray concrete. Its
        // untouched black-concrete void must not become an infinite civilian spawning plane.
        return kind != Kind.CYBERDECK
                || floor.is(Blocks.CONCRETE.pick(DyeColor.GRAY));
    }

    /** Finds a loaded street location in a ring around an origin. */
    public static BlockPos findStreetNear(ServerLevel level, BlockPos origin,
                                          int minDistance, int maxDistance,
                                          int attempts, RandomSource random) {
        Kind kind = kind(level);
        if (kind == Kind.NONE) {
            return null;
        }
        for (int attempt = 0; attempt < attempts; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            int distance = minDistance + random.nextInt(maxDistance - minDistance + 1);
            int x = origin.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = origin.getZ() + (int) Math.round(Math.sin(angle) * distance);
            BlockPos candidate = new BlockPos(x, kind.streetY() + 1, z);
            if (isWalkableStreet(level, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Finds the farthest sampled street point away from a gunshot. */
    public static BlockPos findStreetAway(ServerLevel level, BlockPos origin, Vec3 threat,
                                          int minDistance, int maxDistance,
                                          int attempts, RandomSource random) {
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
            BlockPos candidate = new BlockPos(
                    origin.getX() + (int) Math.round(Math.cos(angle) * distance),
                    kind.streetY() + 1,
                    origin.getZ() + (int) Math.round(Math.sin(angle) * distance));
            double threatDistance = Vec3.atCenterOf(candidate).distanceToSqr(threat);
            if (threatDistance > bestDistance && isWalkableStreet(level, candidate)) {
                bestDistance = threatDistance;
                best = candidate;
            }
        }
        return best;
    }
}
